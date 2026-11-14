package trade.nesty.app.evm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trade.nesty.app.keys.keccak256
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Why a trade did not start. Nothing was signed. */
class TradeException(val code: String, message: String) : Exception(message)

enum class StepKind {
    SWAP,
    APPROVE,

    /** On Solana: SOL into the token a coin is priced in (a buy), or that token back to SOL (a sell). */
    FUND,
}

enum class StepStatus { QUEUED, SENDING, SUBMITTED, CONFIRMED, FAILED, BLOCKED, SKIPPED }

enum class TradeStatus { WORKING, DONE, FAILED, BLOCKED }

/** One transaction of a trade: the swap itself, or an approval before or after it. */
data class TradeStep(
    val kind: StepKind,
    val status: StepStatus = StepStatus.QUEUED,
    val error: String? = null,
    val hash: String? = null,
    val nonce: Long? = null,
    val amountIn: BigInteger? = null,
    /** What a confirmed swap delivered: tokens for a buy, native for a sell. */
    val amountOut: BigInteger? = null,
    val feeWei: BigInteger? = null,
    /** While this transaction waits behind an earlier nonce that never went out. */
    val waitingNonce: Long? = null,
)

/** A buy or a sell from the active wallet, as the screens show it. */
data class Trade(
    val id: String,
    val side: Side,
    val chainId: Long,
    val wallet: String,
    val token: String,
    val symbol: String?,
    /** The pool it trades on, as its venue names it. */
    val venue: String,
    val steps: List<TradeStep>,
    val status: TradeStatus,
    /** The checks of a trade the validator refused, each with its reason. */
    val blocked: List<BlockedAction>?,
    val startedAt: Long,
    /** What a sell paid out in, when not the chain's own coin: a swap's amountOut is in it. */
    val payout: Payout? = null,
    /** The coin's decimals, when the engine read them from chain. */
    val decimals: Int? = null,
) {
    val swap: TradeStep get() = steps.first { it.kind == StepKind.SWAP }
}

/** A token a sell paid out in, with its dollar price when known. */
data class Payout(val symbol: String, val decimals: Int, val usd: Double?)

/** The user's settings for one trade. Speed is the chain's preset (priority fee, max fee). */
data class TradePrefs(
    val slippageBps: Int,
    val gas: GasPreset,
    /** The extension's defaults. */
    val maxGasUsd: Double = 10.0,
    val unlimitedApprovals: Boolean = true,
    val approvalBufferBps: Int = 1_000,
)

data class BuyRequest(
    val chainId: Long,
    val token: String,
    /** Exactly what the user confirmed: no transaction of this buy may carry more. */
    val amount: BigInteger,
    val prefs: TradePrefs,
    val symbol: String? = null,
    /** v4 pool ids the server named for this coin; each is checked on chain. */
    val poolHints: List<String> = emptyList(),
)

data class SellRequest(
    val chainId: Long,
    val token: String,
    /** Share of the wallet's balance, in basis points (10 000 = all). */
    val bps: Int,
    val prefs: TradePrefs,
    val symbol: String? = null,
    val poolHints: List<String> = emptyList(),
)

/** A quote to show. The trade quotes again and checks everything before it signs. */
data class Quote(val amountIn: BigInteger, val out: BigInteger, val priceImpactBps: Int?, val venue: String)

/**
 * The EVM trade engine for the active wallet, ported from the extension's trading.ts and
 * core.ts: route → build → validate → simulate → sign → send → watch, one transaction at a
 * time. Nothing is signed unless the validator passed it; swaps are simulated before signing
 * except a sell right behind its own approval (the simulation would fail for that reason).
 *
 * Left out of the extension's engine, on purpose: the aggregator fallback, the transfer-tax
 * probe (its call is not allowed through Nesty's read-only RPC, so no tax is assumed in the
 * minimums: the simulation still refuses a trade a tax would make revert), funded trades in
 * separate transactions (only where no Nesty Router can do them in one), several wallets at
 * once, and speed-up or cancel.
 */
