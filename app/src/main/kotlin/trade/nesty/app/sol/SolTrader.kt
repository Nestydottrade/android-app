package trade.nesty.app.sol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import trade.nesty.app.evm.BlockedAction
import trade.nesty.app.evm.Payout
import trade.nesty.app.evm.Quote
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.StepKind
import trade.nesty.app.evm.StepStatus
import trade.nesty.app.evm.Trade
import trade.nesty.app.evm.TradeException
import trade.nesty.app.evm.TradeMath
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.TradeStep
import trade.nesty.app.evm.orNull
import trade.nesty.app.evm.sanitizeLabel
import trade.nesty.app.evm.shortErr
import trade.nesty.app.keys.Base58
import trade.nesty.app.sol.dex.BatchedReader
import trade.nesty.app.sol.dex.Dex
import trade.nesty.app.sol.dex.DexLoadCtx
import trade.nesty.app.sol.dex.DexModule
import trade.nesty.app.sol.dex.DexVenue
import trade.nesty.app.sol.dex.Orca
import trade.nesty.app.sol.dex.isDexQuote
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/** Signs a Solana transaction's message for one wallet. The key never leaves the vault. */
interface SolSigner {
    val address: String

    fun signMessage(message: ByteArray): ByteArray
}

/** A token's name, symbol and decimals, as its mint and metadata accounts say. */
data class SolTokenMeta(
    val name: String,
    val symbol: String,
    val decimals: Int,
    val tokenProgram: String,
    /** Supply when the mint was read (pump.fun coins are minted once, so it rarely moves). */
    val supply: BigInteger,
    /** Token-2022 extension types of the mint (none for SPL Token mints). */
    val extensions: List<Int> = emptyList(),
    /** The program its transfer hook runs, when one is set. */
    val hookProgram: String? = null,
    val uri: String? = null,
)

/** What a route pays with, priced. */
data class PayInfo(
    val asset: QuoteAsset,
    val symbol: String,
    val decimals: Int,
    /** Dollars per whole unit; null when unknown. */
    val usd: Double?,
    val stable: Boolean,
)

/** Every way a coin trades now, all in one pay currency, the first the most liquid one known. */
data class SolMarket(
    val routes: List<SolRoute>,
    /** Why it cannot be traded right now, when known. */
    val idle: String?,
    /**
     * When that pay currency is a token: its pools against SOL. The coin is then bought and sold
     * with SOL, through the token, in two transactions.
     */
    val funding: List<DexVenue>? = null,
)

/** The user's settings for one Solana trade. */
data class SolPrefs(
    val slippageBps: Int,
    /** The priority fee each transaction pays, in lamports. */
    val priorityLamports: BigInteger,
    /** The most that fee may cost, in dollars (the extension's default). */
    val maxGasUsd: Double = 10.0,
    /**
     * A tip to the block engine for a private send (MEV protection), in lamports, in each trade
     * transaction. Zero sends through the public RPCs, untipped.
     */
    val tipLamports: BigInteger = BigInteger.ZERO,
)

data class SolBuyRequest(
    val mint: String,
    /** Lamports, exactly what the user confirmed: no transaction of this buy may spend more. */
    val amount: BigInteger,
    val prefs: SolPrefs,
    val symbol: String? = null,
    /** Pools the server named for this coin; each is read and checked on chain. */
    val poolHints: List<String> = emptyList(),
)

data class SolSellRequest(
    val mint: String,
    /** Share of the wallet's balance, in basis points (10 000 = all). */
    val bps: Int,
    val prefs: SolPrefs,
    val symbol: String? = null,
    val poolHints: List<String> = emptyList(),
)

/**
 * The Solana trade engine for the active wallet, ported from the extension's
 * src/background/solana/service.ts: pump.fun's curve and PumpSwap (with routes through other
 * pump.fun coins), and the pools of the programs in ./dex. Validate → simulate → sign → send →
 * watch, for each transaction; nothing is signed unless the validator passed the exact
 * instructions, compute budget included, and every transaction is simulated first.
 *
 * Reads go through Nesty's /rpc/solana; sends go straight to the public Solana RPC.
 *
 * Left out of the extension's engine, on purpose: the pool index (pools come from the coin's
 * own address, Orca's fixed-fee pools and the pools the server names), several wallets at once,
 * and the extension's USD limits per transaction and per click (the app's limit is the amount the
 * user confirmed). A coin priced in a token with no pool against SOL cannot be bought here; it can
 * still be sold, for that token.
 */
