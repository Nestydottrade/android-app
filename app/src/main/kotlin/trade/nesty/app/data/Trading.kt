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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import trade.nesty.app.evm.Broadcast
import trade.nesty.app.evm.BuyRequest
import trade.nesty.app.evm.ChainReader
import trade.nesty.app.evm.Erc20
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
import trade.nesty.app.evm.sanitizeLabel
import trade.nesty.app.evm.shortErr
import trade.nesty.app.i18n.tr
import trade.nesty.app.move.Recipient
import trade.nesty.app.move.Typed
import trade.nesty.app.sol.HttpSolCall
import trade.nesty.app.sol.Jito
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolBroadcast
import trade.nesty.app.sol.SolBuyRequest
import trade.nesty.app.sol.SolPrefs
import trade.nesty.app.sol.SolRpc
import trade.nesty.app.sol.SolRpcs
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
    /** The coin's tax as its launchpad lists it. */
    val tax: Tax? = null,
    /** Whether it trades on its launchpad's curve, whose venue takes that tax inside its quotes. */
    val onCurve: Boolean = false,
    /** Its price when the screen read it, and the launchpad it started on: for the trade's page. */
    val priceUsd: Double? = null,
    val launchpad: String? = null,
) {
    val label: String get() = symbol?.takeIf { it.isNotBlank() } ?: tr("this coin")

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
                tax = coin.tax,
                onCurve = coin.onCurve,
                priceUsd = coin.priceUsd?.takeIf { it > 0 && it.isFinite() },
                launchpad = coin.launchpad,
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

    /** Coins added to Portfolio by hand. */
    lateinit var imports: ImportStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val notesFlow = MutableSharedFlow<TradeNote>(extraBufferCapacity = 16)

    /** A note for each trade that finished, landed or not. */
    val notes: SharedFlow<TradeNote> = notesFlow.asSharedFlow()

    /** Trades whose screen shows how they ended itself: no note at the bottom for them. */
    val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sells by an exit plan: the plan's own alert says how they went. */
    val byPlan: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /** The chains' public RPCs only, for reads that name one of the user's wallets over and over. */
    private val directRpc = HttpRpc(http, direct = true)
    private val directReaders = ConcurrentHashMap<Long, ChainReader>()
    private val solDirect = SolTrader(SolRpc(HttpSolCall(http, urls = SolRpcs.PUBLIC), SolBroadcast(http)), knownSolUsd = { priceOf(Chain.SOLANA) })

    val sol = SolTrader(
        // Trades with a tip go to the block engines only (MEV protection); the rest to the public RPCs.
        rpc = SolRpc(HttpSolCall(http, api.base.toString()), SolBroadcast(http), SolBroadcast(http, Jito.ENGINES, engines = true)),
        knownSolUsd = { priceOf(Chain.SOLANA) },
        onLanded = { t -> landed(t) },
    )

    /**
     * A trade being followed. `price`: the chain's coin in dollars when it started. `extraFeeUsd`:
     * what moving funds here for this buy cost, kept in its cost basis. The rest is for its page.
     */
    private class Pending(
        val chain: Chain,
        val info: CoinInfo,
        val title: String,
        val price: Double?,
        val tappedAt: Long,
        val extraFeeUsd: Double = 0.0,
        val preset: Preset? = null,
        val presetIndex: Int? = null,
        /** A buy's amount in dollars. */
        val usd: Double? = null,
        val moved: Moved? = null,
        val byPlan: Boolean = false,
    ) {
        /** What the route quoted for it when it started: its price impact and what the venue takes. */
        @Volatile var quote: Quote? = null

        /** The coin's price then, read in the background when the screen had none. */
        @Volatile var priceUsd: Double? = info.priceUsd
        @Volatile var sentAt: Long? = null
        @Volatile var landedAt: Long? = null

        /** The chain's coin in dollars at its block, once the server read the receipt. */
        @Volatile var blockNativeUsd: Double? = null

        /** Its newest state. */
        @Volatile var last: EngineTrade? = null
    }

    /** Funds moved to the chain for a buy, as its page shows them: the move and its transactions. */
    data class Moved(val move: FillMove, val txs: List<FillTx>)

    fun evmOf(chain: Chain): EvmChain? = if (chain.isEvm) chain.feed.toLongOrNull()?.let { EvmChains.byId(it) } else null

    /** Whether this build can trade on `chain`. */
    fun canTrade(chain: Chain): Boolean = chain == Chain.SOLANA || evmOf(chain) != null

    /**
     * Whether trading `coin` needs a newer Nesty: its chain is not built in, or it is still on a
     * launchpad curve this build does not know. One that left its curve for a pool trades
     * wherever this build knows the pool.
     */
    fun needsUpdate(chain: Chain, coin: Coin?): Boolean = !canTrade(chain) || !KnownCurves.tradable(chain, coin)

    /** The wallet that trades' address on `chain`: its EVM address, or its Solana one. */
    fun addressOn(chain: Chain): String? = session.active?.let { if (chain.isEvm) it.evm else it.sol }

    /** Why a trade on `chain` cannot start now, before anything is read; null when it can. */
    fun cannotTrade(chain: Chain): String? {
        if (!canTrade(chain)) return tr("This version of Nesty cannot trade on {1} yet.", chain.title)
        val wallet = session.active ?: return tr("Add a wallet first.")
        if (chain == Chain.SOLANA) {
            if (wallet.sol == null) return NO_SOL
            if (session.solSigner() == null) return tr("Unlock your wallets first.")
            return null
        }
        if (wallet.evm == null) return NO_EVM
        if (session.evmSigner() == null) return tr("Unlock your wallets first.")
        return null
    }

    /**
     * Sends go out privately on this chain, away from front-runners: a private relay on Ethereum and
     * BNB Chain, the block engines with a tip on Solana.
     */
    fun mevProtect(chain: Chain): Boolean = chain == Chain.SOLANA || evmOf(chain)?.mevRpc != null

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
        val wallet = session.active ?: throw TradeRefused(tr("Add a wallet first."))
        if (wallet.evm == null) throw TradeRefused(NO_EVM)
        return session.evmSigner() ?: throw TradeRefused(tr("Unlock your wallets first."))
    }

    private fun solSigner(): SolSigner {
        val wallet = session.active ?: throw TradeRefused(tr("Add a wallet first."))
        if (wallet.sol == null) throw TradeRefused(NO_SOL)
        return session.solSigner() ?: throw TradeRefused(tr("Unlock your wallets first."))
    }

    private fun evm(chain: Chain): EvmChain = evmOf(chain) ?: throw TradeRefused(LOCKED)

    fun weiOf(usd: Double, chain: Chain, price: Double): BigInteger =
        BigDecimal(usd).divide(BigDecimal(price), MathContext.DECIMAL128).movePointRight(chain.decimals).toBigInteger()

    fun usdOf(raw: BigInteger, decimals: Int, price: Double): Double = BigDecimal(raw).movePointLeft(decimals).toDouble() * price

    /**
     * Buys `usd` of a coin from the chain's own balance. Throws TradeRefused when it cannot start.
     * `presetIndex`: which preset it is (0 is P1). `known`: the quote the screen showed for it, else
     * one is read beside the send. `moved`: funds moved to the chain for it; their cost joins its
     * network fee.
     */
    suspend fun buy(
        chain: Chain,
        token: String,
        usd: Double,
        preset: Preset,
        info: CoinInfo,
        presetIndex: Int? = null,
        known: Quote? = null,
        moved: Moved? = null,
    ): StateFlow<EngineTrade> {
        val tapped = System.currentTimeMillis()
        val send = if (chain == Chain.SOLANA) solBuyer(token, preset, info) else evmBuyer(chain, token, preset, info)
        if (usd <= 0 || usd.isNaN()) throw TradeRefused(tr("Enter an amount."))
        val price = priceOf(chain) ?: throw TradeRefused(tr("The {1} price is not read yet. Try again in a moment.", chain.native))
        val wei = weiOf(usd, chain, price)
        val balance = balances.active
        val have = balance?.raw?.get(chain)
        if (have != null && have < wei) {
            throw TradeRefused(tr("More than your {1} balance of {2}.", chain.title, Fmt.usdFull(balance.chains[chain] ?: 0.0)))
        }
        val flow = start { send(wei) }
        val p = Pending(
            chain, info, tr("Bought {1} of {2}", Fmt.usdFull(usd), info.label), price, tapped,
            extraFeeUsd = moved?.move?.feeUsd?.coerceAtLeast(0.0) ?: 0.0,
            preset = preset, presetIndex = presetIndex, usd = usd, moved = moved,
        )
        p.quote = known
        follow(flow, p)
        if (known == null) scope.launch { p.quote = orNull { quoteBuy(chain, token, wei, info) } }
        readPriceIfNone(p, chain, token)
        return flow
    }

    /** The coin's price for the trade's page, when the screen that started it had none. Only the coin's address is asked. */
    private fun readPriceIfNone(p: Pending, chain: Chain, token: String) {
        if (p.priceUsd != null) return
        scope.launch {
            val row = orNull { api.prices(listOf(chain to token)).prices.firstOrNull() }
            row?.priceUsd?.takeIf { it > 0 && it.isFinite() }?.let { if (p.priceUsd == null) p.priceUsd = it }
        }
    }

    /** A sell's quote for the trade's page, read beside the send: its price impact and what the venue takes. */
    private fun quoteSellBeside(p: Pending, chain: Chain, token: String, flow: StateFlow<EngineTrade>, info: CoinInfo) {
        val amount = flow.value.swap.amountIn?.takeIf { it.signum() > 0 } ?: return
        scope.launch {
            p.quote = orNull {
                if (chain == Chain.SOLANA) {
                    sol.quotes(token, Side.SELL, listOf(amount), info.hints).firstOrNull()
                } else {
                    evmOf(chain)?.let { engine.quotes(it.id, token.lowercase(), Side.SELL, listOf(amount), info.hints).firstOrNull() }
                }
            }
        }
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
    suspend fun sell(chain: Chain, token: String, bps: Int, preset: Preset, info: CoinInfo, title: String, presetIndex: Int? = null): StateFlow<EngineTrade> {
        val tapped = System.currentTimeMillis()
        val share = bps.coerceIn(1, 10_000)
        val flow = if (chain == Chain.SOLANA) {
            val signer = solSigner()
            start { sol.sell(SolSellRequest(token, share, solPrefsOf(preset), info.symbol, info.hints), signer) }
        } else {
            val evm = evm(chain)
            val signer = signer()
            start { engine.sell(SellRequest(evm.id, token.lowercase(), share, prefsOf(evm, preset), info.symbol, info.hints), signer, session.ownEvm()) }
        }
        val p = Pending(chain, info, title, priceOf(chain), tapped, preset = preset, presetIndex = presetIndex)
        follow(flow, p)
        quoteSellBeside(p, chain, token, flow, info)
        readPriceIfNone(p, chain, token)
        return flow
    }

    private suspend fun start(block: suspend () -> StateFlow<EngineTrade>): StateFlow<EngineTrade> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TradeException) {
        throw TradeRefused(e.message ?: tr("Could not start."))
    } catch (e: TradeRefused) {
        throw e
    } catch (e: Exception) {
        throw TradeRefused(tr("Could not read the chain: {1}", shortErr(e)))
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
     * What `wallet` holds of a coin, read from the chain's own public RPCs, never through Nesty's
     * server: an exit plan reads it every few seconds, for any of the user's wallets. Null when it
     * could not be read.
     */
    suspend fun heldOf(chain: Chain, token: String, wallet: String): BigInteger? = try {
        if (chain == Chain.SOLANA) {
            solDirect.heldOf(wallet, token)
        } else {
            evmOf(chain)?.let { evm -> directReaders.getOrPut(evm.id) { ChainReader(evm, directRpc) }.read1(token.lowercase(), Erc20.balanceOf, wallet) as? BigInteger }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * What selling `amount` of a coin pays now, in dollars, price impact and tax included; null
     * when nothing sells it. Exit plans measure with it. Throws when the chain could not be read.
     * A pool's quote leaves out a transfer tax the coin takes on the way in: taken off here.
     */
    suspend fun sellValueUsd(chain: Chain, token: String, amount: BigInteger, info: CoinInfo): Double? {
        if (chain == Chain.SOLANA) return sol.sellValueUsd(token, amount, info.hints)
        val evm = evmOf(chain) ?: return null
        val q = engine.quotes(evm.id, token.lowercase(), Side.SELL, listOf(amount), info.hints).firstOrNull() ?: return null
        val price = nativeUsd(chain) ?: return null
        val outside = TradeCosts.taxOutside(info.tax?.sell, info.onCurve, q.takeBps)
        return usdOf(q.out, chain.decimals, price) * (1 - outside)
    }

    private val nativeRead = ConcurrentHashMap<Chain, Pair<Long, Double>>()

    /**
     * The chain's own coin in dollars, read again after a minute: exit plans run with no screen
     * reading balances. The last price read stands in while a new one cannot be read.
     */
    suspend fun nativeUsd(chain: Chain): Double? {
        val now = System.currentTimeMillis()
        nativeRead[chain]?.let { (at, usd) -> if (now - at < NATIVE_TTL_MS) return usd }
        val read = if (chain == Chain.SOLANA) {
            orNull { sol.price.usd() }
        } else {
            evmOf(chain)?.let { evm -> orNull { api.prices(listOf(chain to evm.wrappedNative)).prices.firstOrNull()?.priceUsd } }
        }
        val usd = read?.takeIf { it > 0 } ?: return priceOf(chain) ?: nativeRead[chain]?.second
        nativeRead[chain] = now to usd
        return usd
    }

    /**
     * An exit plan's sell: `bps` of what one of the user's wallets holds, signed for that wallet
     * whether it is the one that trades or not, also while Nesty is locked with the watch kept.
     * The plan says how it went, so the usual note at the bottom of the screen is left out.
     */
    suspend fun sellForWatch(
        walletId: String,
        wallet: String,
        chain: Chain,
        token: String,
        bps: Int,
        preset: Preset,
        info: CoinInfo,
        title: String,
        presetIndex: Int? = null,
    ): StateFlow<EngineTrade> {
        val tapped = System.currentTimeMillis()
        val share = bps.coerceIn(1, 10_000)
        val flow = if (chain == Chain.SOLANA) {
            val signer = session.watchSolSigner(walletId, wallet)
            start { sol.sell(SolSellRequest(token, share, solPrefsOf(preset), info.symbol, info.hints), signer) }
        } else {
            val evm = evm(chain)
            val signer = session.watchSigner(walletId, wallet)
            start { engine.sell(SellRequest(evm.id, token.lowercase(), share, prefsOf(evm, preset), info.symbol, info.hints), signer, listOf(wallet.lowercase())) }
        }
        quiet += flow.value.id
        byPlan += flow.value.id
        val p = Pending(chain, info, title, priceOf(chain) ?: nativeUsd(chain), tapped, preset = preset, presetIndex = presetIndex, byPlan = true)
        follow(flow, p)
        quoteSellBeside(p, chain, token, flow, info)
        readPriceIfNone(p, chain, token)
        return flow
    }

    /** What a finished sell paid, in dollars: in the chain's coin, or the token it paid in. */
    fun proceedsUsd(t: EngineTrade, chain: Chain, nativePrice: Double?): Double? {
        val out = t.swap.amountOut ?: return null
        val payout = t.payout
        return if (payout != null) payout.usd?.let { usdOf(out, payout.decimals, it) } else nativePrice?.let { usdOf(out, chain.decimals, it) }
    }

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
            val t = flow.onEach { now ->
                p.last = now
                if (p.sentAt == null && now.swap.hash != null) p.sentAt = System.currentTimeMillis()
            }.first { it.status != TradeStatus.WORKING }
            p.last = t
            notesFlow.emit(noteOf(t, p))
            val chain = chainOf(t)
            if (chain != null) {
                // Landed: its entry gets what came after the swap (an approval, the way back to SOL).
                // Sent and not landed: kept for Activity, with what its network fee was.
                if (t.swap.status == StepStatus.CONFIRMED) settle(t, chain, p) else t.swap.hash?.let { store.put(failedFill(t, chain, p, it)) }
            }
            if (t.status != TradeStatus.DONE) pending.remove(t.id)
        }
    }

    /** A landed trade's entry once the trade ended: every transaction and network fee it paid. */
    private fun settle(t: EngineTrade, chain: Chain, p: Pending) {
        val hash = t.swap.hash ?: return
        store.update(keyOf(chain, t.wallet), hash) { f ->
            f.copy(feeUsd = withExtra(feeUsd(t, chain, p.blockNativeUsd ?: p.price), p), detail = detailOf(t, chain, p))
        }
    }

    private fun failedFill(t: EngineTrade, chain: Chain, p: Pending, hash: String): Fill {
        val buy = t.side == Side.BUY
        return Fill(
            wallet = keyOf(chain, t.wallet),
            feed = chain.feed,
            token = keyOf(chain, t.token),
            symbol = t.symbol ?: p.info.symbol,
            side = if (buy) "buy" else "sell",
            tx = hash,
            at = System.currentTimeMillis(),
            // What it tried to sell; a buy got nothing.
            raw = if (buy) "0" else t.swap.amountIn?.toString() ?: "0",
            decimals = decimalsOf(t, p),
            usd = if (buy) p.usd else null,
            feeUsd = withExtra(feeUsd(t, chain, p.price), p),
            exact = false,
            detail = detailOf(t, chain, p).copy(error = t.swap.error ?: tr("Stopped")),
            failed = true,
        )
    }

    /** How a trade was made, for its page. */
    private fun detailOf(t: EngineTrade, chain: Chain, p: Pending): FillDetail {
        val buy = t.side == Side.BUY
        val swap = t.swap
        val q = p.quote
        val fees = t.steps.mapNotNull { it.feeWei }
        val tips = t.steps.mapNotNull { it.tip }
        val listed = if (buy) p.info.tax?.buy else p.info.tax?.sell
        // In the order they went out: a buy's way into its pay token first, a sell's approval first.
        val rank = mapOf(StepKind.FUND to if (buy) 0 else 2, StepKind.APPROVE to if (buy) 2 else 0, StepKind.SWAP to 1)
        val own = t.steps.sortedBy { rank[it.kind] ?: 1 }.mapNotNull { step ->
            val hash = step.hash ?: return@mapNotNull null
            val kind = when (step.kind) {
                StepKind.SWAP -> if (buy) "buy" else "sell"
                StepKind.APPROVE -> "approve"
                StepKind.FUND -> if (buy) "fund" else "back"
            }
            FillTx(kind, chain.feed, hash, step.status != StepStatus.FAILED)
        }
        return FillDetail(
            route = t.venue,
            launchpad = p.info.launchpad,
            onCurve = p.info.onCurve,
            preset = p.presetIndex,
            slippageBps = p.preset?.let { slippageBps(it) },
            speed = p.preset?.speed,
            mev = if (mevProtect(chain)) true else null,
            minOut = swap.minOut?.toString(),
            impact = q?.priceImpactBps?.coerceAtLeast(0)?.let { it / 10_000.0 },
            takeBps = q?.takeBps,
            taxOutside = TradeCosts.taxOutside(listed, p.info.onCurve, q?.takeBps).takeIf { it > 0 },
            priceUsd = p.priceUsd,
            nativeUsd = p.blockNativeUsd ?: p.price,
            native = (if (buy) swap.amountIn else swap.amountOut)?.toString(),
            payout = t.payout?.symbol,
            payoutDecimals = t.payout?.decimals,
            feeRaw = if (fees.isEmpty()) null else fees.fold(BigInteger.ZERO) { a, b -> a + b }.toString(),
            tipRaw = if (tips.isEmpty()) null else tips.fold(BigInteger.ZERO) { a, b -> a + b }.toString(),
            move = p.moved?.move,
            sentMs = p.sentAt?.let { (it - p.tappedAt).coerceAtLeast(0) },
            confirmMs = p.landedAt?.let { at -> p.sentAt?.takeIf { it <= at }?.let { at - it } },
            txs = (p.moved?.txs ?: emptyList()) + own,
            byPlan = p.byPlan,
        )
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
                when {
                    tokens == null -> ""
                    mcap != null -> tr("≈ {1} {2} at MC {3}", Fmt.amount(tokens), p.info.label, Fmt.usd(mcap))
                    else -> "≈ ${Fmt.amount(tokens)} ${p.info.label}"
                }
            } else {
                val payout = t.payout
                if (payout != null) {
                    // Paid in the token the coin is priced in: no pool turns it into SOL.
                    val amount = swap.amountOut?.let { BigDecimal(it).movePointLeft(payout.decimals).toDouble() }
                    val usd = amount?.let { a -> payout.usd?.let { a * it } }
                    when {
                        amount == null -> tr("Paid in {1}, which stays in the wallet", payout.symbol)
                        usd != null -> tr("≈ {1} {2} ({3}) stays in the wallet", Fmt.amount(amount), payout.symbol, Fmt.usdFull(usd))
                        else -> tr("≈ {1} {2} stays in the wallet", Fmt.amount(amount), payout.symbol)
                    }
                } else {
                    val got = swap.amountOut?.let { w -> p.price?.let { usdOf(w, p.chain.decimals, it) } }
                    got?.let { tr("About {1} is back in One Balance", Fmt.usdFull(it)) } ?: tr("Back in One Balance on {1}", p.chain.title)
                }
            }
            return TradeNote(t.id, p.title, detail, p.chain, t.token, ok = true)
        }
        // A sell through its pay token landed, but that token did not go back to SOL.
        val fund = t.steps.firstOrNull { it.kind == StepKind.FUND && it.status != StepStatus.CONFIRMED }
        if (!buy && swap.status == StepStatus.CONFIRMED && fund != null) {
            return TradeNote(t.id, tr("Sold, not back in {1}", p.chain.native), fund.error ?: tr("Stopped"), p.chain, t.token, ok = false)
        }
        // Nothing went out unless the swap has a hash: then it was sent and did not land.
        val sent = swap.hash != null
        val title = when {
            buy && sent -> tr("Buy failed")
            buy -> tr("Buy not sent")
            sent -> tr("Sell failed")
            else -> tr("Sell not sent")
        }
        return TradeNote(t.id, title, swap.error ?: tr("Stopped"), p.chain, t.token, ok = false)
    }

    private fun chainOf(t: EngineTrade): Chain? = if (t.chainId == SolTrader.CHAIN_ID) Chain.SOLANA else Chain.of(t.chainId.toString())

    /** A swap landed: its figures now, then the server's reading of its receipt as the entry. */
    private suspend fun landed(t: EngineTrade) {
        val chain = chainOf(t) ?: return
        val hash = t.swap.hash ?: return
        val p = pending[t.id]
        if (p != null && p.landedAt == null) p.landedAt = System.currentTimeMillis()
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
                    if (p != null) f.nativeUsd?.takeIf { it > 0 }?.let { p.blockNativeUsd = it }
                    store.put(exact(f, p?.last ?: t, chain, hash, p, price))
                    runCatching { balances.refresh() }
                }
                break
            }
            pending.remove(t.id)
        }
    }

    /**
     * The network fee in dollars: every transaction of the trade, the swap and an approval for the
     * sale on a first buy, on Solana its steps through the pay token too. The sheet counts the same.
     */
    private fun feeUsd(t: EngineTrade, chain: Chain, price: Double?): Double? {
        val fees = t.steps.mapNotNull { it.feeWei } + t.steps.mapNotNull { it.tip }
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
            detail = p?.let { detailOf(t, chain, it) },
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
        // The phone's detail stays; one is made here only when there is none yet.
        detail = if (p != null && store.byTx(keyOf(chain, t.wallet), hash)?.detail == null) detailOf(t, chain, p) else null,
    )

    /** The network fee with what moving funds for the trade cost: both are part of what it cost to get in. */
    private fun withExtra(fee: Double?, p: Pending?): Double? {
        val extra = p?.extraFeeUsd ?: 0.0
        return if (extra > 0) (fee ?: 0.0) + extra else fee
    }

    // ── positions ───────────────────────────────────────────────────────────────────

    /** The addresses of the wallet that trades: its EVM one and its Solana one. */
    private fun own(): List<String> = listOfNotNull(session.active?.evm, session.active?.sol)

    /** The positions of the wallet that trades, with its balances and prices now, and the coins added by hand. */
    fun holdings(): List<Holding> {
        val fills = activity()
        val added = own().flatMap { imports.of(it) }
        if (fills.isEmpty() && added.isEmpty()) return emptyList()
        return Positions.holdings(fills, balances.active, added) { c, t -> prices[Balances.tokenKey(c, t)] }
    }

    /** One coin's position, priced at `priceNow` when the screen has a fresher price. */
    fun holding(chain: Chain, token: String, priceNow: Double? = null): Holding? {
        val wallet = addressOn(chain) ?: return null
        val fills = store.of(wallet).filter { it.feed == chain.feed && sameToken(chain, it.token, token) }
        val added = imports.find(wallet, chain, token)
        val p = (Positions.of(fills).firstOrNull()?.let { if (added != null) it.copy(imported = true) else it })
            ?: added?.let { Position.of(it, chain) }
            ?: return null
        val held = balances.active?.token(chain, token) ?: p.booked
        return Holding(p, held, priceNow ?: prices[Balances.tokenKey(chain, token)])
    }

    /** The trades of the wallet that trades, newest first, with those that were sent and did not land: its activity. */
    fun activity(): List<Fill> = own().flatMap { store.activityOf(it) }.distinct().sortedByDescending { it.at }

    /** The coins added by hand to the wallet that trades, to name with its balance and its prices. */
    private fun addedCoins(): List<Pair<Chain, String>> = own().flatMap { imports.of(it) }.mapNotNull { i -> i.chain?.let { it to i.token } }

    /**
     * Adds a coin the wallet that trades got from outside Nesty to Portfolio. Its address is read
     * on each chain of its kind the wallet has, and the coin is added where the wallet holds some.
     * The server sees the wallet's address with the coin, as for every balance it reads. Throws
     * TradeRefused with the reason when nothing was added.
     */
    suspend fun importCoin(input: String): List<Imported> {
        val wallet = session.active ?: throw TradeRefused(tr("Add a wallet first."))
        val typed = Recipient.parse(input) ?: throw TradeRefused(tr("Paste the coin's address."))
        val (owner, chains) = when (typed) {
            is Typed.Bad -> throw TradeRefused(typed.reason)
            is Typed.EvmAddress -> (wallet.evm ?: throw TradeRefused(NO_EVM)) to Balances.chainsOf(wallet).filter { it.isEvm }
            is Typed.SolAddress -> (wallet.sol ?: throw TradeRefused(NO_SOL)) to listOf(Chain.SOLANA)
        }
        val token = keyOf(chains.first(), typed.address)
        if (sameToken(chains.first(), token, owner)) throw TradeRefused(tr("This is your wallet's own address. Paste the coin's address."))
        val answer = try {
            api.balances(owner, chains.map { it to token })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TradeRefused(tr("Could not read your balances just now. Try again."))
        }
        val read = answer.chains.filter { !it.failed }.mapNotNull { b -> Chain.of(b.feed)?.takeIf { it in chains }?.let { it to b } }
        val held = read.filter { (c, b) -> b.tokens.any { sameToken(c, it.address, token) && (it.raw?.toBigIntegerOrNull()?.signum() ?: 0) > 0 } }.map { it.first }
        if (held.isEmpty()) {
            throw TradeRefused(if (read.size < chains.size) tr("Some chains could not be read just now. Try again.") else tr("This wallet holds none of this coin."))
        }
        val fresh = held.filter { c -> store.of(owner).none { it.feed == c.feed && sameToken(c, it.token, token) } && imports.find(owner, c, token) == null }
        if (fresh.isEmpty()) throw TradeRefused(tr("This coin is already in your Portfolio."))
        val added = fresh.mapNotNull { c ->
            val facts = factsOf(c, token) ?: return@mapNotNull null
            facts.priceUsd?.takeIf { it > 0 && it.isFinite() }?.let { prices[Balances.tokenKey(c, token)] = it }
            facts.mcapUsd?.takeIf { it > 0 && it.isFinite() }?.let { mcaps[Balances.tokenKey(c, token)] = it }
            Imported(keyOf(c, owner), c.feed, token, facts.symbol, facts.decimals, System.currentTimeMillis())
        }
        if (added.isEmpty()) throw TradeRefused(tr("Could not read this coin just now. Try again."))
        for (i in added) {
            if (!imports.put(i)) throw TradeRefused(tr("You have added {1} coins to this wallet already. Remove one first.", ImportStore.MAX_PER_WALLET))
        }
        // The balance that names the new coin, so its row shows what the wallet holds at once.
        orNull { balances.refresh() }
        return added
    }

    /** What a coin added by hand is called and how it counts: from the server by its address, else from its chain. */
    private data class Facts(val symbol: String?, val decimals: Int, val priceUsd: Double?, val mcapUsd: Double?)

    private suspend fun factsOf(chain: Chain, token: String): Facts? {
        val coin = orNull { api.token(chain, token) }
        val symbol = coin?.symbol?.let { sanitizeLabel(it, 16) }?.takeIf { it.isNotEmpty() }
        coin?.decimals?.let { return Facts(symbol, it, coin.priceUsd, coin.mcapUsd) }
        if (chain == Chain.SOLANA) return orNull { solDirect.tokenMeta(token) }?.let { Facts(symbol ?: it.symbol, it.decimals, coin?.priceUsd, coin?.mcapUsd) }
        val evm = evmOf(chain) ?: return null
        val reader = directReaders.getOrPut(evm.id) { ChainReader(evm, directRpc) }
        val decimals = orNull { reader.read1(token, Erc20.decimals) as? BigInteger }?.toInt()?.takeIf { it in 0..36 } ?: return null
        val named = symbol ?: orNull { reader.read1(token, Erc20.symbol) as? String }?.let { sanitizeLabel(it, 16) }?.takeIf { it.isNotEmpty() }
        return Facts(named, decimals, coin?.priceUsd, coin?.mcapUsd)
    }

    /** Takes a coin added by hand out of Portfolio. Its trades from this phone, if any, stay. */
    fun removeImport(chain: Chain, token: String) {
        val wallet = addressOn(chain) ?: return
        imports.remove(wallet, chain, token)
    }

    /**
     * A landed send of a coin to an address, kept with the coin's fills: its tokens count as taken
     * out at what they were worth (`usd`), so the position's result stays where it stood.
     */
    fun recordSend(wallet: String, chain: Chain, token: String, symbol: String?, decimals: Int, raw: BigInteger, tx: String, usd: Double?, feeUsd: Double?) {
        store.put(
            Fill(
                wallet = keyOf(chain, wallet),
                feed = chain.feed,
                token = keyOf(chain, token),
                symbol = symbol,
                side = Fill.SEND,
                tx = tx,
                at = System.currentTimeMillis(),
                raw = raw.toString(),
                decimals = decimals,
                usd = usd,
                feeUsd = feeUsd,
                exact = true,
            ),
        )
    }

    /** Prices of the coins of the wallet's positions and those added by hand, read while a screen shows them. */
    suspend fun livePrices() {
        while (true) {
            val coins = (addedCoins() + own().flatMap { store.coinsOf(it) }).distinct().take(30)
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
        val LOCKED: String get() = tr("This version of Nesty cannot trade this coin yet. Everything else on this page still works.")
        val NO_EVM: String get() = tr("This wallet was made from a Solana key: it has no EVM address.")
        val NO_SOL: String get() = tr("This wallet was made from an EVM key: it has no Solana address.")

        /** How long a read of a chain's own coin price stands. */
        private const val NATIVE_TTL_MS = 60_000L

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

        /**
         * Solana's tip to the block engine per trade transaction at each speed, on top of the
         * priority fee: 0.00001, 0.00005 and 0.0002 SOL.
         */
        fun tipLamports(speed: String): BigInteger = BigInteger.valueOf(
            when (speed) {
                "Normal" -> 10_000L
                "Turbo" -> 200_000L
                else -> 50_000L
            },
        )

        fun solPrefsOf(preset: Preset) = SolPrefs(
            slippageBps = slippageBps(preset),
            priorityLamports = priorityLamports(preset.speed),
            tipLamports = tipLamports(preset.speed),
        )

        /** A coin's address as a fill keeps it: EVM addresses lowercase, Solana ones as they are. */
        fun keyOf(chain: Chain, address: String): String = if (chain == Chain.SOLANA) address else address.lowercase()

        fun sameToken(chain: Chain, a: String, b: String): Boolean = if (chain == Chain.SOLANA) a == b else a.equals(b, ignoreCase = true)
    }
}
