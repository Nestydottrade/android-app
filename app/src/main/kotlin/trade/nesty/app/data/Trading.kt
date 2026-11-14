package trade.nesty.app.data

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import trade.nesty.app.evm.Broadcast
import trade.nesty.app.evm.BuyRequest
import trade.nesty.app.evm.EvmChain
import trade.nesty.app.evm.EvmChains
import trade.nesty.app.evm.EvmSigner
import trade.nesty.app.evm.EvmTrader
import trade.nesty.app.evm.GasKind
import trade.nesty.app.evm.GasPreset
import trade.nesty.app.evm.HttpRpc
import trade.nesty.app.evm.Quote
import trade.nesty.app.evm.SellRequest
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.StepKind
import trade.nesty.app.evm.StepStatus
import trade.nesty.app.evm.TradeException
import trade.nesty.app.evm.TradePrefs
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.orNull
import trade.nesty.app.evm.shortErr
import trade.nesty.app.sol.HttpSolCall
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolBroadcast
import trade.nesty.app.sol.SolBuyRequest
import trade.nesty.app.sol.SolPrefs
import trade.nesty.app.sol.SolRpc
import trade.nesty.app.sol.SolSellRequest
import trade.nesty.app.sol.SolSigner
import trade.nesty.app.sol.SolTrader
import trade.nesty.app.ui.Fmt
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.concurrent.ConcurrentHashMap
import trade.nesty.app.evm.Trade as EngineTrade

/** Why a trade did not start. Nothing was signed. */
class TradeRefused(message: String) : Exception(message)

/** What a finished trade says in the note at the bottom of the screen. */
data class TradeNote(val id: String, val title: String, val detail: String, val chain: Chain, val token: String, val ok: Boolean)

/** What a screen knows of a coin when it trades it. */
data class CoinInfo(
    val symbol: String? = null,
    val decimals: Int? = null,
    /** Whole tokens. */
    val supply: Double? = null,
    /** Pools the server named for this coin (v4 pool ids, or Solana pool accounts); the engine checks each on chain. */
    val hints: List<String> = emptyList(),
) {
    val label: String get() = symbol?.takeIf { it.isNotBlank() } ?: "this coin"

    companion object {
        private val POOL_ID = Regex("^0x[0-9a-fA-F]{64}$")

        fun of(coin: Coin?): CoinInfo = if (coin == null) {
            CoinInfo()
        } else {
            val named = listOfNotNull(coin.pool?.address, coin.chart?.pool)
            CoinInfo(
                symbol = coin.symbol,
                decimals = coin.decimals,
                supply = coin.supply,
                // Solana addresses are case-sensitive; v4 pool ids are not.
                hints = if (coin.feed == Chain.SOLANA.feed) {
                    named.filter { SolAddr.isAddress(it) }.distinct()
                } else {
                    named.filter { POOL_ID.matches(it) }.map { it.lowercase() }.distinct()
                },
            )
        }
    }
}

/**
 * Trading from the wallet that trades: on the EVM chains the Kotlin engine (EvmTrader), on
 * Solana its own (SolTrader). Reads go through Nesty's /rpc, sends straight to each chain's own
 * RPCs, then the fill of each landed trade (/fill) is kept as the position's entry on this phone.
 *
 * A buy is paid from the chain's own balance; when that is short, the Mover moves funds there first
 * (on the user's one confirmation) and its cost joins the buy's.
 */