class EvmTrader(
    private val rpc: EvmRpc,
    private val sender: TxSender,
    private val nativeUsd: suspend (EvmChain) -> Double?,
    private val routing: Routing = Routing(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = RECEIPT_POLL_MS,
    /** A swap landed: the app asks the server for its fill and keeps it as the entry. */
    private val onLanded: suspend (Trade) -> Unit = {},
) {
    private val readers = ConcurrentHashMap<Long, ChainReader>()
    private val legCaches = ConcurrentHashMap<Long, LegCache>()
    private val baseFees = ConcurrentHashMap<Long, Pair<Long, BigInteger>>()
    private val nonces = NonceManager({ chain, address -> reader(chain).pendingNonce(address) }, now)
    private val counter = AtomicLong()

    private val recentTrades = MutableStateFlow<List<Trade>>(emptyList())

    /** This session's trades, newest first. */
    val trades: StateFlow<List<Trade>> = recentTrades.asStateFlow()

    fun reader(chain: EvmChain): ChainReader = readers.getOrPut(chain.id) { ChainReader(chain, rpc) }

    fun venueCtx(chain: EvmChain, hints: List<String> = emptyList()) =
        VenueCtx(chain, reader(chain), now, hints, legCaches.getOrPut(chain.id) { LegCache() })

    private fun chainOf(id: Long) = EvmChains.byId(id) ?: throw TradeException("not_found", "Trading is not available on this chain.")

    // ── quotes ──────────────────────────────────────────────────────────────────────

    /**
     * The best pool's output for each amount, with its price impact against a reference
     * hundred times smaller, as the extension's panel quotes. `refresh`: the same amounts
     * asked again every few seconds.
     */
    suspend fun quotes(
        chainId: Long,
        token: String,
        side: Side,
        amounts: List<BigInteger>,
        hints: List<String> = emptyList(),
        refresh: Boolean = true,
    ): List<Quote?> {
        val chain = EvmChains.byId(chainId) ?: return amounts.map { null }
        if (amounts.isEmpty()) return emptyList()
        val smallest = amounts.filter { it.signum() > 0 }.minOrNull()
            ?: if (side == Side.BUY) parseEther("0.001") else return amounts.map { null }
        val ref = (smallest / big(100)).takeIf { it.signum() > 0 } ?: BigInteger.ONE
        val ctx = venueCtx(chain, hints)
        val routes = routing.bestWithRef(ctx, token, side, ref, amounts, refresh)
        return routes.mapIndexed { i, r ->
            r.route?.let { route ->
                val impact = r.refOut?.takeIf { it.signum() > 0 }?.let { TradeMath.priceImpactBps(amounts[i], route.out, ref, it) }
                Quote(amounts[i], route.out, impact, route.pool.label)
            }
        }
    }

    /** Why no pool trades `token` right now, when a venue knows (a full curve waiting for its pool). */
    suspend fun whyNone(chainId: Long, token: String, hints: List<String> = emptyList()): String? {
        val chain = EvmChains.byId(chainId) ?: return null
        return routing.whyNone(venueCtx(chain, hints), token)
    }

    /**
     * What transactions of these kinds are expected to cost now at `preset`, for showing before a
     * trade: the base fee and tip on typical gas, as the trade's own fee check counts it.
     */
    suspend fun networkFeeWei(chainId: Long, kinds: List<GasKind>, preset: GasPreset): BigInteger? {
        val chain = EvmChains.byId(chainId) ?: return null
        val reader = reader(chain)
        val base = orNull { baseFee(chain, reader) } ?: return null
        val fees = TradeMath.computeFees(chain, base, preset)
        return kinds.fold(BigInteger.ZERO) { sum, kind ->
            sum + TradeMath.expectedFeeWei(chain, fees, base, TradeMath.staticGas(chain, kind), false, kind)
        }
    }

    /** The wallet's balance of `token`, or null when it could not be read. */
    suspend fun tokenBalance(chainId: Long, token: String, wallet: String): BigInteger? {
        val chain = EvmChains.byId(chainId) ?: return null
        return orNull { reader(chain).read1(token, Erc20.balanceOf, wallet) as? BigInteger }
    }

    // ── buy ─────────────────────────────────────────────────────────────────────────

    /** Starts a buy and returns its live state. Throws when it cannot start. */
    suspend fun buy(req: BuyRequest, wallet: EvmSigner, own: List<String>): StateFlow<Trade> {
        val chain = chainOf(req.chainId)
        if (req.amount.signum() <= 0) throw TradeException("insufficient_funds", "Amount too small.")
        val ctx = venueCtx(chain, req.poolHints)
        // The sheet quotes every few seconds; a fresh quote skips a round trip.
        val route = routing.cached(ctx, req.token, Side.BUY, req.amount, MAX_QUOTE_AGE_MS)
            ?: routing.best(ctx, req.token, Side.BUY, listOf(req.amount)).firstOrNull()
            ?: throw TradeException("no_route", routing.whyNone(ctx, req.token) ?: NO_ROUTE)
        val t = newTicket(Side.BUY, chain, wallet.address, req.token, req.symbol, route.pool.label, TradeStep(StepKind.SWAP, amountIn = req.amount))
        val job = BuyJob(chain, ctx, wallet, req.amount, route, own, req.prefs, maxValue = req.amount)
        scope.launch { buyOne(t, 0, job) }
        return t.flow
    }

    private class BuyJob(
        val chain: EvmChain,
        val ctx: VenueCtx,
        val wallet: EvmSigner,
        val amount: BigInteger,
        val route: Routed,
        val own: List<String>,
        val prefs: TradePrefs,
        val maxValue: BigInteger,
    )

    private suspend fun buyOne(t: Ticket, i: Int, p: BuyJob) {
        try {
            val out = p.route.out
            if (out.signum() == 0) return t.fail(i, "No quote")
            val funding = p.route.venue.funding(p.ctx, p.route.pool)
            if (funding != null) {
                val via = viaRouter(p.chain, p.ctx, funding) ?: return t.fail(i, UPDATE_APP)
                routerBuy(t, i, p, funding, via)
                return
            }
            val gate = balanceGate(p.chain, p.ctx, p.prefs, p.wallet.address, p.amount)
            val minOut = TradeMath.minOutAfter(out, 0, p.prefs.slippageBps)
            val deadline = big(now() / 1000 + TradeMath.DEADLINE_S)
            val tx = buildTx(p.route.venue, p.ctx, p.route.pool, Side.BUY, SellParams(p.wallet.address, p.amount, minOut, deadline))
            val vctx = vctx(p.chain, p.wallet.address, p.own, p.ctx, p.route, token = p.route.pool.token, maxValue = p.maxValue)
            val ok = execute(t, i, Exec(p.chain, p.prefs, p.wallet, tx, vctx, GasKind.SWAP, simulate = true, gate = gate))
            if (ok) ensureSellApproval(t, p.chain, p.ctx, p.prefs, p.wallet, p.route, extra = out, own = p.own, spender = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t.fail(i, shortErr(e))
        }
    }

    /**
     * A buy on a funded pool in ONE transaction through the Nesty Router: native → asset,
     * approve, curve buy. Sells of the token then go through the router too, so the approval
     * that follows is for Permit2.
     */
    private suspend fun routerBuy(t: Ticket, i: Int, p: BuyJob, funding: Funding, via: Via) {
        val gate = balanceGate(p.chain, p.ctx, p.prefs, p.wallet.address, p.amount)
        val assetOut = via.legVenue.quote(p.ctx, funding.leg, Side.BUY, listOf(p.amount)).firstOrNull()
        if (assetOut == null || assetOut.signum() == 0) return t.fail(i, "No ${funding.symbol} quote")
        val minOut = TradeMath.minOutAfter(p.route.out, 0, p.prefs.slippageBps)
        val r = fundedRoute(p.ctx, p.wallet.address, p.route, funding, via)
        val assetMin = TradeMath.minOutAfter(assetOut, 0, TradeMath.FUNDING_SLIPPAGE_BPS)
        val calls = NestyRouter.buyCalls(r, p.amount, assetMin, minOut)
        val tx = TxDraft(
            via.router,
            NestyRouter.encodeExecute(calls, null, p.route.pool.token, minOut, listOf(funding.asset, p.chain.wrappedNative), r.deadline),
            p.amount,
        )
        val vctx = vctx(p.chain, p.wallet.address, p.own, p.ctx, p.route, token = p.route.pool.token, fundingAsset = funding.asset, maxValue = p.maxValue)
        // Several venue calls in one: their gas varies too much for a static guess.
        val ok = execute(t, i, Exec(p.chain, p.prefs, p.wallet, tx, vctx, GasKind.ROUTED, simulate = true, gate = gate))
        if (ok) ensureSellApproval(t, p.chain, p.ctx, p.prefs, p.wallet, p.route, extra = p.route.out, own = p.own, spender = Permit2.ADDRESS)
    }

    /**
     * The balance check, fetched while the swap is simulated (one round trip less) and applied
     * to the gas limit that will be signed. Never fails: a failed read is its own reason.
     */
    private fun balanceGate(chain: EvmChain, ctx: VenueCtx, prefs: TradePrefs, wallet: String, amount: BigInteger): Deferred<(BigInteger) -> String?> =
        scope.async {
            try {
                val (balance, fees) = coroutineScope {
                    val b = async { ctx.reader.balance(wallet) }
                    val f = async { fees(chain, ctx.reader, prefs.gas) }
                    b.await() to f.await()
                }
                val check: (BigInteger) -> String? = { gasLimit ->
                    val need = amount + gasLimit * fees.maxFeePerGas
                    if (balance < need) {
                        "Insufficient balance: needs ${TradeMath.formatToken(need, 18)} ${chain.nativeSymbol} with gas, has ${TradeMath.formatToken(balance, 18)}"
                    } else {
                        null
                    }
                }
                check
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = "Balance check failed: ${shortErr(e)}"
                val check: (BigInteger) -> String? = { reason }
                check
            }
        }

    /**
     * After a buy: approves the sell spender for what the wallet will hold, so the sell is one
     * transaction. Not simulated: the buy is not mined yet, and an approval does not depend on it.
     */
    private suspend fun ensureSellApproval(
        t: Ticket,
        chain: EvmChain,
        ctx: VenueCtx,
        prefs: TradePrefs,
        wallet: EvmSigner,
        route: Routed,
        extra: BigInteger,
        own: List<String>,
        spender: String?,
    ) {
        val token = route.pool.token
        val sp = spender ?: route.venue.sellApproval(ctx, route.pool).spender
        // A failed read leaves the approval to the sell; the buy itself is unaffected.
        val (allowance, balance) = orNull {
            coroutineScope {
                val a = async { readBig(ctx.reader, token, Erc20.allowance, wallet.address, sp) }
                val b = async { readBig(ctx.reader, token, Erc20.balanceOf, wallet.address) }
                a.await() to b.await()
            }
        } ?: return
        val needed = balance + extra
        if (allowance >= needed) return
        val target = TradeMath.approvalTarget(prefs.unlimitedApprovals, prefs.approvalBufferBps, needed)
        val tx = TxDraft(token, Erc20.approve.encode(sp, target), BigInteger.ZERO)
        val idx = t.add(TradeStep(StepKind.APPROVE))
        val vctx = vctx(chain, wallet.address, own, ctx, route, token = token, maxApproval = target, maxValue = BigInteger.ZERO)
        execute(t, idx, Exec(chain, prefs, wallet, tx, vctx, GasKind.APPROVE, simulate = false))
    }

    // ── sell ────────────────────────────────────────────────────────────────────────

    /** Starts a sell of a share of the wallet's balance and returns its live state. */
    suspend fun sell(req: SellRequest, wallet: EvmSigner, own: List<String>): StateFlow<Trade> {
        val chain = chainOf(req.chainId)
        val ctx = venueCtx(chain, req.poolHints)
        val balance = readBig(ctx.reader, req.token, Erc20.balanceOf, wallet.address)
        val amount = balance * big(req.bps.coerceIn(0, 10_000)) / big(10_000)
        if (amount.signum() == 0) throw TradeException("insufficient_funds", "No tokens to sell.")
        val route = routing.cached(ctx, req.token, Side.SELL, amount, MAX_QUOTE_AGE_MS)
            ?: routing.best(ctx, req.token, Side.SELL, listOf(amount)).firstOrNull()
            ?: throw TradeException("no_route", routing.whyNone(ctx, req.token) ?: NO_ROUTE)
        val t = newTicket(Side.SELL, chain, wallet.address, req.token, req.symbol, route.pool.label, TradeStep(StepKind.SWAP, amountIn = amount))
        val job = SellJob(chain, ctx, wallet, amount, balance, route, quotedIn = amount, own = own, prefs = req.prefs)
        scope.launch { sellOne(t, 0, job) }
        return t.flow
    }

    private class SellJob(
        val chain: EvmChain,
        val ctx: VenueCtx,
        val wallet: EvmSigner,
        val amount: BigInteger,
        val balance: BigInteger,
        val route: Routed,
        val quotedIn: BigInteger,
        val own: List<String>,
        val prefs: TradePrefs,
    )

    private class SellPath(val funding: Funding?, val via: Via?, val approval: SellApproval)

    /**
     * Who a sell lets take the tokens: the venue's spender, or Permit2 when the sell goes
     * through the Nesty Router (the tokens reach it in a one-time Permit2 transfer).
     */
    private fun sellPath(chain: EvmChain, ctx: VenueCtx, route: Routed): SellPath {
        val funding = route.venue.funding(ctx, route.pool)
        val via = funding?.let { viaRouter(chain, ctx, it) }
        val approval = if (via != null) SellApproval(Permit2.ADDRESS, false, via.router) else route.venue.sellApproval(ctx, route.pool)
        return SellPath(funding, via, approval)
    }

    private suspend fun sellOne(t: Ticket, i: Int, p: SellJob) {
        val token = p.route.pool.token
        try {
            val path = sellPath(p.chain, p.ctx, p.route)
            // Such a sale pays out the asset, not native, and its second step is not built here.
            if (path.funding != null && path.via == null) return t.fail(i, UPDATE_APP)
            val (out, allowance) = coroutineScope {
                // A sell of exactly the amount just quoted keeps that quote.
                val o = async { if (p.amount == p.quotedIn) p.route.out else routing.requote(p.ctx, p.route.pool, Side.SELL, p.amount) }
                val a = async { readBig(p.ctx.reader, token, Erc20.allowance, p.wallet.address, path.approval.spender) }
                o.await() to a.await()
            }
            if (out == null || out.signum() == 0) return t.fail(i, "No quote")

            // 1) The ERC-20 approval, when the allowance does not cover this sell.
            var pendingApproval = false
            if (allowance < p.amount) {
                val target = TradeMath.approvalTarget(p.prefs.unlimitedApprovals, p.prefs.approvalBufferBps, p.balance)
                val tx = TxDraft(token, Erc20.approve.encode(path.approval.spender, target), BigInteger.ZERO)
                val idx = t.add(TradeStep(StepKind.APPROVE))
                val vctx = vctx(p.chain, p.wallet.address, p.own, p.ctx, p.route, token = token, maxApproval = target, maxValue = BigInteger.ZERO)
                if (!execute(t, idx, Exec(p.chain, p.prefs, p.wallet, tx, vctx, GasKind.APPROVE, simulate = true))) {
                    return t.fail(i, "Approval failed")
                }
                pendingApproval = true
            }
            if (path.funding != null && path.via != null) {
                routerSell(t, i, p, path.funding, path.via, out, pendingApproval)
                return
            }

            val nowS = now() / 1000
            val vctx = vctx(p.chain, p.wallet.address, p.own, p.ctx, p.route, token = token, maxPermit = p.amount, maxValue = BigInteger.ZERO)

            // 2) A Permit2 signature for exactly this amount, when the router's allowance is short.
            var permit: SignedPermit? = null
            if (path.approval.viaPermit2) {
                val p2 = readPermit2(p.ctx.reader, p.wallet.address, token, path.approval.router)
                if (p2.amount < p.amount || p2.expiration <= nowS + 60) {
                    val msg = Permit2.build(token, path.approval.router, p.amount, p2.nonce, nowS)
                    val check = Validator.validatePermit(msg, vctx)
                    if (!check.ok) return t.block(i, check.actions)
                    val signature = withContext(Dispatchers.Default) { Permit2.single(p.chain.id, msg).sign(p.wallet::signHash) }
                    permit = SignedPermit(msg, signature)
                }
            }

            // 3) The swap itself.
            val minOut = TradeMath.minOutAfter(out, 0, p.prefs.slippageBps)
            val deadline = big(nowS + TradeMath.DEADLINE_S)
            val tx = buildTx(p.route.venue, p.ctx, p.route.pool, Side.SELL, SellParams(p.wallet.address, p.amount, minOut, deadline, permit))
            // With an approval still pending, a simulation would fail for the wrong reason.
            execute(t, i, Exec(p.chain, p.prefs, p.wallet, tx, vctx, GasKind.SWAP, simulate = !pendingApproval))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t.fail(i, shortErr(e))
        }
    }

    /**
     * A sell on a funded pool in ONE transaction through the Nesty Router: a one-time Permit2
     * transfer of exactly `amount` to the router, the curve sell, then asset → native to the
     * wallet. The token's Permit2 approval was handled by sellOne.
     */
    private suspend fun routerSell(t: Ticket, i: Int, p: SellJob, funding: Funding, via: Via, out: BigInteger, pendingApproval: Boolean) {
        val token = p.route.pool.token
        val assetOut = funding.quoteAsset(p.ctx, Side.SELL, listOf(p.amount)).firstOrNull()
        if (assetOut == null || assetOut.signum() == 0) return t.fail(i, "No quote")
        val vctx = vctx(p.chain, p.wallet.address, p.own, p.ctx, p.route, token = token, fundingAsset = funding.asset, maxPermit = p.amount, maxValue = BigInteger.ZERO)
        val permit = Permit2.buildTransfer(token, p.amount, now() / 1000)
        val check = NestyRouter.checkTransferPermit(permit, via.router, vctx)
        if (!check.ok) return t.block(i, listOf(check))
        val signature = withContext(Dispatchers.Default) { Permit2.transfer(p.chain.id, permit, via.router).sign(p.wallet::signHash) }
        val minOut = TradeMath.minOutAfter(out, 0, p.prefs.slippageBps)
        val r = fundedRoute(p.ctx, p.wallet.address, p.route, funding, via)
        val assetMin = TradeMath.minOutAfter(assetOut, 0, p.prefs.slippageBps)
        val calls = NestyRouter.sellCalls(r, assetMin, minOut) ?: return t.fail(i, "No way back from ${funding.symbol}")
        val tx = TxDraft(
            via.router,
            NestyRouter.encodeExecute(
                calls,
                NestyRouter.SignedTransfer(permit, signature),
                NestyRouter.NATIVE,
                minOut,
                listOf(token, funding.asset, p.chain.wrappedNative),
                r.deadline,
            ),
            BigInteger.ZERO,
        )
        // With the Permit2 approval still pending the router's static limit applies.
        execute(t, i, Exec(p.chain, p.prefs, p.wallet, tx, vctx, GasKind.ROUTED, simulate = !pendingApproval))
    }

    // ── the Nesty Router ────────────────────────────────────────────────────────────

    private class Via(val router: String, val legVenue: Venue)

    /** The Nesty Router for a funded pool, when the chain has one and it can trade the leg. */
    private fun viaRouter(chain: EvmChain, ctx: VenueCtx, funding: Funding): Via? {
        val router = chain.nestyRouter ?: return null
        val legVenue = routing.venue(funding.leg.venueId) ?: return null
        if (!NestyRouter.legSupported(ctx, legVenue, funding.leg)) return null
        return Via(router, legVenue)
    }

    private fun fundedRoute(ctx: VenueCtx, wallet: String, route: Routed, funding: Funding, via: Via) = FundedRoute(
        ctx = ctx,
        router = via.router,
        wallet = wallet,
        venue = route.venue,
        pool = route.pool,
        legVenue = via.legVenue,
        leg = funding.leg,
        asset = funding.asset,
        deadline = big(now() / 1000 + TradeMath.DEADLINE_S),
    )

    // ── one transaction ─────────────────────────────────────────────────────────────

    private class Exec(
        val chain: EvmChain,
        val prefs: TradePrefs,
        val wallet: EvmSigner,
        val tx: TxDraft,
        val vctx: ValidationContext,
        val kind: GasKind,
        val simulate: Boolean,
        /** Fetched alongside the simulation, then applied to the gas limit that is signed. */
        val gate: Deferred<(BigInteger) -> String?>? = null,
        /** Where its reads go; the chain's usual reader when null. */
        val reader: ChainReader? = null,
    )

    private class Sent(val from: String, val nonce: Long)

    /** What one transaction came to before it was watched. */
    private sealed interface Submit {
        class Blocked(val actions: List<BlockedAction>) : Submit
        class Skipped(val reason: String) : Submit
        class Failed(val error: String) : Submit
        class Out(val hash: String, val nonce: Long) : Submit
    }

    private fun vctx(
        chain: EvmChain,
        from: String,
        own: List<String>,
        ctx: VenueCtx,
        route: Routed,
        token: String? = null,
        fundingAsset: String? = null,
        maxApproval: BigInteger? = null,
        maxPermit: BigInteger? = null,
        maxValue: BigInteger,
    ) = validationContext(chain, from, own, now() / 1000, route.venue, ctx, route.pool, token, fundingAsset, maxApproval, maxPermit, maxValue)

    /** Validate → simulate → sign → send, for a trade's step. False when the step did not go out. */
    private suspend fun execute(t: Ticket, i: Int, p: Exec): Boolean =
        when (val s = submit(p, onSending = { t.set(i) { it.copy(status = StepStatus.SENDING) } })) {
            is Submit.Blocked -> {
                t.block(i, s.actions)
                false
            }
            is Submit.Skipped -> {
                t.set(i) { it.copy(status = StepStatus.SKIPPED, error = s.reason) }
                false
            }
            is Submit.Failed -> {
                t.fail(i, s.error)
                false
            }
            is Submit.Out -> {
                t.sent[i] = Sent(p.wallet.address, s.nonce)
                t.set(i) { it.copy(status = StepStatus.SUBMITTED, hash = s.hash, nonce = s.nonce) }
                scope.launch { watch(t, i, p.chain) }
                true
            }
        }

    /** Validate → simulate → sign → send: nothing is signed unless the validator passed it. */
    private suspend fun submit(p: Exec, onSending: () -> Unit, onSigned: (String) -> Unit = {}): Submit {
        val verdict = Validator.validateTx(p.tx, p.vctx)
        if (!verdict.ok) return Submit.Blocked(verdict.actions)
        val reader = p.reader ?: reader(p.chain)
        var gas = TradeMath.staticGas(p.chain, p.kind)
        // The gate and the simulation are independent round trips: run them together.
        val (check, sim) = coroutineScope {
            val sim = if (p.simulate) async { estimate(reader, p) } else null
            val check = p.gate?.await()
            check to sim?.await()
        }
        sim?.getOrNull()?.let { gas = it * big(13) / BigInteger.TEN }
        // Checked against the gas limit actually signed. When the simulation failed, only the
        // value itself is checked, so an unfunded wallet still gets the clearer reason.
        var refusal = check?.invoke(if (sim?.isFailure == true) BigInteger.ZERO else gas)
        if (refusal != null && check != null && sim == null) {
            // Not simulated, so `gas` is the static worst case: measure before refusing.
            val est = estimate(reader, p).getOrNull()
            if (est != null) {
                gas = est * big(13) / BigInteger.TEN
                refusal = check(gas)
            }
        }
        if (refusal != null) return Submit.Skipped(refusal)
        if (sim != null && sim.isFailure) return Submit.Failed("Simulation failed: ${shortErr(sim.exceptionOrNull()!!)}")
        try {
            val fees = fees(p.chain, reader, p.prefs.gas)
            if (sim == null && p.chain.feeModel == FeeModel.ARBITRUM) {
                // The node refuses a transaction whose limit × max fee is above the balance:
                // cap the static limit at what the wallet covers; real use is far less.
                val balance = reader.balance(p.wallet.address)
                val affordable = if (balance > p.tx.value) (balance - p.tx.value) / fees.maxFeePerGas else BigInteger.ZERO
                if (affordable < TradeMath.MIN_UNSIMULATED_GAS) {
                    return Submit.Skipped("Insufficient balance for gas: has ${TradeMath.formatToken(balance, 18)} ${p.chain.nativeSymbol}")
                }
                if (affordable < gas) gas = affordable
            }
            // Gas is not covered by the amount: a fee spike must not cost more than the limit.
            val base = orNull { baseFee(p.chain, reader) }
            val feeRefusal = TradeMath.gasFeeRefusal(
                TradeMath.expectedFeeWei(p.chain, fees, base, gas, sim != null, p.kind),
                orNull { nativeUsd(p.chain) },
                p.prefs.maxGasUsd,
                p.chain.nativeSymbol,
            )
            if (feeRefusal != null) return Submit.Skipped(feeRefusal)
            onSending()
            val signedGas = gas
            val (nonce, hash) = nonces.signAndSend(
                p.chain,
                p.wallet.address,
                sign = { n ->
                    withContext(Dispatchers.Default) {
                        Tx1559(p.chain.id, n, fees.maxPriorityFeePerGas, fees.maxFeePerGas, signedGas, p.tx.to, p.tx.value, p.tx.data)
                            .sign(p.wallet::signHash)
                    }
                },
                send = { raw ->
                    // The hash of the bytes signed, whatever an endpoint answers: known before they go out.
                    val hash = keccak256(raw).toHex()
                    onSigned(hash)
                    sender.send(p.chain, raw)
                    hash
                },
            )
            return Submit.Out(hash, nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Submit.Failed(shortErr(e))
        }
    }

    // ── plain transactions: a bridge deposit, its approval, a send ───────────────────────

    /** What a plain transaction came to: sent with its hash, or refused (nothing was signed). */
    sealed interface Plain {
        data class Out(val hash: String, val nonce: Long) : Plain
        data class Refused(val reason: String, val blocked: List<BlockedAction>? = null) : Plain
    }

    /**
     * One plain transaction from `wallet`, on the same path as a trade's: the validator, the
     * simulation when asked, the fee limit, the shared nonces, the chain's own RPCs for the send.
     * `reader` sends its reads elsewhere (a send to an outside address reads the chain directly).
     */
    suspend fun sendPlain(
        chainId: Long,
        wallet: EvmSigner,
        tx: TxDraft,
        vctx: ValidationContext,
        kind: GasKind,
        prefs: TradePrefs,
        simulate: Boolean = true,
        gate: ((BigInteger) -> String?)? = null,
        reader: ChainReader? = null,
        /** The hash, right before the signed bytes are sent: a caller keeps it in case the app closes. */
        onSigned: (String) -> Unit = {},
    ): Plain {
        val chain = chainOf(chainId)
        val p = Exec(chain, prefs, wallet, tx, vctx, kind, simulate, gate?.let { CompletableDeferred(it) }, reader)
        return when (val s = submit(p, {}, onSigned)) {
            is Submit.Blocked -> Plain.Refused(s.actions.firstOrNull { !it.ok }?.reason ?: "Blocked", s.actions)
            is Submit.Skipped -> Plain.Refused(s.reason)
            is Submit.Failed -> Plain.Refused(s.error)
            is Submit.Out -> Plain.Out(s.hash, s.nonce)
        }
    }

    /**
     * The receipt of a plain transaction, waited for as a trade's is: every poll for five minutes,
     * then the nonce is read again (it may have been dropped) and it is watched slowly for an hour.
     * Null when it never came.
     */
    suspend fun receiptOf(chainId: Long, hash: String, from: String, reader: ChainReader? = null): Receipt? {
        val chain = chainOf(chainId)
        val r = reader ?: reader(chain)
        val started = now()
        var late = false
        while (now() - started < WATCH_LIMIT_MS) {
            delay(if (late) LATE_POLL_MS else pollMs)
            orNull { r.receipt(hash) }?.let { return it }
            if (!late && now() - started >= RECEIPT_TIMEOUT_MS) {
                late = true
                nonces.reset(chain, from)
            }
        }
        return null
    }

    /** What the chain's own coin costs in fees for gas now: the base fee and tip of `preset`. */
    suspend fun gasPrice(chainId: Long, preset: GasPreset): Fees? {
        val chain = EvmChains.byId(chainId) ?: return null
        return orNull { fees(chain, reader(chain), preset) }
    }

    private suspend fun estimate(reader: ChainReader, p: Exec): Result<BigInteger> = try {
        Result.success(reader.estimateGas(p.wallet.address, p.tx.to, p.tx.data, p.tx.value))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Waits for the receipt. After five minutes the step says it is not confirmed yet and the
     * nonce is read from the chain again (it may have been dropped); it is still watched, more
     * slowly, for an hour.
     */
    private suspend fun watch(t: Ticket, i: Int, chain: EvmChain) {
        val reader = reader(chain)
        val started = now()
        var gapCheckedAt = started
        var late = false
        while (now() - started < WATCH_LIMIT_MS) {
            delay(if (late) LATE_POLL_MS else pollMs)
            val step = t.step(i)
            val hash = step.hash ?: return
            if (step.status != StepStatus.SUBMITTED) return
            val receipt = orNull { reader.receipt(hash) }
            if (receipt != null) {
                val sent = t.sent[i]
                val out = if (receipt.success && sent != null && step.kind == StepKind.SWAP) orNull { received(t, sent.from, receipt, reader, chain) } else null
                t.set(i) {
                    it.copy(
                        status = if (receipt.success) StepStatus.CONFIRMED else StepStatus.FAILED,
                        error = if (receipt.success) null else "Reverted on-chain",
                        feeWei = receipt.feeWei,
                        amountOut = out ?: it.amountOut,
                        waitingNonce = null,
                    )
                }
                if (receipt.success && step.kind == StepKind.SWAP) orNull { onLanded(t.snapshot()) }
                return
            }
            if (!late && now() - started >= RECEIPT_TIMEOUT_MS) {
                late = true
                t.set(i) { it.copy(error = "Not confirmed yet.") }
                t.sent[i]?.let { nonces.reset(chain, it.from) }
            }
            if (now() - gapCheckedAt >= GAP_CHECK_MS) {
                gapCheckedAt = now()
                checkGap(t, i, reader)
            }
        }
    }

    /**
     * Is this transaction queued behind a nonce nobody sent? That happens when an earlier send
     * of a quick burst was refused while later ones went out: a mined count below our nonce,
     * with nothing pending at it.
     */
    private suspend fun checkGap(t: Ticket, i: Int, reader: ChainReader) {
        val sent = t.sent[i] ?: return
        val (mined, pending) = orNull {
            coroutineScope {
                val m = async { reader.nonce(sent.from, "latest") }
                val q = async { reader.nonce(sent.from, "pending") }
                m.await() to q.await()
            }
        } ?: return
        val gap = if (mined < sent.nonce && pending <= mined) mined else null
        if (gap == t.step(i).waitingNonce) return
        t.set(i) { it.copy(waitingNonce = gap) }
    }

    /** What a confirmed swap delivered to the wallet. Best effort. */
    private suspend fun received(t: Ticket, wallet: String, r: Receipt, reader: ChainReader, chain: EvmChain): BigInteger? {
        if (t.side == Side.BUY) return r.received(t.token, wallet)
        val unwrapped = r.unwrapped(chain.wrappedNative)
        if (unwrapped.signum() > 0) return unwrapped
        // Native pools (Uniswap v4) pay out without an unwrap: the balance change, fee added back.
        val block = r.blockNumber ?: return null
        val (before, after) = coroutineScope {
            val b = async { reader.balance(wallet, block - BigInteger.ONE) }
            val a = async { reader.balance(wallet, block) }
            b.await() to a.await()
        }
        val out = after - before + r.feeWei
        return if (out.signum() > 0) out else null
    }

    // ── fees ────────────────────────────────────────────────────────────────────────

    /** The latest base fee per chain, kept 3 s so a tap does not wait for a block. */
    private suspend fun baseFee(chain: EvmChain, reader: ChainReader): BigInteger {
        baseFees[chain.id]?.let { (at, fee) -> if (now() - at < BLOCK_TTL_MS) return fee }
        val fee = reader.baseFee()
        baseFees[chain.id] = now() to fee
        return fee
    }

    private suspend fun fees(chain: EvmChain, reader: ChainReader, preset: GasPreset): Fees =
        TradeMath.computeFees(chain, baseFee(chain, reader), preset)

    // ── tickets ─────────────────────────────────────────────────────────────────────

    private fun newTicket(side: Side, chain: EvmChain, wallet: String, token: String, symbol: String?, venue: String, first: TradeStep): Ticket {
        val t = Ticket("t${now()}-${counter.incrementAndGet()}", side, chain.id, wallet, token, symbol, venue, now(), first)
        recentTrades.update { (listOf(t.snapshot()) + it).take(KEEP_TRADES) }
        return t
    }

    private inner class Ticket(
        val id: String,
        val side: Side,
        val chainId: Long,
        val wallet: String,
        val token: String,
        val symbol: String?,
        val venue: String,
        val startedAt: Long,
        first: TradeStep,
    ) {
        private val lock = Any()
        private val steps = mutableListOf(first)
        private var blocked: List<BlockedAction>? = null
        private var status = TradeStatus.WORKING
        val sent = ConcurrentHashMap<Int, Sent>()
        val flow = MutableStateFlow(snapshot())

        fun step(i: Int): TradeStep = synchronized(lock) { steps[i] }

        fun add(step: TradeStep): Int {
            val i = synchronized(lock) {
                steps += step
                steps.size - 1
            }
            publish()
            return i
        }

        fun set(i: Int, f: (TradeStep) -> TradeStep) {
            synchronized(lock) { steps[i] = f(steps[i]) }
            publish()
        }

        fun fail(i: Int, error: String) = set(i) { it.copy(status = StepStatus.FAILED, error = error) }

        fun block(i: Int, actions: List<BlockedAction>) {
            synchronized(lock) {
                blocked = actions
                steps[i] = steps[i].copy(status = StepStatus.BLOCKED, error = actions.firstOrNull { !it.ok }?.reason ?: "Blocked")
            }
            publish()
        }

        fun snapshot(): Trade = synchronized(lock) {
            Trade(id, side, chainId, wallet, token, symbol, venue, steps.toList(), status, blocked, startedAt)
        }

        /** The trade's state from its swaps, as core.ts update() reads it; approvals do not count. */
        private fun publish() {
            val snap = synchronized(lock) {
                val swaps = steps.filter { it.kind != StepKind.APPROVE }
                val done = swaps.count { it.status == StepStatus.CONFIRMED }
                val bad = swaps.count { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED || it.status == StepStatus.SKIPPED }
                if (swaps.size - done - bad == 0) {
                    status = when {
                        done == swaps.size -> TradeStatus.DONE
                        blocked != null && done == 0 -> TradeStatus.BLOCKED
                        else -> TradeStatus.FAILED
                    }
                }
                Trade(id, side, chainId, wallet, token, symbol, venue, steps.toList(), status, blocked, startedAt)
            }
            flow.value = snap
            recentTrades.update { list -> list.map { if (it.id == snap.id) snap else it } }
        }
    }

    companion object {
        /** A quote this fresh is used as it is (the extension's maxQuoteAgeMs). */
        const val MAX_QUOTE_AGE_MS = 6_000L
        const val RECEIPT_POLL_MS = 1_500L
        const val RECEIPT_TIMEOUT_MS = 5 * 60_000L
        private const val LATE_POLL_MS = 10_000L
        private const val WATCH_LIMIT_MS = 60 * 60_000L
        private const val GAP_CHECK_MS = 4_000L
        private const val BLOCK_TTL_MS = 3_000L
        private const val KEEP_TRADES = 20

        const val NO_ROUTE = "No pool can trade this coin right now."
        const val UPDATE_APP = "Update the app to trade here"
    }
}

/**
 * The validator's context for one transaction, as core.ts builds it: the chain's router rules,
 * the pool's own (a launchpad's contracts), and the Nesty Router's rule.
 */
fun validationContext(
    chain: EvmChain,
    from: String,
    own: List<String>,
    nowS: Long,
    venue: Venue?,
    ctx: VenueCtx?,
    pool: PoolRef?,
    token: String? = null,
    fundingAsset: String? = null,
    maxApproval: BigInteger? = null,
    maxPermit: BigInteger? = null,
    maxValue: BigInteger,
): ValidationContext {
    val rules = LinkedHashMap(RouterVenues.rules(chain.id))
    if (venue != null && ctx != null && pool != null) {
        for ((address, rule) in venue.poolRules(ctx, pool)) rules[address.lowercase()] = rule
    }
    // Its rule only passes funded curve trades (it needs `fundingAsset`).
    chain.nestyRouter?.let { rules[it.lowercase()] = NestyRouter.rule(it) }
    return ValidationContext(
        chain = chain,
        from = from,
        ownWallets = own,
        token = token,
        fundingAsset = fundingAsset,
        router = chain.uniswap.universalRouter,
        extraContracts = rules,
        maxApproval = maxApproval,
        maxPermit = maxPermit,
        maxValue = maxValue,
        nowS = nowS,
    )
}

/** A read that must answer: a failed call is an error, not a zero. */
suspend fun readBig(reader: ChainReader, to: String, fn: AbiFunction, vararg args: Any?): BigInteger =
    reader.read1(to, fn, *args) as? BigInteger ?: throw RpcError(-32000, "Could not read ${fn.name}")

class Permit2State(val amount: BigInteger, val expiration: Long, val nonce: Long)

suspend fun readPermit2(reader: ChainReader, owner: String, token: String, spender: String): Permit2State {
    val r = reader.read(Permit2.ADDRESS, Permit2.allowance, owner, token, spender) ?: throw RpcError(-32000, "Could not read the Permit2 allowance")
    return Permit2State(r[0] as BigInteger, (r[1] as BigInteger).toLong(), (r[2] as BigInteger).toLong())
}

/**
 * An error as a trade step shows it, ported from core.ts shortErr: one line, no addresses,
 * long hex cut to its ends.
 */
fun shortErr(e: Throwable): String {
    val msg = e.message ?: e.javaClass.simpleName
    if (Regex("insufficient funds", RegexOption.IGNORE_CASE).containsMatchIn(msg)) return "Insufficient balance for gas"
    val line = msg
        .replace(Regex("0x[0-9a-fA-F]{40}(?![0-9a-fA-F])"), "0x…")
        .replace(Regex("0x([0-9a-fA-F]{6})[0-9a-fA-F]{12,}([0-9a-fA-F]{6})"), "0x$1…$2")
        .split('\n').first()
    return sanitizeLabel(line, 160).ifEmpty { "error" }
}

private val UNSAFE_RANGES = listOf(
    0x0000..0x001f,
    0x007f..0x009f,
    0x200b..0x200f,
    0x202a..0x202e,
    0x2060..0x2069,
    0xfeff..0xfeff,
)

/** JavaScript's \s: what a run of spaces is made of. */
private fun isJsSpace(c: Char): Boolean =
    c == ' ' || c in '\t'..'\r' || c == ' ' || c == ' ' || c in ' '..' ' ||
        c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

/** Text from outside (a node, a token) without characters that hide or reorder text, capped. */
fun sanitizeLabel(value: String, max: Int = 32): String {
    val kept = StringBuilder()
    var gap = false
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        i += Character.charCount(cp)
        if (UNSAFE_RANGES.any { cp in it }) continue
        // A run of spaces becomes one, none at either end: the extension's .replace(/\s+/g, ' ')
        // and trim(). By hand, since Android's regex engine refuses the (?U) a JVM regex needs.
        if (cp < 0x10000 && isJsSpace(cp.toChar())) {
            gap = true
            continue
        }
        if (gap && kept.isNotEmpty()) kept.append(' ')
        gap = false
        kept.appendCodePoint(cp)
    }
    val clean = kept.toString()
    val count = clean.codePointCount(0, clean.length)
    return if (count <= max) clean else clean.substring(0, clean.offsetByCodePoints(0, max))
}