class SolTrader(
    private val rpc: SolRpc,
    /** SOL in dollars when the app already knows it; else Orca's SOL/USDC pool is read. */
    private val knownSolUsd: () -> Double? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = CONFIRM_POLL_MS,
    /** A number below the bound: the extra compute units, and pump.fun's fee account. */
    private val random: (Int) -> Int = { Random.nextInt(it) },
    /** The coin's own trade landed: the app asks the server for its fill. */
    private val onLanded: suspend (Trade) -> Unit = {},
) {
    val price = SolPrice(rpc, now, scope)

    private class Timed<T>(val at: Long, val value: T)

    private class VenueResult(val venue: PumpVenue?, val idle: String?, val pump: Boolean? = null)

    private class RouteResult(val route: PumpRoute?, val idle: String?)

    /** A coin's canonical pool, once found: read with the coin from then on. */
    private class PoolKeys(val quote: QuoteAsset, val base: String, val quoteVault: String)

    private class Configs(
        val at: Long,
        val global: PumpGlobal,
        val pumpFees: FeeConfig,
        val ammGlobal: AmmGlobalConfig,
        val ammFees: FeeConfig,
    )

    private val reader = BatchedReader(rpc)

    @Volatile
    private var configs: Configs? = null

    @Volatile
    private var blockhashCache: Timed<SolRpc.Blockhash>? = null
    private val venues = ConcurrentHashMap<String, Timed<VenueResult>>()
    private val venueLoads = ConcurrentHashMap<String, Deferred<VenueResult>>()
    private val pools = ConcurrentHashMap<String, PoolKeys>()

    /** Token programs of quote mints (they never change). */
    private val quoteAssets = ConcurrentHashMap<String, QuoteAsset>()

    /** Mints that are no pump.fun coin: a quote chain ends at them. */
    private val notPump: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The mints of each coin's quote chain, to read them all at once next time. */
    private val chains = ConcurrentHashMap<String, List<String>>()

    /** Pools the server named, per coin: quoted with the ones found from the coin's address. */
    private val pagePools = ConcurrentHashMap<String, List<String>>()

    /** The program owning each pool named ("" when there is no such account). */
    private val poolOwners = ConcurrentHashMap<String, String>()
    private val markets = ConcurrentHashMap<String, Timed<SolMarket>>()
    private val marketLoads = ConcurrentHashMap<String, Deferred<SolMarket>>()

    /** Each pay token's pools against SOL, as read last. */
    private val legs = ConcurrentHashMap<String, Timed<List<DexVenue>>>()
    private val legLoads = ConcurrentHashMap<String, Deferred<List<DexVenue>>>()

    /** ...and which of its pools those are, ranked now and then (they rarely change). */
    private val legPools = ConcurrentHashMap<String, Timed<List<String>>>()
    private val usdPrices = ConcurrentHashMap<String, Timed<Double?>>()
    private val metas = ConcurrentHashMap<String, SolTokenMeta>()
    private val counter = AtomicLong()

    private fun <K, V> bounded(map: MutableMap<K, V>): MutableMap<K, V> {
        if (map.size > MAX_CACHE) map.clear()
        return map
    }

    /** One load per key at a time: callers asking meanwhile wait for the same one. */
    private suspend fun <V> ConcurrentHashMap<String, Deferred<V>>.once(key: String, load: suspend () -> V): V {
        val made = scope.async(start = CoroutineStart.LAZY) { load() }
        val running = putIfAbsent(key, made)
        if (running != null) {
            made.cancel()
            return running.await()
        }
        made.invokeOnCompletion { remove(key, made) }
        return made.await()
    }

    /** SOL in dollars: the app's, else from chain. */
    suspend fun solUsd(): Double? = knownSolUsd()?.takeIf { it > 0 } ?: price.usd()

    private suspend fun blockhash(): SolRpc.Blockhash {
        blockhashCache?.let { if (now() - it.at < BLOCKHASH_TTL_MS) return it.value }
        val value = rpc.getLatestBlockhash()
        blockhashCache = Timed(now(), value)
        return value
    }

    // ── token data ──────────────────────────────────────────────────────────────────

    suspend fun tokenMeta(mint: String): SolTokenMeta? = tokenMetas(listOf(mint)).firstOrNull()

    /** Names and decimals of many mints, reading the ones not known yet in one batch. */
    suspend fun tokenMetas(mints: List<String>): List<SolTokenMeta?> {
        val missing = mints.filter { !metas.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) {
            val accs = rpc.getMultipleAccounts(missing.flatMap { listOf(it, SolAddr.metadataAddress(it)) })
            missing.forEachIndexed { i, m ->
                // A missing account may still appear (a coin seconds old): only found ones are kept.
                parseMeta(accs.getOrNull(i * 2), accs.getOrNull(i * 2 + 1))?.let { bounded(metas)[m] = it }
            }
        }
        return mints.map { metas[it] }
    }

    /** The wallet's balance of `mint`, in its associated token account. */
    suspend fun tokenBalance(owner: String, mint: String, tokenProgram: String): BigInteger {
        val acc = rpc.getAccount(SolAddr.associatedTokenAddress(owner, mint, tokenProgram))
        return acc?.let { Layouts.decodeTokenAccount(it.data)?.amount } ?: BigInteger.ZERO
    }

    /** Pools the server named for `mint`: quoted too, after the ones its address gives. */
    fun notePools(mint: String, pools: List<String>) {
        for (pool in pools.reversed()) {
            if (!SolAddr.isAddress(pool)) continue
            val list = pagePools[mint] ?: emptyList()
            if (pool !in list) bounded(pagePools)[mint] = (listOf(pool) + list).take(MAX_PAGE_POOLS)
        }
    }

    // ── venues and routes ───────────────────────────────────────────────────────────

    /** The venue `mint` trades on now: its pump.fun curve, else its canonical PumpSwap pool. */
    private suspend fun venue(mint: String, maxAgeMs: Long = VENUE_TTL_MS): VenueResult {
        venues[mint]?.let { if (now() - it.at <= maxAgeMs) return it.value }
        return venueLoads.once(mint) {
            val result = loadVenue(mint)
            bounded(venues)[mint] = Timed(now(), result)
            if (result.pump == false) {
                if (notPump.size > MAX_CACHE * 5) notPump.clear()
                notPump += mint
            }
            result
        }
    }

    /**
     * How `mint` trades now on pump.fun: its own venue and, while it is quoted in another pump.fun
     * coin, that coin's venue, down to SOL or a token (the route pickRoute allows).
     */
    private suspend fun route(mint: String, maxAgeMs: Long = VENUE_TTL_MS): RouteResult {
        // A chain seen before is read all at once; a new one hop by hop.
        chains[mint]?.let { known -> coroutineScope { known.map { m -> async { orNull { venue(m, maxAgeMs) } } }.awaitAll() } }
        val first = venue(mint, maxAgeMs)
        val own = first.venue ?: return RouteResult(null, first.idle)
        val chain = mutableListOf(own)
        while (chain.size < Pump.MAX_HOPS) {
            val quote = chain.last().quote.mint
            if (quote == SolAddr.WSOL_MINT || quote in SolAddr.STABLE_MINTS || quote in notPump) break
            val next = venue(quote, maxAgeMs)
            val v = next.venue
            if (v == null) {
                // The coin it is paired with exists but cannot trade right now (mid-migration).
                if (next.idle != null) return RouteResult(null, next.idle)
                break
            }
            chain += v
        }
        bounded(chains)[mint] = chain.map { it.mint }
        val route = Pump.pickRoute(chain) ?: return RouteResult(null, null)
        return RouteResult(route, Pump.routeIdle(route))
    }

    /**
     * Every way `mint` trades now: its pump.fun route while it has one that trades, else its pools
     * on the other programs Nesty knows (in one pay currency, the first open pool's).
     */
    suspend fun market(mint: String, maxAgeMs: Long = VENUE_TTL_MS, hints: List<String> = emptyList()): SolMarket {
        notePools(mint, hints)
        var pumpIdle: String? = null
        if (mint !in notPump) {
            val r = route(mint, maxAgeMs)
            if (r.route != null && r.idle == null) return withFunding(SolMarket(listOf(SolRoute.OnPump(r.route)), null), maxAgeMs)
            pumpIdle = r.idle
        }
        val dex = dexMarket(mint, maxAgeMs)
        return if (dex.routes.isNotEmpty()) withFunding(dex, maxAgeMs) else SolMarket(emptyList(), pumpIdle ?: dex.idle)
    }

    /** A market priced in a token, with that token's pools against SOL when it has any. */
    private suspend fun withFunding(m: SolMarket, maxAgeMs: Long): SolMarket {
        val pay = m.routes.firstOrNull()?.let { SolRoutes.pay(it) } ?: return m
        if (pay.isSol) return m
        val found = orNull { fundingLegs(pay, maxOf(maxAgeMs, LEG_AGE_MS)) } ?: emptyList()
        return if (found.isNotEmpty()) m.copy(funding = found) else m
    }

    /** The pools that turn SOL into `pay` and back, the best first: Orca's fixed-fee ones. */
    suspend fun fundingLegs(pay: QuoteAsset, maxAgeMs: Long = VENUE_TTL_MS): List<DexVenue> {
        legs[pay.mint]?.let { if (now() - it.at <= maxAgeMs) return it.value }
        return legLoads.once(pay.mint) {
            val found = loadLegs(pay.mint)
            bounded(legs)[pay.mint] = Timed(now(), found)
            found
        }
    }

    private suspend fun loadLegs(mint: String): List<DexVenue> {
        val meta = tokenMeta(mint) ?: return emptyList()
        if (untradable(meta)) return emptyList()
        // These pools are only how SOL becomes the token: SOL is the one quote they may have.
        val ctx = DexLoadCtx(read = { reader.read(it) }, coinProgram = meta.tokenProgram, quoteAsset = { m -> if (m == SolAddr.WSOL_MINT) SOL_QUOTE else null })
        legPools[mint]?.let { if (now() - it.at < POOLS_TTL_MS) return openPools(ctx, it.value, mint) }
        // Ranked by what each pays for a SOL, so later reads load only the best few.
        val best = openPools(ctx, Orca.whirlpoolAddresses(mint, SolAddr.WSOL_MINT).distinct(), mint)
            .map { it to Dex.module(it).quote(it, Side.BUY, LEG_RANK_LAMPORTS) }
            .filter { it.second.signum() > 0 }
            .sortedByDescending { it.second }
            .take(MAX_FUNDING_LEGS)
            .map { it.first }
        bounded(legPools)[mint] = Timed(now(), best.map { it.pool })
        return best
    }

    /** The pools at `pools` that trade `mint` now, read in one batch. */
    private suspend fun openPools(ctx: DexLoadCtx, pools: List<String>, mint: String): List<DexVenue> {
        val nowSec = now() / 1000.0
        return loadPools(ctx, pools, mint).filter { Dex.module(it).idle(it, nowSec) == null }
    }

    private suspend fun loadPools(ctx: DexLoadCtx, pools: List<String>, mint: String): List<DexVenue> {
        val modules = poolModules(pools, ctx.read)
        return coroutineScope {
            pools.mapIndexed { i, pool -> async { modules[i]?.let { m -> orNull { m.load(ctx, pool, mint) } } } }.awaitAll()
        }.filterNotNull()
    }

    private suspend fun dexMarket(mint: String, maxAgeMs: Long): SolMarket {
        markets[mint]?.let { if (now() - it.at <= maxAgeMs) return it.value }
        return marketLoads.once(mint) {
            val result = loadDexMarket(mint)
            bounded(markets)[mint] = Timed(now(), result)
            result
        }
    }

    private suspend fun loadDexMarket(mint: String): SolMarket {
        val none = SolMarket(emptyList(), null)
        val meta = tokenMeta(mint) ?: return none
        val pools = dexCandidates(mint)
        if (pools.isEmpty()) return none
        if (untradable(meta)) return SolMarket(emptyList(), UNTRADABLE)
        val venues = loadPools(dexCtx(meta), pools, mint)
        val nowSec = now() / 1000.0
        val open = venues.filter { Dex.module(it).idle(it, nowSec) == null }
        val first = open.firstOrNull()
            ?: return venues.firstOrNull()?.let { SolMarket(emptyList(), Dex.module(it).idle(it, nowSec)) } ?: none
        // One pay currency, the first open pool's: a buy is priced, and paid, in it.
        return SolMarket(open.filter { it.quote.mint == first.quote.mint }.map { SolRoute.OnDex(it) }, null)
    }

    /** What reading a coin's pools needs: one batched reader, and its pay currencies. */
    private fun dexCtx(meta: SolTokenMeta) = DexLoadCtx(
        read = { reader.read(it) },
        coinProgram = meta.tokenProgram,
        quoteAsset = { m -> if (isDexQuote(m)) quoteAsset(m) else null },
    )

    /** The pools to quote `mint` on: the launch curves derived from its address, then the server's. */
    private fun dexCandidates(mint: String): List<String> =
        (Dex.MODULES.flatMap { it.candidates(mint) } + (pagePools[mint] ?: emptyList())).distinct()

    /** The module of each pool, from the program owning it (read once per pool). */
    private suspend fun poolModules(pools: List<String>, read: suspend (List<String>) -> List<SolAccount?>): List<DexModule<DexVenue>?> {
        val unknown = pools.filter { !poolOwners.containsKey(it) }
        if (unknown.isNotEmpty()) {
            val accs = read(unknown)
            if (poolOwners.size > MAX_CACHE * 5) poolOwners.clear()
            unknown.forEachIndexed { i, p -> poolOwners[p] = accs.getOrNull(i)?.owner ?: "" }
        }
        return pools.map { p -> poolOwners[p]?.takeIf { it.isNotEmpty() }?.let { Dex.moduleOf(it) } }
    }

    /** What `route` pays with, and its dollar price. */
    private suspend fun payInfo(route: SolRoute): PayInfo {
        val asset = SolRoutes.pay(route)
        if (asset.isSol) return solPay(solUsd())
        val meta = tokenMeta(asset.mint) ?: throw TradeException("rpc", "Could not read the token this coin is bought with.")
        val stable = asset.mint in SolAddr.STABLE_MINTS
        val usd = if (stable) 1.0 else tokenUsd(asset.mint)
        return PayInfo(asset, meta.symbol, meta.decimals, usd, stable)
    }

    /** A pay token's dollar price, from its own pump.fun route when it is a pump.fun coin. */
    private suspend fun tokenUsd(mint: String, depth: Int = 0): Double? {
        if (mint in SolAddr.STABLE_MINTS) return 1.0
        if (mint == SolAddr.WSOL_MINT) return solUsd()
        usdPrices[mint]?.let { if (now() - it.at < PRICE_TTL_MS) return it.value }
        var usd: Double? = null
        val route = if (depth < Pump.MAX_HOPS && mint !in notPump) orNull { route(mint, 10_000) }?.route else null
        if (route != null) {
            val found = tokenMetas(listOf(mint, route.pay.mint))
            val meta = found[0]
            val payMeta = found[1]
            val payUsd = tokenUsd(route.pay.mint, depth + 1)
            if (meta != null && payMeta != null && payUsd != null) {
                usd = Pump.routeSpotPrice(route) * 10.0.pow(meta.decimals - payMeta.decimals) * payUsd
            }
        }
        bounded(usdPrices)[mint] = Timed(now(), usd)
        return usd
    }

    /** "pump.fun", "PumpSwap", for a route through other coins "pump.fun via WIF", or the pool's program. */
    private suspend fun routeLabel(r: SolRoute): String = when (r) {
        is SolRoute.OnDex -> Dex.module(r.venue).label(r.venue)
        is SolRoute.OnPump -> {
            val coin = Pump.routeCoin(r.pump)
            if (r.pump.hops.size == 1) {
                Pump.venueLabel(coin)
            } else {
                val found = orNull { tokenMetas(r.pump.hops.dropLast(1).map { it.mint }) } ?: emptyList()
                "${Pump.venueLabel(coin)} via ${found.joinToString(" → ") { it?.symbol ?: "?" }}"
            }
        }
    }

    private suspend fun loadVenue(mint: String): VenueResult {
        val curveAddr = Pump.bondingCurveAddress(mint)
        val known = pools[mint]
        // A pool's address depends on the coin's quote, which only its curve tells: SOL until known.
        val poolAddr = Pump.canonicalPoolAddress(mint, (known?.quote ?: SOL_QUOTE).mint)
        val cached = configs
        val fresh = cached != null && now() - cached.at < CONFIG_TTL_MS
        val keys = buildList {
            add(mint)
            add(curveAddr)
            add(poolAddr)
            add(SolAddr.associatedTokenAddress(curveAddr, mint, SolAddr.TOKEN_PROGRAM))
            add(SolAddr.associatedTokenAddress(curveAddr, mint, SolAddr.TOKEN_2022_PROGRAM))
            if (known != null) {
                add(known.base)
                add(known.quoteVault)
            }
            if (!fresh) addAll(listOf(Pump.PUMP_GLOBAL, Pump.PUMP_FEE_CONFIG, Pump.AMM_GLOBAL_CONFIG, Pump.AMM_FEE_CONFIG))
        }
        val accs = rpc.getMultipleAccounts(keys)
        val mintAcc = accs.getOrNull(0)
        val curveAcc = accs.getOrNull(1)
        val poolAcc = accs.getOrNull(2)
        val ataSpl = accs.getOrNull(3)
        val ata2022 = accs.getOrNull(4)
        var at = 5
        var vaultAccs: List<SolAccount?>? = if (known != null) listOf(accs.getOrNull(at++), accs.getOrNull(at++)) else null
        val cfg = if (fresh) {
            cached
        } else {
            val g = accs.getOrNull(at)
            val pf = accs.getOrNull(at + 1)
            val ag = accs.getOrNull(at + 2)
            val af = accs.getOrNull(at + 3)
            val global = if (g?.owner == Pump.PUMP_PROGRAM) Pump.decodeGlobal(g.data) else null
            val pumpFees = pf?.let { Pump.decodeFeeConfig(it.data) }
            val ammGlobal = if (ag?.owner == Pump.PUMP_AMM_PROGRAM) Pump.decodeAmmGlobalConfig(ag.data) else null
            val ammFees = af?.let { Pump.decodeFeeConfig(it.data) }
            if (global == null || pumpFees == null || ammGlobal == null || ammFees == null) {
                throw TradeException("rpc", "Could not read pump.fun settings.")
            }
            Configs(now(), global, pumpFees, ammGlobal, ammFees).also { configs = it }
        }

        if (mintAcc == null || !SolAddr.isTokenProgram(mintAcc.owner)) return VenueResult(null, null)
        val mintInfo = Layouts.decodeMint(mintAcc.data)
        if (mintInfo == null || !mintInfo.initialized) return VenueResult(null, null)
        val tokenProgram = mintAcc.owner

        val curve = if (curveAcc?.owner == Pump.PUMP_PROGRAM) Pump.decodeBondingCurve(curveAcc.data) else null
        val quoteMint = curve?.let { Pump.curveQuoteMint(it) } ?: known?.quote?.mint ?: SolAddr.WSOL_MINT
        val quote = quoteAsset(quoteMint) ?: return VenueResult(null, null, pump = curve != null)
        if (curve != null && Pump.curveIdle(curve) == null) {
            val ata = if (tokenProgram == SolAddr.TOKEN_PROGRAM) ataSpl else ata2022
            val venue = CurveVenue(
                mint = mint,
                tokenProgram = tokenProgram,
                quote = quote,
                curve = curve,
                curveTokenBalance = ata?.let { Layouts.decodeTokenAccount(it.data)?.amount } ?: BigInteger.ZERO,
                global = cfg.global,
                feeConfig = cfg.pumpFees,
                mintSupply = mintInfo.supply,
            )
            return VenueResult(venue, null, pump = true)
        }

        // Migrated (or migrating): the canonical pool in the curve's quote.
        val poolAt = Pump.canonicalPoolAddress(mint, quote.mint)
        val poolInfo = if (poolAt == poolAddr) poolAcc else rpc.getAccount(poolAt)
        val pool = if (poolInfo?.owner == Pump.PUMP_AMM_PROGRAM) Pump.decodePool(poolInfo.data) else null
        if (pool != null && pool.baseMint == mint && pool.quoteMint == quote.mint) {
            val found = PoolKeys(quote, pool.poolBaseTokenAccount, pool.poolQuoteTokenAccount)
            if (vaultAccs == null || known?.base != found.base || known.quoteVault != found.quoteVault) {
                bounded(pools)[mint] = found
                vaultAccs = rpc.getMultipleAccounts(listOf(found.base, found.quoteVault))
            }
            val base = vaultAccs.getOrNull(0)?.let { Layouts.decodeTokenAccount(it.data) }
            val quoteVault = vaultAccs.getOrNull(1)?.let { Layouts.decodeTokenAccount(it.data) }
            if (base == null || quoteVault == null) return VenueResult(null, null, pump = true)
            val venue = PoolVenue(
                mint = mint,
                tokenProgram = tokenProgram,
                quote = quote,
                poolAddress = poolAt,
                pool = pool,
                baseReserve = base.amount,
                quoteReserve = quoteVault.amount,
                globalConfig = cfg.ammGlobal,
                feeConfig = cfg.ammFees,
                mintSupply = mintInfo.supply,
            )
            return VenueResult(venue, null, pump = true)
        }
        if (curve != null) return VenueResult(null, CURVE_COMPLETE, pump = true)
        // Neither a curve nor a pool: not a pump.fun coin.
        return VenueResult(null, null, pump = false)
    }

    /** A quote mint with its token program, read once (it never changes). */
    private suspend fun quoteAsset(mint: String): QuoteAsset? {
        if (mint == SolAddr.WSOL_MINT) return SOL_QUOTE
        quoteAssets[mint]?.let { return it }
        val program = metas[mint]?.tokenProgram ?: rpc.getAccount(mint)?.owner
        if (program == null || !SolAddr.isTokenProgram(program)) return null
        return QuoteAsset(mint, program).also { bounded(quoteAssets)[mint] = it }
    }

    // ── quotes ──────────────────────────────────────────────────────────────────────

    /**
     * The best route's output for each amount (lamports for a buy, the coin for a sell), with its
     * price impact against a reference a hundred times smaller, as the extension's panel quotes. A
     * coin priced in a token is quoted through the token's pool against SOL.
     */
    suspend fun quotes(mint: String, side: Side, amounts: List<BigInteger>, hints: List<String> = emptyList()): List<Quote?> {
        if (amounts.isEmpty()) return emptyList()
        val none = amounts.map { null }
        val m = market(mint, VENUE_TTL_MS, hints)
        val route = m.routes.firstOrNull() ?: return none
        tokenMeta(mint) ?: return none
        val funding = m.funding
        // A buy pays SOL: without a pool from SOL into its pay token, a token-priced coin is not bought here.
        if (side == Side.BUY && funding == null && !SolRoutes.pay(route).isSol) return none
        val labels = m.routes.map { routeLabel(it) }
        val paySymbol = if (funding != null) payInfo(route).symbol else null
        fun labelOf(r: SolRoute): String {
            val label = labels.getOrNull(m.routes.indexOf(r)) ?: ""
            return if (paySymbol != null) viaLabel(label, paySymbol) else label
        }
        fun best(amount: BigInteger): Best<SolRoute>? = if (funding != null) {
            SolRoutes.fundedBest(m.routes, funding, side, amount)?.let { Best(it.route, it.out) }
        } else {
            SolRoutes.bestRoute(m.routes, side, amount)
        }
        val smallest = amounts.filter { it.signum() > 0 }.minOrNull()
        // A thousandth of a SOL when no amount is set.
        val base = smallest ?: if (side == Side.BUY) BigInteger.TEN.pow(6) else return none
        val ref = (base / HUNDRED).takeIf { it.signum() > 0 } ?: BigInteger.ONE
        val refOut = best(ref)?.out ?: BigInteger.ZERO
        return amounts.map { a ->
            if (a.signum() <= 0) return@map null
            val q = best(a) ?: return@map null
            val impact = if (refOut.signum() > 0) TradeMath.priceImpactBps(a, q.out, ref, refOut) else null
            Quote(a, q.out, impact, labelOf(q.route))
        }
    }

    /**
     * What selling `amount` of `mint` pays now, in dollars, price impact and pool fees included:
     * in SOL through its pools, or in the token it is priced in when no pool turns that into SOL.
     * Null when nothing sells it now. Exit plans measure with it.
     */
    suspend fun sellValueUsd(mint: String, amount: BigInteger, hints: List<String> = emptyList()): Double? {
        if (amount.signum() <= 0) return null
        val m = market(mint, VENUE_TTL_MS, hints)
        if (m.routes.isEmpty() || m.idle != null) return null
        val funding = m.funding
        if (funding != null) {
            val out = SolRoutes.fundedBest(m.routes, funding, Side.SELL, amount)?.out ?: return null
            return solUsd()?.let { BigDecimal(out).movePointLeft(SOL_DECIMALS).toDouble() * it }
        }
        val best = SolRoutes.bestRoute(m.routes, Side.SELL, amount) ?: return null
        val pay = payInfo(best.route)
        val usd = pay.usd ?: return null
        return BigDecimal(best.out).movePointLeft(pay.decimals).toDouble() * usd
    }

    /** What `owner` holds of `mint`; null when the coin cannot be read. */
    suspend fun heldOf(owner: String, mint: String): BigInteger? {
        val meta = tokenMeta(mint) ?: return null
        return tokenBalance(owner, mint, meta.tokenProgram)
    }

    /** Why `mint` cannot be bought right now, when more can be said than that nothing quotes it. */
    suspend fun whyNone(mint: String, hints: List<String> = emptyList()): String? {
        val m = orNull { market(mint, VENUE_TTL_MS, hints) } ?: return null
        m.idle?.let { return it }
        val route = m.routes.firstOrNull() ?: return null
        val pay = SolRoutes.pay(route)
        if (m.funding == null && !pay.isSol) return noSolPool(orNull { tokenMeta(pay.mint) }?.symbol)
        return SolRoutes.noFill(route)
    }

    /** The network fee of a buy now: each transaction's signature and priority fee. */
    suspend fun buyFeeLamports(mint: String, prefs: SolPrefs, hints: List<String> = emptyList()): BigInteger? {
        val m = orNull { market(mint, VENUE_TTL_MS, hints) } ?: return null
        if (m.routes.isEmpty()) return null
        val txs = if (m.funding != null) 2L else 1L
        return (prefs.priorityLamports + SIGNATURE_LAMPORTS + prefs.tipLamports) * BigInteger.valueOf(txs)
    }

    // ── buy ─────────────────────────────────────────────────────────────────────────

    /** Starts a buy with SOL and returns its live state. Throws when it cannot start. */
    suspend fun buy(req: SolBuyRequest, wallet: SolSigner): StateFlow<Trade> {
        if (req.amount.signum() <= 0) throw TradeException("insufficient_funds", "Amount too small.")
        val m = market(req.mint, MAX_QUOTE_AGE_MS, req.poolHints)
        val primary = m.routes.firstOrNull() ?: throw TradeException("no_route", m.idle ?: NO_ROUTE)
        m.idle?.let { throw TradeException("no_route", it) }
        val funding = m.funding
        val pay = payInfo(primary)
        if (funding == null && !pay.asset.isSol) throw TradeException("no_route", noSolPool(pay.symbol))
        val solUsd = solUsd() ?: throw TradeException("limit_exceeded", noPrice("SOL"))
        // Through its token: the SOL pool that pays most for the amount, then the coin's route.
        val best = (
            if (funding != null) {
                SolRoutes.fundedBest(m.routes, funding, Side.BUY, req.amount)?.let { Best(it.route, it.out) }
            } else {
                SolRoutes.bestRoute(m.routes, Side.BUY, req.amount)
            }
            ) ?: throw TradeException("no_route", SolRoutes.noFill(primary) ?: NO_ROUTE)
        val build = SolRoutes.tradeBuilder(best.route, random) ?: throw TradeException("no_route", FEE_ACCOUNTS)
        val meta = tokenMeta(req.mint) ?: throw TradeException("no_route", NO_ROUTE)
        val label = routeLabel(best.route)
        val t = newTicket(
            Side.BUY,
            wallet.address,
            req.mint,
            req.symbol ?: meta.symbol,
            if (funding != null) viaLabel(label, pay.symbol) else label,
            TradeStep(StepKind.SWAP, amountIn = req.amount),
            null,
            meta.decimals,
        )
        val maxPriority = maxPriority(req.prefs, solUsd)
        if (funding != null) {
            // Each wallet's SOL becomes the token first, in a transaction of its own.
            val stepAt = t.add(TradeStep(StepKind.FUND, amountIn = req.amount))
            scope.launch { fundedBuy(t, 0, stepAt, FundedBuy(wallet, req.mint, pay, solPay(solUsd), req.amount, req.prefs, maxPriority)) }
        } else {
            val least = minAfter(best.out, req.prefs.slippageBps)
            t.set(0) { it.copy(minOut = least) }
            val ixs = build.build(Side.BUY, wallet.address, req.amount, least)
            scope.launch {
                trade(t, 0, TradeJob(wallet, req.mint, Side.BUY, best.route, req.amount, pay, ixs, req.prefs, req.amount, maxPriority, spend = ixs.spend, landed = true))
            }
        }
        return t.flow
    }

    private class FundedBuy(
        val wallet: SolSigner,
        val mint: String,
        /** The token the coin is priced in, and SOL. */
        val pay: PayInfo,
        val sol: PayInfo,
        /** Lamports. */
        val amount: BigInteger,
        val prefs: SolPrefs,
        val maxPriority: BigInteger,
    )

    /**
     * A buy, with SOL, of a coin priced in a token: SOL into the token first, then (once that has
     * landed, so the exact amount is known) the coin with exactly that.
     */
    private suspend fun fundedBuy(t: Ticket, i: Int, stepAt: Int, p: FundedBuy) {
        val sym = p.pay.symbol
        fun fail(error: String, status: StepStatus = StepStatus.FAILED) = t.set(i) { it.copy(status = status, error = error) }
        try {
            // 1) SOL into the token, on its pool that pays most for it now.
            val found = orNull { fundingLegs(p.pay.asset) } ?: emptyList()
            val into = SolRoutes.bestLeg(found, Side.BUY, p.amount)
            val leg = into?.let { SolRoute.OnDex(it.route) }
            val toToken = leg?.let { SolRoutes.tradeBuilder(it, random) }
            if (into == null || leg == null || toToken == null) {
                t.set(stepAt) { it.copy(status = StepStatus.FAILED, error = "No $sym quote") }
                return fail("Could not get $sym")
            }
            val swap = toToken.build(Side.BUY, p.wallet.address, p.amount, minAfter(into.out, FUNDING_SLIPPAGE_BPS))
            val arrived = CompletableDeferred<BigInteger?>()
            trade(
                t,
                stepAt,
                TradeJob(
                    p.wallet, p.pay.asset.mint, Side.BUY, leg, p.amount, p.sol, swap, p.prefs, p.amount, p.maxPriority,
                    // The coin's buy after it pays its network fee in SOL too.
                    spend = swap.spend + p.prefs.priorityLamports,
                    settled = { tx -> arrived.complete(tx?.let { SolRoutes.tokenDelta(it, p.wallet.address, p.pay.asset.mint) }) },
                ),
            )
            val got = arrived.await()
            if (got == null || got.signum() <= 0) {
                // What stopped the step stops the buy: a wallet short of SOL is skipped, as any buy is.
                val step = t.step(stepAt)
                if (step.status == StepStatus.SKIPPED || step.status == StepStatus.BLOCKED) return fail(step.error ?: "Could not get $sym", step.status)
                return fail(
                    when (step.status) {
                        StepStatus.CONFIRMED -> "Could not read the $sym that arrived: it stays in the wallet."
                        StepStatus.SUBMITTED -> "The $sym swap has not confirmed, so nothing was bought with it."
                        else -> step.error ?: "Could not get $sym"
                    },
                )
            }

            // 2) The coin, with exactly what arrived, on the route that pays most for it now.
            val routes = orNull { market(p.mint) }?.routes ?: emptyList()
            val priced = routes.filter { SolRoutes.pay(it).mint == p.pay.asset.mint }
            val coin = SolRoutes.bestRoute(priced, Side.BUY, got)
            val buy = coin?.let { SolRoutes.tradeBuilder(it.route, random) }
            if (coin == null || buy == null) return fail("No route for this coin right now: the $sym stays in the wallet.")
            val least = minAfter(coin.out, p.prefs.slippageBps)
            t.set(i) { it.copy(minOut = least) }
            val ixs = buy.build(Side.BUY, p.wallet.address, got, least)
            // The SOL step was held to the amount; this spends exactly what that step bought.
            trade(t, i, TradeJob(p.wallet, p.mint, Side.BUY, coin.route, got, p.pay, ixs, p.prefs, got, p.maxPriority, spend = ixs.spend, landed = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(shortErr(e))
        }
    }

    // ── sell ────────────────────────────────────────────────────────────────────────

    /** Starts a sell of a share of the wallet's balance and returns its live state. */
    suspend fun sell(req: SolSellRequest, wallet: SolSigner): StateFlow<Trade> {
        val meta = tokenMeta(req.mint) ?: throw TradeException("no_route", NO_ROUTE)
        val held = tokenBalance(wallet.address, req.mint, meta.tokenProgram)
        val amount = held * BigInteger.valueOf(req.bps.coerceIn(0, 10_000).toLong()) / TEN_K
        if (amount.signum() == 0) throw TradeException("insufficient_funds", "No tokens to sell.")
        val m = market(req.mint, MAX_QUOTE_AGE_MS, req.poolHints)
        if (m.routes.isEmpty()) throw TradeException("no_route", m.idle ?: NO_ROUTE)
        m.idle?.let { throw TradeException("no_route", it) }
        val funding = m.funding
        // Through its token, when that has pools against SOL: the coin's own sell, then the token
        // it paid back to SOL.
        val funded = funding?.let { SolRoutes.fundedBest(m.routes, it, Side.SELL, amount) }
        val best = (if (funded != null) Best(funded.route, funded.mid) else SolRoutes.bestRoute(m.routes, Side.SELL, amount))
        if (best == null || (funding != null && funded == null)) throw TradeException("no_route", NO_ROUTE)
        val pay = payInfo(best.route)
        val solUsd = solUsd() ?: throw TradeException("limit_exceeded", "No USD price for SOL, so the network fee cannot be checked against your limit.")
        val build = SolRoutes.tradeBuilder(best.route, random) ?: throw TradeException("no_route", FEE_ACCOUNTS)
        val label = routeLabel(best.route)
        // Paid out in the token itself when no pool turns it into SOL.
        val payout = if (funding == null && !pay.asset.isSol) Payout(pay.symbol, pay.decimals, pay.usd) else null
        val t = newTicket(
            Side.SELL,
            wallet.address,
            req.mint,
            req.symbol ?: meta.symbol,
            if (funding != null) viaLabel(label, pay.symbol) else label,
            TradeStep(StepKind.SWAP, amountIn = amount),
            payout,
            meta.decimals,
        )
        val stepAt = if (funding != null) t.add(TradeStep(StepKind.FUND)) else null
        val maxPriority = maxPriority(req.prefs, solUsd)
        val least = minAfter(best.out, req.prefs.slippageBps)
        // Through its token the swap pays that token, then SOL arrives: its least is not in SOL.
        if (stepAt == null) t.set(0) { it.copy(minOut = least) }
        val ixs = build.build(Side.SELL, wallet.address, amount, least)
        val unfund: ((TxMeta?) -> Unit)? = if (stepAt == null) {
            null
        } else {
            { tx: TxMeta? ->
                val got = tx?.let { SolRoutes.tokenDelta(it, wallet.address, pay.asset.mint) }
                scope.launch { unfund(t, 0, stepAt, Unfund(wallet, pay, solPay(solUsd), req.prefs, maxPriority), got) }
            }
        }
        scope.launch {
            trade(
                t,
                0,
                TradeJob(
                    wallet, req.mint, Side.SELL, best.route, amount, pay, ixs, req.prefs, BigInteger.ZERO, maxPriority,
                    // The token it pays is sold on for SOL; that SOL is what the sell got.
                    record = stepAt == null,
                    landed = stepAt == null,
                    settled = unfund,
                ),
            )
        }
        return t.flow
    }

    private class Unfund(val wallet: SolSigner, val pay: PayInfo, val sol: PayInfo, val prefs: SolPrefs, val maxPriority: BigInteger)

    /**
     * After a sell of a coin priced in a token: exactly the token it paid, back to SOL on the
     * token's pool that pays most for it, as a step of the same trade.
     */
    private suspend fun unfund(t: Ticket, i: Int, stepAt: Int, p: Unfund, got: BigInteger?) {
        val sym = p.pay.symbol
        fun stop(error: String, status: StepStatus) = t.set(stepAt) { it.copy(status = status, error = error) }
        val leg = t.step(i)
        if (leg.status == StepStatus.SUBMITTED) return stop("Not confirmed yet: any $sym it pays stays in the wallet.", StepStatus.SKIPPED)
        if (leg.status != StepStatus.CONFIRMED) return stop("The sell did not go through.", StepStatus.SKIPPED)
        // The coin's sell landed: it is recorded once what it got back is known, or cannot be.
        var record = true
        try {
            if (got == null || got.signum() <= 0) return stop("Could not read the $sym it paid: that stays in the wallet.", StepStatus.FAILED)
            t.set(stepAt) { it.copy(amountIn = got) }
            val found = orNull { fundingLegs(p.pay.asset) } ?: emptyList()
            val back = SolRoutes.bestLeg(found, Side.SELL, got)
            val leg2 = back?.let { SolRoute.OnDex(it.route) }
            val toSol = leg2?.let { SolRoutes.tradeBuilder(it, random) }
            if (back == null || leg2 == null || toSol == null) return stop("No route from $sym to SOL right now: it stays in the wallet.", StepStatus.FAILED)
            val ixs = toSol.build(Side.SELL, p.wallet.address, got, minAfter(back.out, FUNDING_SLIPPAGE_BPS))
            record = false
            trade(
                t,
                stepAt,
                TradeJob(
                    p.wallet, p.pay.asset.mint, Side.SELL, leg2, got, p.sol, ixs, p.prefs, BigInteger.ZERO, p.maxPriority,
                    settled = { tx ->
                        val out = tx?.let { SolRoutes.tradeDelivered(it, p.wallet.address, Side.SELL, p.pay.asset.mint, SOL_QUOTE) } ?: BigInteger.ZERO
                        if (out.signum() > 0) t.set(i) { it.copy(amountOut = out) }
                        scope.launch { orNull { onLanded(t.snapshot()) } }
                    },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stop(shortErr(e), StepStatus.FAILED)
        } finally {
            if (record) orNull { onLanded(t.snapshot()) }
        }
    }

    // ── one transaction ─────────────────────────────────────────────────────────────

    /** One wallet's buy or sell, on `route`. */
    private class TradeJob(
        val wallet: SolSigner,
        /** The coin the trade is for: the route must end in it. */
        val mint: String,
        val side: Side,
        val route: SolRoute,
        /** What the side spends: the pay currency for a buy, the coin for a sell. */
        val amount: BigInteger,
        val pay: PayInfo,
        val ixs: TradeIxs,
        val prefs: SolPrefs,
        val maxSpend: BigInteger,
        val maxPriority: BigInteger,
        /** What a buy needs in the pay currency, besides fees: checked against the wallet first. */
        val spend: BigInteger = BigInteger.ZERO,
        /** False when what it delivers is not what the trade shows: a funded sell's token. */
        val record: Boolean = true,
        /** The coin's own trade: the app records its fill. */
        val landed: Boolean = false,
        val settled: ((TxMeta?) -> Unit)? = null,
    )

    private suspend fun trade(t: Ticket, i: Int, p: TradeJob) {
        val ctx = SolValidationContext(
            wallet = p.wallet.address,
            mint = p.mint,
            route = p.route,
            side = p.side,
            maxSpend = p.maxSpend,
            maxPriorityLamports = p.maxPriority,
            payUnit = PayUnit(p.pay.symbol, p.pay.decimals),
            maxTipLamports = p.prefs.tipLamports,
        )
        runLeg(
            t,
            i,
            LegJob(
                wallet = p.wallet,
                instructions = p.ixs.instructions,
                units = SolRoutes.tradeUnits(p.route, p.side, p.amount),
                prefs = p.prefs,
                validate = { ixs -> SolValidator.validateTrade(ixs, ctx) },
                shortfall = if (p.side == Side.BUY) ({ fee -> buyShortfall(p.wallet.address, p.pay, p.spend, fee) }) else null,
                delivered = if (p.record) ({ meta -> SolRoutes.tradeDelivered(meta, p.wallet.address, p.side, p.mint, p.pay.asset) }) else null,
                landed = p.landed,
                onSettled = p.settled,
            ),
        )
    }

    /** One wallet's transaction, for runLeg. */
    private class LegJob(
        val wallet: SolSigner,
        val instructions: List<Instruction>,
        /** Compute units to ask for, before the simulation measures them. */
        val units: Int,
        val prefs: SolPrefs,
        /** Runs on the exact instructions, compute budget included, before every signature. */
        val validate: (List<Instruction>) -> SolValidationResult,
        /** Why the wallet cannot pay for it, read alongside the blockhash; null when it can. */
        val shortfall: (suspend (BigInteger) -> String?)? = null,
        /** What the confirmed transaction delivered, in raw units, from its balances. */
        val delivered: ((TxMeta) -> BigInteger)? = null,
        val landed: Boolean = false,
        private val onSettled: ((TxMeta?) -> Unit)? = null,
    ) {
        private val done = AtomicBoolean(false)

        /**
         * Runs once, when it is settled: with its balances once confirmed and read, else with null
         * (blocked, skipped, failed, expired, or its balances never came).
         */
        fun settled(meta: TxMeta?) {
            if (done.compareAndSet(false, true)) onSettled?.invoke(meta)
        }
    }

    /** What one Solana transaction came to before it was watched. */
    private sealed interface LegSubmit {
        class Blocked(val actions: List<BlockedAction>) : LegSubmit
        class Skipped(val reason: String) : LegSubmit
        class Failed(val error: String) : LegSubmit
        class Out(val sent: Sent) : LegSubmit
    }

    /**
     * Validate → simulate → sign → send → confirm, for one wallet's transaction. Nothing is signed
     * unless `job.validate` passes on the exact instructions.
     */
    private suspend fun runLeg(t: Ticket, i: Int, job: LegJob) {
        val s = submitLeg(job.wallet, job.instructions, job.units, job.prefs, job.validate, job.shortfall) { sig ->
            t.set(i) { it.copy(status = StepStatus.SENDING, hash = sig) }
        }
        when (s) {
            is LegSubmit.Blocked -> {
                t.block(i, s.actions)
                job.settled(null)
            }
            is LegSubmit.Skipped -> {
                t.set(i) { it.copy(status = StepStatus.SKIPPED, error = s.reason) }
                job.settled(null)
            }
            is LegSubmit.Failed -> {
                t.fail(i, s.error)
                job.settled(null)
            }
            is LegSubmit.Out -> {
                t.set(i) { it.copy(status = StepStatus.SUBMITTED) }
                scope.launch { confirm(t, i, s.sent, job) }
            }
        }
    }

    /** Validate → simulate → sign → send for one transaction. Nothing is signed unless `validate` passes. */
    private suspend fun submitLeg(
        wallet: SolSigner,
        instructions: List<Instruction>,
        baseUnits: Int,
        prefs: SolPrefs,
        validate: (List<Instruction>) -> SolValidationResult,
        shortfall: (suspend (BigInteger) -> String?)?,
        onSending: (String) -> Unit,
    ): LegSubmit {
        try {
            val fee = prefs.priorityLamports
            // A few random units make every transaction unique: an identical second click in the
            // same blockhash window would otherwise have the same signature and be dropped.
            var units = minOf(Spl.MAX_COMPUTE_UNITS, baseUnits + random(256))
            // A private send tips the block engine, with the marker against sandwiches when the
            // transaction still fits with it (a two-hop route is near the size limit).
            val tip = prefs.tipLamports.takeIf { it.signum() > 0 }
            val tipTo = Jito.TIP_ACCOUNTS[random(Jito.TIP_ACCOUNTS.size)]
            var marked = tip != null
            fun withBudget(u: Int) = listOf(Spl.setComputeUnitLimit(u), Spl.setComputeUnitPrice(microLamports(fee, u))) + instructions +
                listOfNotNull(tip?.let { Jito.tip(wallet.address, it, tipTo, marked) })
            if (marked && !fits(wallet.address, withBudget(Spl.MAX_COMPUTE_UNITS))) marked = false
            var ixs = withBudget(units)
            val verdict = validate(ixs)
            if (!verdict.ok) return LegSubmit.Blocked(verdict.actions)

            val (bh, short) = coroutineScope {
                val b = async { blockhash() }
                val s = async { shortfall?.invoke(fee + (tip ?: BigInteger.ZERO)) }
                b.await() to s.await()
            }
            if (short != null) return LegSubmit.Skipped(short)

            // Always simulated first: on its error nothing is signed.
            val sim = rpc.simulate(SolTx.serializeTransaction(SolTx.compileMessage(wallet.address, ixs, bh.blockhash), emptyList()))
            if (sim.err != null) return LegSubmit.Failed(SolErrors.explain(sim.err, ixs))
            val used = sim.unitsConsumed
            if (used != null && used > 0) {
                units = minOf(Spl.MAX_COMPUTE_UNITS, maxOf(30_000, ceil(used * 1.2).toInt()) + random(256))
                ixs = withBudget(units)
                // Same instructions, new budget: checked again all the same.
                if (!validate(ixs).ok) throw IllegalStateException("Blocked after simulation")
            }

            val message = SolTx.compileMessage(wallet.address, ixs, bh.blockhash)
            val signature = wallet.signMessage(message.bytes)
            val raw = SolTx.serializeTransaction(message, listOf(signature))
            val sig = Base58.encode(signature)
            onSending(sig)
            rpc.broadcast(raw, privately = tip != null)
            return LegSubmit.Out(Sent(raw, sig, ixs, bh.lastValidBlockHeight, privately = tip != null, tip = tip))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LegSubmit.Failed(shortErr(e))
        }
    }

    // ── plain transactions: a bridge deposit, a send ─────────────────────────────────

    /** What a plain Solana transaction came to: sent with its signature, or refused (nothing was signed). */
    sealed interface Plain {
        class Out(val sig: String, internal val sent: Any) : Plain
        data class Refused(val reason: String, val blocked: List<BlockedAction>? = null) : Plain
    }

    /** How a sent plain transaction ended: landed with its fee, or not, and why. */
    sealed interface PlainEnd {
        data class Landed(val feeLamports: BigInteger?) : PlainEnd
        data class Failed(val reason: String, val unknown: Boolean = false) : PlainEnd
    }

    /**
     * One plain transaction from `wallet` on the same path as a trade's: the exact instructions,
     * compute budget included, pass `validate` before every signature; it is simulated first.
     * Never tipped, whatever `prefs` say: a send or a deposit moves no price, so there is nothing
     * to front-run. It goes through the public RPCs, as the extension's sends do, and the
     * validators of sends and deposits take no tip.
     */
    suspend fun sendPlain(
        wallet: SolSigner,
        instructions: List<Instruction>,
        units: Int,
        prefs: SolPrefs,
        validate: (List<Instruction>) -> SolValidationResult,
        shortfall: (suspend (BigInteger) -> String?)? = null,
        /** The signature, right before the transaction is sent: a caller keeps it in case the app closes. */
        onSigned: (String) -> Unit = {},
    ): Plain = when (val s = submitLeg(wallet, instructions, units, prefs.copy(tipLamports = BigInteger.ZERO), validate, shortfall, onSigned)) {
        is LegSubmit.Blocked -> Plain.Refused(s.actions.firstOrNull { !it.ok }?.reason ?: "Blocked", s.actions)
        is LegSubmit.Skipped -> Plain.Refused(s.reason)
        is LegSubmit.Failed -> Plain.Refused(s.error)
        is LegSubmit.Out -> Plain.Out(s.sent.sig, s.sent)
    }

    /** Waits for a plain transaction as for a trade's: sent again until it lands or expires. */
    suspend fun landed(out: Plain.Out): PlainEnd {
        val sent = out.sent as Sent
        return when (val end = awaitSig(sent) {}) {
            is SigEnd.Landed -> if (end.err != null) {
                PlainEnd.Failed(SolErrors.explain(end.err, sent.ixs))
            } else {
                var fee: BigInteger? = null
                for (attempt in 0 until FILL_TRIES) {
                    val meta = orNull { rpc.getTransactionMeta(sent.sig) }
                    if (meta != null) {
                        fee = meta.fee
                        break
                    }
                    delay(FILL_WAIT_MS)
                }
                PlainEnd.Landed(fee)
            }
            SigEnd.Expired -> PlainEnd.Failed(EXPIRED)
            SigEnd.Timeout -> PlainEnd.Failed(NOT_CONFIRMED, unknown = true)
        }
    }

    /** Where a signature stands now: landed (with its error, if any), or null while it is not known. */
    suspend fun statusOf(sig: String): SignatureStatus? = orNull { rpc.getSignatureStatuses(listOf(sig)) }?.firstOrNull()

    /** Why `wallet` cannot fund a buy of `spend` in the pay currency, or null when it can. */
    private suspend fun buyShortfall(wallet: String, pay: PayInfo, spend: BigInteger, fee: BigInteger): String? {
        if (pay.asset.isSol) {
            val need = spend + fee + BUY_HEADROOM
            return if (rpc.getBalance(wallet) < need) "Insufficient SOL: about ${units(need, 9)} needed." else null
        }
        val accs = rpc.getMultipleAccounts(listOf(wallet, SolAddr.associatedTokenAddress(wallet, pay.asset.mint, pay.asset.program)))
        val held = accs.getOrNull(1)?.let { Layouts.decodeTokenAccount(it.data)?.amount } ?: BigInteger.ZERO
        if (held < spend) return "Insufficient ${pay.symbol}: ${units(spend, pay.decimals)} needed."
        // The network fee and any new account's rent are still paid in SOL.
        val need = fee + BUY_HEADROOM
        return if ((accs.getOrNull(0)?.lamports ?: BigInteger.ZERO) < need) "Insufficient SOL for fees: about ${units(need, 9)} needed." else null
    }

    /** A sent transaction: `privately` through the block engines, with its `tip`. */
    private class Sent(val raw: ByteArray, val sig: String, val ixs: List<Instruction>, val lastValid: Long, val privately: Boolean = false, val tip: BigInteger? = null)

    /** Whether these instructions make a transaction small enough to send. */
    private fun fits(wallet: String, ixs: List<Instruction>): Boolean =
        runCatching { SolTx.serializeTransaction(SolTx.compileMessage(wallet, ixs, SolAddr.SYSTEM_PROGRAM), emptyList()) }.isSuccess

    private sealed interface SigEnd {
        class Landed(val err: JsonElement?) : SigEnd
        data object Expired : SigEnd
        data object Timeout : SigEnd
    }

    /** Waits for a trade's transaction, then books how it ended. */
    private suspend fun confirm(t: Ticket, i: Int, p: Sent, job: LegJob) {
        val end = awaitSig(p) {
            t.set(i) { it.copy(error = "Not confirmed yet. Check the explorer.") }
            job.settled(null)
        }
        when (end) {
            is SigEnd.Landed -> {
                val err = end.err
                if (err != null) t.fail(i, SolErrors.explain(err, p.ixs)) else t.set(i) { it.copy(status = StepStatus.CONFIRMED, error = null) }
                fill(t, i, p.sig, job, p.tip)
            }
            SigEnd.Expired -> {
                t.fail(i, EXPIRED)
                job.settled(null)
            }
            SigEnd.Timeout -> {
                t.fail(i, NOT_CONFIRMED)
                job.settled(null)
            }
        }
    }

    /**
     * Waits for the transaction, sending it again until it lands or its blockhash expires. Past
     * the extension's two minutes it is still watched, slowly (`onLate` says so once), so the
     * trade ends on screen.
     */
    private suspend fun awaitSig(p: Sent, onLate: () -> Unit): SigEnd {
        val sentAt = now()
        var lastSend = sentAt
        var heightAt = sentAt
        var late = false
        while (now() - sentAt < WATCH_LIMIT_MS) {
            delay(if (late) LATE_POLL_MS else pollMs)
            val status = orNull { rpc.getSignatureStatuses(listOf(p.sig)) }?.firstOrNull()
            if (status != null && (status.confirmationStatus == "confirmed" || status.confirmationStatus == "finalized")) {
                return SigEnd.Landed(status.err)
            }
            val n = now()
            if (!late && n - lastSend >= REBROADCAST_MS) {
                lastSend = n
                scope.launch { orNull { rpc.broadcast(p.raw, p.privately) } }
            }
            if (late || n - heightAt >= HEIGHT_CHECK_MS) {
                heightAt = n
                val height = orNull { rpc.getBlockHeight() }
                if (height != null && height > p.lastValid && status == null) return SigEnd.Expired
            }
            if (!late && now() - sentAt >= CONFIRM_TIMEOUT_MS) {
                late = true
                onLate()
            }
        }
        return SigEnd.Timeout
    }

    /** The fee the transaction paid (and its tip, when it landed with one), and what it delivered. Best effort. */
    private suspend fun fill(t: Ticket, i: Int, sig: String, job: LegJob, tip: BigInteger? = null) {
        var landed: TxMeta? = null
        for (attempt in 0 until FILL_TRIES) {
            val meta = orNull { rpc.getTransactionMeta(sig) }
            if (meta != null) {
                val confirmed = t.step(i).status == StepStatus.CONFIRMED
                val out = if (confirmed) job.delivered?.invoke(meta)?.takeIf { it.signum() > 0 } else null
                t.set(i) { it.copy(feeWei = meta.fee, tip = if (confirmed) tip else null, amountOut = out ?: it.amountOut) }
                if (confirmed) landed = meta
                break
            }
            delay(FILL_WAIT_MS)
        }
        if (job.landed && t.step(i).status == StepStatus.CONFIRMED) orNull { onLanded(t.snapshot()) }
        job.settled(landed)
    }

    private fun maxPriority(prefs: SolPrefs, solUsd: Double): BigInteger {
        val lamports = Math.floor(prefs.maxGasUsd / solUsd * 1e9)
        return if (lamports.isFinite() && lamports > 0) BigDecimal(lamports).toBigInteger() else BigInteger.ZERO
    }

    // ── tickets ─────────────────────────────────────────────────────────────────────

    private fun newTicket(side: Side, wallet: String, token: String, symbol: String?, venue: String, first: TradeStep, payout: Payout?, decimals: Int): Ticket =
        Ticket("s${now()}-${counter.incrementAndGet()}", side, wallet, token, symbol, venue, now(), first, payout, decimals)

    private class Ticket(
        val id: String,
        val side: Side,
        val wallet: String,
        val token: String,
        val symbol: String?,
        val venue: String,
        val startedAt: Long,
        first: TradeStep,
        val payout: Payout?,
        val decimals: Int,
    ) {
        private val lock = Any()
        private val steps = mutableListOf(first)
        private var blocked: List<BlockedAction>? = null
        private var status = TradeStatus.WORKING
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
            Trade(id, side, CHAIN_ID, wallet, token, symbol, venue, steps.toList(), status, blocked, startedAt, payout, decimals)
        }

        /** The trade's state from all its transactions, as service.ts update() reads it. */
        private fun publish() {
            flow.value = synchronized(lock) {
                val done = steps.count { it.status == StepStatus.CONFIRMED }
                val bad = steps.count { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED || it.status == StepStatus.SKIPPED }
                if (done + bad == steps.size) {
                    status = when {
                        done == steps.size -> TradeStatus.DONE
                        blocked != null && done == 0 -> TradeStatus.BLOCKED
                        else -> TradeStatus.FAILED
                    }
                }
                Trade(id, side, CHAIN_ID, wallet, token, symbol, venue, steps.toList(), status, blocked, startedAt, payout, decimals)
            }
        }
    }

    companion object {
        /** Solana's id in the extension's tickets (and Relay's). */
        const val CHAIN_ID = 792_703_809L

        const val VENUE_TTL_MS = 400L
        private const val SOL_DECIMALS = 9

        /** A trade reads its route at most this old: the sheet quotes about every few seconds. */
        const val MAX_QUOTE_AGE_MS = 2_000L
        const val CONFIRM_POLL_MS = 800L
        private const val CONFIG_TTL_MS = 60_000L

        /** A blockhash stays valid for ~60 s; one this old still leaves time to land. */
        private const val BLOCKHASH_TTL_MS = 15_000L
        private const val REBROADCAST_MS = 2_000L
        private const val HEIGHT_CHECK_MS = 4_000L
        private const val CONFIRM_TIMEOUT_MS = 120_000L
        private const val LATE_POLL_MS = 10_000L
        private const val WATCH_LIMIT_MS = 60 * 60_000L
        private const val FILL_TRIES = 5
        private const val FILL_WAIT_MS = 1_000L
        private const val PRICE_TTL_MS = 30_000L
        private const val MAX_CACHE = 200

        /** How long a pay token's pools against SOL, as ranked, are kept. */
        private const val POOLS_TTL_MS = 60_000L

        /** Pools the server named kept per coin. */
        private const val MAX_PAGE_POOLS = 3

        /** Pools that turn SOL into a coin's pay token kept per token, the best for a SOL first. */
        private const val MAX_FUNDING_LEGS = 2

        /** Quotes read them at most this old: they move far less than a new coin's own pool. */
        private const val LEG_AGE_MS = 1_500L

        /** Slippage for turning SOL into a pay token and back: the deep pool's, not the user's. */
        private const val FUNDING_SLIPPAGE_BPS = 100

        /** What those pools are ranked by: what they pay for this many lamports (1 SOL). */
        private val LEG_RANK_LAMPORTS: BigInteger = BigInteger.valueOf(1_000_000_000L)

        /** What a buy may need on top of its amount: new accounts' rent and the network fee. */
        private val BUY_HEADROOM: BigInteger = BigInteger.valueOf(6_000_000L)

        /** Solana's base fee per signature. */
        val SIGNATURE_LAMPORTS: BigInteger = BigInteger.valueOf(5_000L)
        private val HUNDRED: BigInteger = BigInteger.valueOf(100)
        private val TEN_K: BigInteger = BigInteger.valueOf(10_000)
        private val MILLION: BigInteger = BigInteger.valueOf(1_000_000)

        const val NO_ROUTE = "No pool can trade this coin right now."
        const val EXPIRED = "Expired before it landed. Try a higher priority fee."
        const val NOT_CONFIRMED = "Not confirmed. Check the explorer."
        const val FEE_ACCOUNTS = "pump.fun changed its fee accounts."
        const val CURVE_COMPLETE = "The pump.fun curve is complete. Trading opens on PumpSwap once it migrates."
        const val UNTRADABLE = "Tokens with a transfer fee or hook cannot be traded here yet."

        fun noPrice(symbol: String) = "No USD price for $symbol, so buys cannot be checked against your limits."

        fun noSolPool(symbol: String?) = "This coin is bought with ${symbol ?: "another token"}, and no pool turns SOL into it right now."

        /** A route's label for a trade through SOL and its pay token: "PumpSwap via AAPLx". */
        fun viaLabel(label: String, symbol: String) = label + (if (label.contains(" via ")) " →" else " via") + " " + symbol

        fun solPay(usd: Double?) = PayInfo(SOL_QUOTE, "SOL", 9, usd, stable = false)

        /** `amount` less `bps` of slippage, never zero (a zero minimum would accept anything). */
        fun minAfter(amount: BigInteger, bps: Int): BigInteger {
            val min = amount * BigInteger.valueOf((10_000 - bps).toLong()) / TEN_K
            return if (min.signum() > 0) min else BigInteger.ONE
        }

        /** Per-unit price that makes `units` of compute cost `feeLamports` in total. */
        fun microLamports(feeLamports: BigInteger, units: Int): BigInteger = feeLamports * MILLION / BigInteger.valueOf(units.toLong())

        /** Token-2022 rules pools cannot quote or trade through: a fee on every transfer, or a hook. */
        fun untradable(meta: SolTokenMeta) = Layouts.EXT_TRANSFER_FEE_CONFIG in meta.extensions || meta.hookProgram != null

        /** A raw amount as a plain decimal, as viem's formatUnits writes it. */
        fun units(raw: BigInteger, decimals: Int): String {
            if (raw.signum() == 0) return "0"
            return BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()
        }

        fun parseMeta(mintAcc: SolAccount?, metaAcc: SolAccount?): SolTokenMeta? {
            if (mintAcc == null || !SolAddr.isTokenProgram(mintAcc.owner)) return null
            val info = Layouts.decodeMint(mintAcc.data) ?: return null
            if (!info.initialized) return null
            val mp = if (metaAcc?.owner == SolAddr.METADATA_PROGRAM) Layouts.decodeMetaplexMetadata(metaAcc.data) else null
            val uri = info.uri?.takeIf { it.isNotEmpty() } ?: mp?.uri?.takeIf { it.isNotEmpty() }
            return SolTokenMeta(
                name = sanitizeLabel(info.name?.takeIf { it.isNotEmpty() } ?: mp?.name ?: "", 32).ifEmpty { "Unknown token" },
                symbol = sanitizeLabel(info.symbol?.takeIf { it.isNotEmpty() } ?: mp?.symbol ?: "", 16).ifEmpty { "???" },
                decimals = info.decimals,
                tokenProgram = mintAcc.owner,
                supply = info.supply,
                extensions = info.extensions,
                hookProgram = info.transferHookProgram,
                uri = uri,
            )
        }
    }
}