class Trading(
    private val api: NestyApi,
    private val session: WalletSession,
    private val store: PositionStore,
    http: OkHttpClient,
) {
    lateinit var balances: BalanceModel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val notesFlow = MutableSharedFlow<TradeNote>(extraBufferCapacity = 16)

    /** A note for each trade that finished, landed or not. */
    val notes: SharedFlow<TradeNote> = notesFlow.asSharedFlow()

    /** Trades whose screen shows how they ended itself: no note at the bottom for them. */
    val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Prices of the coins of positions, by Balances.tokenKey. */
    val prices = mutableStateMapOf<String, Double>()

    /** Their market caps now, by the same key. */
    val mcaps = mutableStateMapOf<String, Double>()

    val engine = EvmTrader(
        rpc = HttpRpc(http, api.base.toString()),
        sender = Broadcast(http),
        nativeUsd = { chain -> Chain.of(chain.id.toString())?.let { priceOf(it) } },
        onLanded = { t -> landed(t) },
    )

    val sol = SolTrader(
        rpc = SolRpc(HttpSolCall(http, api.base.toString()), SolBroadcast(http)),
        knownSolUsd = { priceOf(Chain.SOLANA) },
        onLanded = { t -> landed(t) },
    )

    /** `extraFeeUsd`: what moving funds here for this buy cost, kept in its cost basis. */
    private class Pending(val chain: Chain, val info: CoinInfo, val title: String, val price: Double?, val extraFeeUsd: Double = 0.0)

    fun evmOf(chain: Chain): EvmChain? = if (chain.isEvm) chain.feed.toLongOrNull()?.let { EvmChains.byId(it) } else null

    /** Whether this build can trade on `chain`. */
    fun canTrade(chain: Chain): Boolean = chain == Chain.SOLANA || evmOf(chain) != null

    /** The wallet that trades' address on `chain`: its EVM address, or its Solana one. */
    fun addressOn(chain: Chain): String? = session.active?.let { if (chain.isEvm) it.evm else it.sol }

    /** Why a trade on `chain` cannot start now, before anything is read; null when it can. */
    fun cannotTrade(chain: Chain): String? {
        if (!canTrade(chain)) return "This version of Nesty cannot trade on ${chain.title} yet."
        val wallet = session.active ?: return "Add a wallet first."
        if (chain == Chain.SOLANA) {
            if (wallet.sol == null) return NO_SOL
            if (session.solSigner() == null) return "Unlock your wallets first."
            return null
        }
        if (wallet.evm == null) return NO_EVM
        if (session.evmSigner() == null) return "Unlock your wallets first."
        return null
    }

    /** Sends go to a private relay on this chain, out of the public mempool. */
    fun mevProtect(chain: Chain): Boolean = evmOf(chain)?.mevRpc != null

    /** The chain's own coin from its wrapped coin's market, until a balance read prices it. */
    private val wrappedPrices = mutableStateMapOf<Chain, Double>()

    /** A whole coin of the chain in dollars: from the last balance read, else its wrapped coin. */
    fun priceOf(chain: Chain): Double? = balances.active?.prices?.get(chain) ?: wrappedPrices[chain]

    /** Prices the chain's own coin before any balance is read: SOL from chain, the others by their wrapped coin. */
    suspend fun readNativePrice(chain: Chain) {
        if (priceOf(chain) != null) return
        if (chain == Chain.SOLANA) {
            orNull { sol.price.usd() }?.takeIf { it > 0 }?.let { wrappedPrices[chain] = it }
            return
        }
        val evm = evmOf(chain) ?: return
        val row = try {
            api.prices(listOf(chain to evm.wrappedNative)).prices.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        row?.priceUsd?.takeIf { it > 0 }?.let { wrappedPrices[chain] = it }
    }

    private fun signer(): EvmSigner {
        val wallet = session.active ?: throw TradeRefused("Add a wallet first.")
        if (wallet.evm == null) throw TradeRefused(NO_EVM)
        return session.evmSigner() ?: throw TradeRefused("Unlock your wallets first.")
    }

    private fun solSigner(): SolSigner {
        val wallet = session.active ?: throw TradeRefused("Add a wallet first.")
        if (wallet.sol == null) throw TradeRefused(NO_SOL)
        return session.solSigner() ?: throw TradeRefused("Unlock your wallets first.")
    }

    private fun evm(chain: Chain): EvmChain = evmOf(chain) ?: throw TradeRefused(LOCKED)

    fun weiOf(usd: Double, chain: Chain, price: Double): BigInteger =
        BigDecimal(usd).divide(BigDecimal(price), MathContext.DECIMAL128).movePointRight(chain.decimals).toBigInteger()

    fun usdOf(raw: BigInteger, decimals: Int, price: Double): Double = BigDecimal(raw).movePointLeft(decimals).toDouble() * price

    /**
     * Buys `usd` of a coin from the chain's own balance. Throws TradeRefused when it cannot start.
     * `extraFeeUsd`: what moving funds to the chain for it cost, added to its network fee.
     */
    suspend fun buy(chain: Chain, token: String, usd: Double, preset: Preset, info: CoinInfo, extraFeeUsd: Double = 0.0): StateFlow<EngineTrade> {
        val send = if (chain == Chain.SOLANA) solBuyer(token, preset, info) else evmBuyer(chain, token, preset, info)
        if (usd <= 0 || usd.isNaN()) throw TradeRefused("Enter an amount.")
        val price = priceOf(chain) ?: throw TradeRefused("The ${chain.native} price is not read yet. Try again in a moment.")
        val wei = weiOf(usd, chain, price)
        val balance = balances.active
        val have = balance?.raw?.get(chain)
        if (have != null && have < wei) {
            throw TradeRefused("More than your ${chain.title} balance of ${Fmt.usdFull(balance.chains[chain] ?: 0.0)}.")
        }
        val flow = start { send(wei) }
        follow(flow, Pending(chain, info, "Bought ${Fmt.usdFull(usd)} of ${info.label}", price, extraFeeUsd.coerceAtLeast(0.0)))
        return flow
    }

    /** A Solana buy of so many lamports, from the wallet that trades. */
    private fun solBuyer(token: String, preset: Preset, info: CoinInfo): suspend (BigInteger) -> StateFlow<EngineTrade> {
        val signer = solSigner()
        return { lamports -> sol.buy(SolBuyRequest(token, lamports, solPrefsOf(preset), info.symbol, info.hints), signer) }
    }

    /** An EVM buy of so much wei, from the wallet that trades. */
    private fun evmBuyer(chain: Chain, token: String, preset: Preset, info: CoinInfo): suspend (BigInteger) -> StateFlow<EngineTrade> {
        val evm = evm(chain)
        val signer = signer()
        return { wei -> engine.buy(BuyRequest(evm.id, token.lowercase(), wei, prefsOf(evm, preset), info.symbol, info.hints), signer, session.ownEvm()) }
    }

    /** Sells `bps` of the wallet's balance of a coin (10 000: all). The proceeds stay on its chain. */
    suspend fun sell(chain: Chain, token: String, bps: Int, preset: Preset, info: CoinInfo, title: String): StateFlow<EngineTrade> {
        val share = bps.coerceIn(1, 10_000)
        val flow = if (chain == Chain.SOLANA) {
            val signer = solSigner()
            start { sol.sell(SolSellRequest(token, share, solPrefsOf(preset), info.symbol, info.hints), signer) }
        } else {
            val evm = evm(chain)
            val signer = signer()
            start { engine.sell(SellRequest(evm.id, token.lowercase(), share, prefsOf(evm, preset), info.symbol, info.hints), signer, session.ownEvm()) }
        }
        follow(flow, Pending(chain, info, title, priceOf(chain)))
        return flow
    }

    private suspend fun start(block: suspend () -> StateFlow<EngineTrade>): StateFlow<EngineTrade> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TradeException) {
        throw TradeRefused(e.message ?: "Could not start.")
    } catch (e: TradeRefused) {
        throw e
    } catch (e: Exception) {
        throw TradeRefused("Could not read the chain: ${shortErr(e)}")
    }

    /** A buy of `native` (the chain's own coin, in its smallest unit): what it gets now, or null. */
    suspend fun quoteBuy(chain: Chain, token: String, native: BigInteger, info: CoinInfo): Quote? =
        if (chain == Chain.SOLANA) {
            sol.quotes(token, Side.BUY, listOf(native), info.hints).firstOrNull()
        } else {
            evmOf(chain)?.let { engine.quotes(it.id, token, Side.BUY, listOf(native), info.hints).firstOrNull() }
        }

    /** Why no pool trades `token` right now, when a venue knows. */
    suspend fun whyNone(chain: Chain, token: String, info: CoinInfo): String? =
        if (chain == Chain.SOLANA) sol.whyNone(token, info.hints) else evmOf(chain)?.let { engine.whyNone(it.id, token, info.hints) }

    /**
     * The network fee of a buy now, in dollars: the swap, and on the EVM chains the approval for
     * its sale on a first buy; on Solana each transaction's signature and priority fee.
     */
    suspend fun buyFeeUsd(chain: Chain, token: String, info: CoinInfo, preset: Preset, firstBuy: Boolean): Double? {
        val price = priceOf(chain) ?: return null
        if (chain == Chain.SOLANA) {
            val lamports = sol.buyFeeLamports(token, solPrefsOf(preset), info.hints) ?: return null
            return usdOf(lamports, chain.decimals, price)
        }
        val evm = evmOf(chain) ?: return null
        val kinds = if (firstBuy) listOf(GasKind.SWAP, GasKind.APPROVE) else listOf(GasKind.SWAP)
        val wei = engine.networkFeeWei(evm.id, kinds, gasOf(evm, preset.speed)) ?: return null
        return usdOf(wei, 18, price)
    }

    /** What a buy keeps back on top of its amount and fee: on Solana the rent of the accounts it opens. */
    fun reserveUsd(chain: Chain): Double =
        if (chain == Chain.SOLANA) priceOf(chain)?.let { usdOf(SOL_RESERVE, chain.decimals, it) } ?: 0.0 else 0.0

    // ── after a trade ───────────────────────────────────────────────────────────────

    private fun follow(flow: StateFlow<EngineTrade>, p: Pending) {
        pending[flow.value.id] = p
        scope.launch {
            val t = flow.first { it.status != TradeStatus.WORKING }
            notesFlow.emit(noteOf(t, p))
            if (t.status != TradeStatus.DONE) pending.remove(t.id)
        }
    }

    private fun decimalsOf(t: EngineTrade, p: Pending?): Int = t.decimals ?: p?.info?.decimals ?: 18

    private fun noteOf(t: EngineTrade, p: Pending): TradeNote {
        val swap = t.swap
        val buy = t.side == Side.BUY
        if (t.status == TradeStatus.DONE) {
            val detail = if (buy) {
                val out = swap.amountOut
                val tokens = out?.let { BigDecimal(it).movePointLeft(decimalsOf(t, p)).toDouble() }
                val paid = swap.amountIn?.let { w -> p.price?.let { usdOf(w, p.chain.decimals, it) } }
                val mcap = if (tokens != null && tokens > 0 && paid != null && p.info.supply != null) paid / tokens * p.info.supply else null
                listOfNotNull(tokens?.let { "≈ ${Fmt.amount(it)} ${p.info.label}" }, mcap?.let { "at MC ${Fmt.usd(it)}" }).joinToString(" ")
            } else {
                val payout = t.payout
                if (payout != null) {
                    // Paid in the token the coin is priced in: no pool turns it into SOL.
                    val amount = swap.amountOut?.let { BigDecimal(it).movePointLeft(payout.decimals).toDouble() }
                    val usd = amount?.let { a -> payout.usd?.let { a * it } }
                    when {
                        amount == null -> "Paid in ${payout.symbol}, which stays in the wallet"
                        usd != null -> "≈ ${Fmt.amount(amount)} ${payout.symbol} (${Fmt.usdFull(usd)}) stays in the wallet"
                        else -> "≈ ${Fmt.amount(amount)} ${payout.symbol} stays in the wallet"
                    }
                } else {
                    val got = swap.amountOut?.let { w -> p.price?.let { usdOf(w, p.chain.decimals, it) } }
                    got?.let { "About ${Fmt.usdFull(it)} is back in One Balance" } ?: "Back in One Balance on ${p.chain.title}"
                }
            }
            return TradeNote(t.id, p.title, detail, p.chain, t.token, ok = true)
        }
        // A sell through its pay token landed, but that token did not go back to SOL.
        val fund = t.steps.firstOrNull { it.kind == StepKind.FUND && it.status != StepStatus.CONFIRMED }
        if (!buy && swap.status == StepStatus.CONFIRMED && fund != null) {
            return TradeNote(t.id, "Sold, not back in ${p.chain.native}", fund.error ?: "Stopped", p.chain, t.token, ok = false)
        }
        // Nothing went out unless the swap has a hash: then it was sent and did not land.
        val sent = swap.hash != null
        val title = (if (buy) "Buy" else "Sell") + if (sent) " failed" else " not sent"
        return TradeNote(t.id, title, swap.error ?: "Stopped", p.chain, t.token, ok = false)
    }

    private fun chainOf(t: EngineTrade): Chain? = if (t.chainId == SolTrader.CHAIN_ID) Chain.SOLANA else Chain.of(t.chainId.toString())

    /** A swap landed: its figures now, then the server's reading of its receipt as the entry. */
    private suspend fun landed(t: EngineTrade) {
        val chain = chainOf(t) ?: return
        val hash = t.swap.hash ?: return
        val p = pending[t.id]
        val price = p?.price ?: priceOf(chain)
        estimate(t, chain, hash, p, price)?.let { store.put(it) }
        scope.launch {
            runCatching { balances.refresh() }
            for (wait in FILL_WAITS) {
                delay(wait)
                val f = try {
                    api.fill(chain, hash, t.wallet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
                if (f.state == "pending") continue
                // A reading of another coin (a funding leg) is not this trade's entry.
                if (f.state == "filled" && f.token != null && sameToken(chain, f.token, t.token)) {
                    store.put(exact(f, t, chain, hash, p, price))
                    runCatching { balances.refresh() }
                }
                break
            }
            pending.remove(t.id)
        }
    }

    /** The network fee in dollars: the swap's, and on Solana its step through the pay token too. */
    private fun feeUsd(t: EngineTrade, chain: Chain, price: Double?): Double? {
        val steps = if (chain == Chain.SOLANA) t.steps else listOf(t.swap)
        val fees = steps.mapNotNull { it.feeWei }
        if (fees.isEmpty() || price == null) return null
        return usdOf(fees.fold(BigInteger.ZERO) { s, f -> s + f }, chain.decimals, price)
    }

    private fun estimate(t: EngineTrade, chain: Chain, hash: String, p: Pending?, price: Double?): Fill? {
        val swap = t.swap
        val decimals = decimalsOf(t, p)
        val buy = t.side == Side.BUY
        val raw = (if (buy) swap.amountOut else swap.amountIn) ?: return null
        val native = if (buy) swap.amountIn else swap.amountOut
        val payout = t.payout
        val usd = if (!buy && payout != null) {
            native?.let { w -> payout.usd?.let { usdOf(w, payout.decimals, it) } }
        } else {
            native?.let { w -> price?.let { usdOf(w, chain.decimals, it) } }
        }
        val tokens = BigDecimal(raw).movePointLeft(decimals).toDouble()
        val supply = p?.info?.supply
        return Fill(
            wallet = keyOf(chain, t.wallet),
            feed = chain.feed,
            token = keyOf(chain, t.token),
            symbol = t.symbol ?: p?.info?.symbol,
            side = if (buy) "buy" else "sell",
            tx = hash,
            at = System.currentTimeMillis(),
            raw = raw.toString(),
            decimals = decimals,
            usd = usd,
            feeUsd = withExtra(feeUsd(t, chain, price), p),
            mcapUsd = if (usd != null && tokens > 0 && supply != null) usd / tokens * supply else null,
            exact = false,
        )
    }

    private fun exact(f: FillAnswer, t: EngineTrade, chain: Chain, hash: String, p: Pending?, price: Double?) = Fill(
        wallet = keyOf(chain, t.wallet),
        feed = chain.feed,
        token = keyOf(chain, t.token),
        symbol = t.symbol ?: p?.info?.symbol,
        side = f.side ?: if (t.side == Side.BUY) "buy" else "sell",
        tx = hash,
        at = f.at ?: System.currentTimeMillis(),
        raw = f.raw ?: "0",
        decimals = f.decimals ?: decimalsOf(t, p),
        usd = f.usd,
        feeUsd = withExtra(feeUsd(t, chain, f.nativeUsd ?: price), p),
        mcapUsd = f.mcapUsd,
        exact = true,
    )

    /** The network fee with what moving funds for the trade cost: both are part of what it cost to get in. */
    private fun withExtra(fee: Double?, p: Pending?): Double? {
        val extra = p?.extraFeeUsd ?: 0.0
        return if (extra > 0) (fee ?: 0.0) + extra else fee
    }

    // ── positions ───────────────────────────────────────────────────────────────────

    /** The addresses of the wallet that trades: its EVM one and its Solana one. */
    private fun own(): List<String> = listOfNotNull(session.active?.evm, session.active?.sol)

    /** The positions of the wallet that trades, with its balances and prices now. */
    fun holdings(): List<Holding> {
        val fills = activity()
        if (fills.isEmpty()) return emptyList()
        return Positions.holdings(fills, balances.active) { c, t -> prices[Balances.tokenKey(c, t)] }
    }

    /** One coin's position, priced at `priceNow` when the screen has a fresher price. */
    fun holding(chain: Chain, token: String, priceNow: Double? = null): Holding? {
        val wallet = addressOn(chain) ?: return null
        val fills = store.of(wallet).filter { it.feed == chain.feed && sameToken(chain, it.token, token) }
        if (fills.isEmpty()) return null
        val p = Positions.of(fills).firstOrNull() ?: return null
        val held = balances.active?.token(chain, token) ?: p.booked
        return Holding(p, held, priceNow ?: prices[Balances.tokenKey(chain, token)])
    }

    /** The fills of the wallet that trades, newest first: its activity. */
    fun activity(): List<Fill> = own().flatMap { store.of(it) }.distinct().sortedByDescending { it.at }

    /** Prices of the coins of the wallet's positions, read while a screen shows them. */
    suspend fun livePrices() {
        while (true) {
            val coins = own().flatMap { store.coinsOf(it) }.distinct().take(30)
            if (coins.isNotEmpty()) {
                runCatching { api.prices(coins) }.getOrNull()?.prices?.forEach { r ->
                    val c = Chain.of(r.feed) ?: return@forEach
                    val key = Balances.tokenKey(c, r.address)
                    r.priceUsd?.takeIf { it > 0 }?.let { prices[key] = it }
                    r.mcapUsd?.takeIf { it > 0 }?.let { mcaps[key] = it }
                }
            }
            delay(20_000)
        }
    }

    companion object {
        const val LOCKED = "This version of Nesty cannot trade this coin yet. Everything else on this page still works."
        const val NO_EVM = "This wallet was made from a Solana key: it has no EVM address."
        const val NO_SOL = "This wallet was made from an EVM key: it has no Solana address."

        /** What a Solana buy keeps back for the accounts it opens (the engine's own headroom). */
        private val SOL_RESERVE: BigInteger = BigInteger.valueOf(6_000_000L)

        /** Waits before each ask for a fill: the server may not have the receipt yet. */
        private val FILL_WAITS = listOf(1_500L, 3_000, 5_000, 10_000, 20_000, 40_000, 60_000)

        fun gasOf(chain: EvmChain, speed: String): GasPreset = when (speed) {
            "Normal" -> chain.p1
            "Turbo" -> chain.p3
            else -> chain.p2
        }

        fun prefsOf(chain: EvmChain, preset: Preset) = TradePrefs(slippageBps = slippageBps(preset), gas = gasOf(chain, preset.speed))

        fun slippageBps(preset: Preset) = (preset.slippage * 100).coerceIn(10, 5_000)

        /** Solana's priority fee per transaction at each speed: the extension's Low, Medium and High. */
        fun priorityLamports(speed: String): BigInteger = BigInteger.valueOf(
            when (speed) {
                "Normal" -> 100_000L
                "Turbo" -> 2_000_000L
                else -> 500_000L
            },
        )

        fun solPrefsOf(preset: Preset) = SolPrefs(slippageBps = slippageBps(preset), priorityLamports = priorityLamports(preset.speed))

        /** A coin's address as a fill keeps it: EVM addresses lowercase, Solana ones as they are. */
        fun keyOf(chain: Chain, address: String): String = if (chain == Chain.SOLANA) address else address.lowercase()

        fun sameToken(chain: Chain, a: String, b: String): Boolean = if (chain == Chain.SOLANA) a == b else a.equals(b, ignoreCase = true)
    }
}
