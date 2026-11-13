package trade.nesty.app.evm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import trade.nesty.app.keys.keccak256
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A pool pairing the token with a stablecoin, reached from native through a native/stable
 * pool (the bridge) in the same transaction.
 */
data class Via(
    val stable: String,
    val pool: String,
    val fee: Int,
    /** Set when the native ↔ `stable` leg is a v4 pool (then `pool`/`fee` are unused). */
    val v4Key: PoolKey? = null,
)

/** `quote` is the pool's other asset: native / wrapped native, or the stable of `via`. */
sealed interface UniPool : PoolData {
    val quote: String
    val via: Via?
}

data class UniV2(val pair: String, override val quote: String, val token0: String, override val via: Via? = null) : UniPool
data class UniV3(val pool: String, override val quote: String, val fee: Int, override val via: Via? = null) : UniPool
data class UniV4(val poolId: String, val key: PoolKey, override val quote: String, override val via: Via? = null) : UniPool

/** The v4 pool id of a key. */
fun poolIdOf(key: PoolKey): String =
    keccak256(Abi.encode("address, address, uint24, int24, address", *key.abi().toTypedArray())).toHex()

/** "dynamic fee", or the fee in percent as JavaScript writes the number ("0.3%", "1%"). */
fun feeLabel(fee: Int): String = if ((fee and 0x800000) != 0) "dynamic fee" else "${percent(fee)}%"

/** fee / 10 000, without trailing zeros. */
fun percent(fee: Int): String = BigDecimal(fee).divide(BigDecimal(10_000)).stripTrailingZeros().toPlainString()

/**
 * Uniswap v2, v3 and v4 through the Universal Router, ported from uniswap/venue.ts. Covers
 * graduated coins and the launchpads that seed v4 pools straight through the PoolManager
 * (Clanker, Bankr/Doppler, Klik, Flaunch, Zora, Pons, Stockereum), rebuilt from their own
 * records.
 */
object UniswapVenue : Venue {
    override val id = "uniswap"

    private const val DYNAMIC_FEE = 0x800000
    private val V3_FEES = listOf(100, 500, 3000, 10000)
    private val V4_STANDARD = listOf(100 to 1, 500 to 10, 3000 to 60, 10000 to 200)

    /** The highest fixed fee a v4 pool may charge to be traded through (10%). */
    private const val MAX_FIXED_FEE = 100_000

    private val getPair = AbiFunction("function getPair(address, address) view returns (address)")
    private val token0 = AbiFunction("function token0() view returns (address)")
    private val getReserves = AbiFunction("function getReserves() view returns (uint112, uint112, uint32)")
    private val getPool = AbiFunction("function getPool(address, address, uint24) view returns (address)")
    private val liquidity = AbiFunction("function liquidity() view returns (uint128)")
    private val quoterV2 = AbiFunction(
        "function quoteExactInputSingle((address tokenIn, address tokenOut, uint256 amountIn, uint24 fee, uint160 sqrtPriceLimitX96)) returns (uint256 amountOut, uint160, uint32, uint256 gasEstimate)",
    )
    private val getLiquidity = AbiFunction("function getLiquidity(bytes32) view returns (uint128)")
    private val poolKeys = AbiFunction(
        "function poolKeys(bytes25) view returns (address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks)",
    )
    private val v4Quote = AbiFunction(
        "function quoteExactInputSingle(((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks) poolKey, bool zeroForOne, uint128 exactAmount, bytes hookData)) returns (uint256 amountOut, uint256 gasEstimate)",
    )
    private val zoraPoolKey = AbiFunction(
        "function getPoolKey() view returns ((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks))",
    )
    private val clankerInfo = AbiFunction(
        "function tokenDeploymentInfo(address) view returns ((address token, address hook, address locker, address[] extensions))",
    )
    private val dopplerAsset = AbiFunction(
        "function getAssetData(address) view returns (address numeraire, address timelock, address governance, address liquidityMigrator, address poolInitializer, address pool, address migrationPool, uint256 numTokensToSell, uint256 totalSupply, address integrator)",
    )
    private val klikHook = AbiFunction("function tokenHook(address) view returns (address)")
    private val flaunchKey = AbiFunction(
        "function poolKey(address) view returns ((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks))",
    )
    private val ponsCurve = AbiFunction("function curve() view returns (address)")
    private val ponsFeePolicy = AbiFunction("function feePolicy() view returns (address)")
    private val ponsPairToken = AbiFunction("function pairToken() view returns (address)")
    private val launches = AbiFunction(
        "function launches(bytes32) view returns (bool registered, bool memecoinIsCurrency0, address memecoin, address quoteToken, address creator, address buybackCreatorRecipient, address protocolFeeRecipient, uint16 creatorTaxBps, uint16 protocolFeeShareBps, uint16 buybackBurnBps, uint16 hookFeeBps, uint16 maxInternalPriceImpactBps, bool buybackEnabled)",
    )

    /** Clanker v4 factories (Clanker, and Bankr before it moved to Doppler). Paired with WETH. */
    private val CLANKER_V4 = mapOf(
        1L to listOf("0x6c8599779b03b00aaae63c6378830919abb75473"),
        8453L to listOf("0xe85a59c628f7d27878aceb4bf3b35733630083a9"),
    )

    /** Doppler Airlocks (Bankr's current launches). The pool initializer is the pool's hook. */
    private val DOPPLER_AIRLOCK = mapOf(
        8453L to listOf("0x660eaaedebc968f8f3694354fa8ec0b4c5ba8d12"),
        4663L to listOf("0xeb7c034704ef8dcd2d32324c1545f62fb4ad0862"),
    )

    /** Flaunch position managers (each is also its pools' hook); coins are paired with flETH. */
    private val FLAUNCH = mapOf(
        8453L to listOf(
            "0x23321f11a6d44fd1ab790044fdfde5758c902fdc",
            "0x588c683ecc450f8b2aadb13d7f63792b840425dc",
            "0x8dc3b85e1dc1c846ebf3971179a751896842e5dc",
        ),
    )

    /** Klik factories: per-token hook, paired with native ETH (fee 0, tick spacing 200). */
    private val KLIK = mapOf(1L to listOf("0xdfb7fe5e4ea0504afee387c42bb86e5860d29e8a"))

    /** Chains with Pons launches: a graduated coin trades in a v4 pool its curve's fee policy hooks. */
    private val PONS_CHAINS = setOf(4663L)

    private data class LaunchHook(val hooks: String, val fee: Int, val tickSpacing: Int)

    /** Fixed hook configurations, paired with wrapped native (Stockereum). */
    private val LAUNCH_HOOKS = mapOf(1L to listOf(LaunchHook("0x322dcec4958c14e021a9f1cd49df11b9457968cc", 0, 200)))

    private const val NATIVE = UniversalRouter.NATIVE
    private const val ADDRESS_THIS = UniversalRouter.ADDRESS_THIS
    private val OPEN_DELTA = UniversalRouter.OPEN_DELTA
    private val CONTRACT_BALANCE = UniversalRouter.CONTRACT_BALANCE
    private val INNER_MIN = UniversalRouter.INNER_MIN

    override fun supports(chainId: Long) = EvmChains.byId(chainId) != null

    private fun isZero(a: String?) = a == null || sameAddress(a, ZERO_ADDRESS)

    /** A v4 key is usable if it pairs `token` with native, wrapped native or one of `stables`. */
    private fun v4QuoteSide(key: PoolKey, token: String, wrapped: String, stables: List<String>): String? {
        val other = when {
            sameAddress(key.currency0, token) -> key.currency1
            sameAddress(key.currency1, token) -> key.currency0
            else -> return null
        }
        return if (sameAddress(other, NATIVE) || sameAddress(other, wrapped) || stables.any { sameAddress(it, other) }) other else null
    }

    private val POOL_ID = Regex("^0x[0-9a-fA-F]{64}$")

    /** Resolves a v4 pool id to its key via PositionManager, verified by hashing it again. */
    suspend fun poolKeyFromId(ctx: VenueCtx, poolId: String): PoolKey? {
        val r = orNull { ctx.reader.read(ctx.chain.uniswap.v4PositionManager, poolKeys, poolId.hexBytes().copyOf(25)) } ?: return null
        val key = PoolKey.of(r)
        return if (poolIdOf(key) == poolId.lowercase()) key else null
    }

    /** A Pons coin's pool hook and pair asset, read from its curve. */
    private suspend fun ponsHook(ctx: VenueCtx, token: String): Pair<String, String>? {
        val curve = orNull { ctx.reader.read1(token, ponsCurve) } as String?
        if (isZero(curve)) return null
        val r = ctx.reader.multicall(listOf(Call(curve!!, ponsFeePolicy.encode()), Call(curve, ponsPairToken.encode())))
        val hook = r[0].decode(ponsFeePolicy)?.get(0) as String?
        if (isZero(hook)) return null
        val pairRaw = r[1].decode(ponsPairToken)?.get(0) as String?
        val pair = if (pairRaw != null && !isZero(pairRaw)) pairRaw else NATIVE
        return hook!! to pair
    }

    /**
     * Candidate keys from launchpad records: Clanker's and Klik's hook per token, Doppler's
     * numeraire and initializer hook, Flaunch's own key, the Pons hook, and fixed hooks.
     * Wrong guesses have no liquidity and drop out.
     */
    private suspend fun launchpadKeys(ctx: VenueCtx, token: String): List<PoolKey> = coroutineScope {
        val id = ctx.chain.id
        val weth = ctx.chain.wrappedNative
        fun key(other: String, fee: Int, tickSpacing: Int, hooks: String): PoolKey {
            val (c0, c1) = sortPair(token, other)
            return PoolKey(c0.lowercase(), c1.lowercase(), fee, tickSpacing, hooks.lowercase())
        }
        val clanker = CLANKER_V4[id].orEmpty().map { a -> async { orNull { ctx.reader.read(a, clankerInfo, token) } } }
        val doppler = DOPPLER_AIRLOCK[id].orEmpty().map { a -> async { orNull { ctx.reader.read(a, dopplerAsset, token) } } }
        val klik = KLIK[id].orEmpty().map { a -> async { orNull { ctx.reader.read(a, klikHook, token) } } }
        val flaunch = FLAUNCH[id].orEmpty().map { a -> async { orNull { ctx.reader.read(a, flaunchKey, token) } } }
        val pons = async { if (id in PONS_CHAINS) ponsHook(ctx, token) else null }
        val out = mutableListOf<PoolKey>()
        for (r in clanker.awaitAll()) {
            val info = r?.get(0) as List<*>? ?: continue
            val hook = info[1] as String
            if (sameAddress(info[0] as String, token) && !isZero(hook)) out += key(weth, DYNAMIC_FEE, 200, hook)
        }
        for (r in doppler.awaitAll()) {
            if (r == null) continue
            val numeraire = r[0] as String
            val initializer = r[4] as String
            if (!isZero(initializer)) out += key(if (isZero(numeraire)) NATIVE else numeraire, DYNAMIC_FEE, 200, initializer)
        }
        for (r in klik.awaitAll()) {
            val hook = r?.get(0) as String?
            if (!isZero(hook)) out += key(NATIVE, 0, 200, hook!!)
        }
        flaunch.awaitAll().forEachIndexed { i, r ->
            val manager = FLAUNCH[id]?.getOrNull(i)
            val k = r?.let { PoolKey.of(it[0]) }
            if (k != null && manager != null && sameAddress(k.hooks, manager) &&
                (sameAddress(k.currency0, token) || sameAddress(k.currency1, token))
            ) out += k
        }
        pons.await()?.let { (hook, pair) -> out += key(pair, 0, 200, hook) }
        for (h in LAUNCH_HOOKS[id].orEmpty()) out += key(weth, h.fee, h.tickSpacing, h.hooks)
        out
    }

    private data class V3Lookup(val a: String, val b: String, val fee: Int)

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> = coroutineScope {
        val d = ctx.chain.uniswap
        val weth = ctx.chain.wrappedNative
        val stables = hopStables(ctx.chain, token)
        val quotes = listOf(weth) + stables

        // Pool ids the server named (in place of the extension's pool index), each verified.
        val hinted = async {
            orNull {
                ctx.poolHints.filter { POOL_ID.matches(it) }.map { it.lowercase() }.distinct().take(10)
                    .map { id -> async { id to poolKeyFromId(ctx, id) } }.awaitAll()
            }.orEmpty()
        }
        val zora = async { orNull { ctx.reader.read(token, zoraPoolKey) }?.let { PoolKey.of(it[0]) } }
        val launched = async { orNull { launchpadKeys(ctx, token) }.orEmpty() }
        val v3Lookups = quotes.flatMap { q -> V3_FEES.map { V3Lookup(token, q, it) } } +
            stables.flatMap { s -> V3_FEES.map { V3Lookup(weth, s, it) } }
        val v2Res = async { ctx.reader.multicall(quotes.map { q -> Call(d.v2Factory, getPair.encode(token, q)) }) }
        val v3Res = async { ctx.reader.multicall(v3Lookups.map { l -> Call(d.v3Factory, getPool.encode(l.a, l.b, l.fee)) }) }

        val pairs = quotes.mapIndexedNotNull { i, q ->
            val pair = v2Res.await()[i].decode(getPair)?.get(0) as String?
            if (isZero(pair)) null else q to pair!!
        }
        val v3Found = v3Lookups.mapIndexedNotNull { i, l ->
            val pool = v3Res.await()[i].decode(getPool)?.get(0) as String?
            if (isZero(pool)) null else l to pool!!
        }
        val v3Pools = v3Found.filter { sameAddress(it.first.a, token) }
        val bridgeCandidates = v3Found.filter { !sameAddress(it.first.a, token) }

        // v4 candidates: hook-less tiers, Zora's getPoolKey(), launch records and hints.
        val keys = LinkedHashMap<String, PoolKey>()
        for (quote in listOf(NATIVE, weth) + stables) {
            val (c0, c1) = sortPair(token, quote)
            for ((fee, tickSpacing) in V4_STANDARD) {
                val key = PoolKey(c0.lowercase(), c1.lowercase(), fee, tickSpacing, ZERO_ADDRESS)
                keys[poolIdOf(key)] = key
            }
        }
        zora.await()?.let { keys[poolIdOf(it)] = it }
        // Launch pools can sit outside their range between swaps: the quote decides.
        val launchedIds = HashSet<String>()
        for (key in launched.await()) {
            val pid = poolIdOf(key)
            keys[pid] = key
            launchedIds += pid
        }
        for ((pid, key) in hinted.await()) if (key != null && pid !in keys) keys[pid] = key
        val v4Candidates = keys.entries
            .filter { (_, k) -> (k.fee and DYNAMIC_FEE) != 0 || k.fee <= MAX_FIXED_FEE }
            .mapNotNull { (pid, k) -> v4QuoteSide(k, token, weth, stables)?.let { Triple(pid, k, it) } }

        // Liquidity checks for everything in one more multicall.
        val checkCalls = pairs.flatMap { (_, pair) -> listOf(Call(pair, getReserves.encode()), Call(pair, token0.encode())) } +
            (v3Pools + bridgeCandidates).map { (_, pool) -> Call(pool, liquidity.encode()) } +
            v4Candidates.map { (pid, _, _) -> Call(d.v4StateView, getLiquidity.encode(pid)) }
        val checks = ctx.reader.multicall(checkCalls)
        var i = 0
        data class V2Live(val pair: String, val quote: String, val token0: String)
        val v2Live = mutableListOf<V2Live>()
        for ((quote, pair) in pairs) {
            val reserves = checks.getOrNull(i++)?.decode(getReserves)
            val t0 = checks.getOrNull(i++)?.decode(token0)
            if (reserves != null && t0 != null) {
                if ((reserves[0] as BigInteger).signum() > 0 && (reserves[1] as BigInteger).signum() > 0) {
                    v2Live += V2Live(pair, quote, t0[0] as String)
                }
            }
        }
        fun nextLiquidity(): BigInteger = checks.getOrNull(i++)?.decode(liquidity)?.get(0) as BigInteger? ?: BigInteger.ZERO
        val v3Live = v3Pools.map { it to nextLiquidity() }.filter { it.second.signum() > 0 }.map { it.first }
        // Per stable, the bridge pool with the most in-range liquidity.
        val bridges = HashMap<String, Via>()
        val bridgeLiq = HashMap<String, BigInteger>()
        for ((l, pool) in bridgeCandidates) {
            val liq = nextLiquidity()
            val k = l.b.lowercase()
            if (liq.signum() > 0 && liq > (bridgeLiq[k] ?: BigInteger.ZERO)) {
                bridgeLiq[k] = liq
                bridges[k] = Via(l.b, pool, l.fee)
            }
        }
        val v4Live = v4Candidates.filter { (pid, _, _) -> nextLiquidity().signum() > 0 || pid in launchedIds }
        // Hop assets converted from native in a v4 pool (flETH): the quotes decide.
        for (h in ctx.chain.hopTokens) {
            val bridge = h.v4Bridge ?: continue
            if (bridges.containsKey(h.address.lowercase())) continue
            if (stables.none { sameAddress(it, h.address) }) continue
            val (c0, c1) = sortPair(NATIVE, h.address)
            bridges[h.address.lowercase()] = Via(
                h.address, ZERO_ADDRESS, 0,
                PoolKey(c0.lowercase(), c1.lowercase(), bridge.fee, bridge.tickSpacing, bridge.hooks.lowercase()),
            )
        }

        /** null = a stable-quoted pool with no bridge pool: unreachable from native, skipped. */
        fun viaFor(quote: String): Pair<Via?, String>? {
            if (sameAddress(quote, weth) || sameAddress(quote, NATIVE)) return null to ""
            val via = bridges[quote.lowercase()] ?: return null
            return via to " via ${stableSymbol(ctx.chain, quote)}"
        }

        val out = mutableListOf<PoolRef>()
        for (p in v2Live) {
            val (via, suffix) = viaFor(p.quote) ?: continue
            if (via?.v4Key != null) continue
            out += PoolRef(id, p.pair.lowercase(), "Uniswap v2$suffix", token, UniV2(p.pair, p.quote, p.token0, via))
        }
        for ((l, pool) in v3Live) {
            val (via, suffix) = viaFor(l.b) ?: continue
            if (via?.v4Key != null) continue
            out += PoolRef(id, pool.lowercase(), "Uniswap v3 · ${feeLabel(l.fee)}$suffix", token, UniV3(pool, l.b, l.fee, via))
        }
        for ((pid, key, quote) in v4Live) {
            val (via, suffix) = viaFor(quote) ?: continue
            val hooked = !isZero(key.hooks)
            out += PoolRef(
                id, pid, "Uniswap v4 · ${feeLabel(key.fee)}${if (hooked) " · hook" else ""}$suffix", token,
                UniV4(pid, key, quote, via),
            )
        }
        out
    }

    /** Uniswap v2 constant product with the 0.3% fee. */
    private fun v2AmountOut(amountIn: BigInteger, reserveIn: BigInteger, reserveOut: BigInteger): BigInteger {
        if (amountIn.signum() <= 0 || reserveIn.signum() <= 0 || reserveOut.signum() <= 0) return BigInteger.ZERO
        val inWithFee = amountIn * big(10_000 - 30)
        return inWithFee * reserveOut / (reserveIn * big(10_000) + inWithFee)
    }

    /** One pool, `token` against the pool's quote asset. Buy = quote → token. */
    private suspend fun quoteLeg(ctx: VenueCtx, pool: UniPool, token: String, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val d = ctx.chain.uniswap
        if (amounts.isEmpty()) return emptyList()
        return when (pool) {
            is UniV2 -> {
                val r = ctx.reader.read(pool.pair, getReserves) ?: throw IOException("getReserves failed")
                val r0 = r[0] as BigInteger
                val r1 = r[1] as BigInteger
                val (rToken, rQuote) = if (sameAddress(pool.token0, token)) r0 to r1 else r1 to r0
                amounts.map { a -> if (side == Side.BUY) v2AmountOut(a, rQuote, rToken) else v2AmountOut(a, rToken, rQuote) }
            }
            // Pools that run only Uniswap's code and the tokens': quoted with the other pools.
            is UniV3 -> {
                val (tokenIn, tokenOut) = if (side == Side.BUY) pool.quote to token else token to pool.quote
                ctx.reader.shared(amounts.map { a -> Call(d.v3QuoterV2, quoterV2.encode(listOf(tokenIn, tokenOut, a, pool.fee, 0))) })
                    .map { firstOut(it, quoterV2) }
            }
            is UniV4 -> {
                val tokenIs0 = sameAddress(pool.key.currency0, token)
                val zeroForOne = if (side == Side.BUY) !tokenIs0 else tokenIs0
                val calls = amounts.map { a -> Call(d.v4Quoter, v4Quote.encode(listOf(pool.key.abi(), zeroForOne, a, ByteArray(0)))) }
                // A hooked pool asks alone: a hook that burns its gas cannot starve other quotes.
                val res = if (isZero(pool.key.hooks)) ctx.reader.shared(calls) else ctx.reader.multicall(calls)
                res.map { firstOut(it, v4Quote) }
            }
        }
    }

    private fun bridgePool(ctx: VenueCtx, via: Via): UniPool =
        if (via.v4Key != null) UniV4(poolIdOf(via.v4Key), via.v4Key, NATIVE)
        else UniV3(via.pool, ctx.chain.wrappedNative, via.fee)

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val p = pool.data as UniPool
        val via = p.via ?: return quoteLeg(ctx, p, pool.token, side, amounts)
        val bridge = bridgePool(ctx, via)
        // Buy: native → stable → token; sell: token → stable → native.
        return if (side == Side.BUY) {
            chainQuotes(amounts, { quoteLeg(ctx, bridge, via.stable, Side.BUY, it) }, { quoteLeg(ctx, p, pool.token, Side.BUY, it) })
        } else {
            chainQuotes(amounts, { quoteLeg(ctx, p, pool.token, Side.SELL, it) }, { quoteLeg(ctx, bridge, via.stable, Side.SELL, it) })
        }
    }

    private fun swap(key: PoolKey, zeroForOne: Boolean, amountIn: BigInteger, min: BigInteger) =
        V4Action.SwapExactInSingle(key, zeroForOne, amountIn, min, BigInteger.ZERO, ByteArray(0))

    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft {
        val data = pool.data as UniPool
        val weth = ctx.chain.wrappedNative
        val token = pool.token
        val commands = mutableListOf<Command>()
        val via = data.via
        if (via?.v4Key != null && data is UniV4) {
            // Both legs v4, one unlock: native → hop asset, then its whole credit → token.
            commands += Command.V4Swap(
                listOf(
                    swap(via.v4Key, sameAddress(via.v4Key.currency0, NATIVE), p.amountIn, INNER_MIN),
                    swap(data.key, !sameAddress(data.key.currency0, token), OPEN_DELTA, p.minOut),
                    V4Action.SettleAll(NATIVE, p.amountIn),
                    V4Action.Take(token, p.wallet, OPEN_DELTA),
                ),
            )
        } else if (via != null) {
            commands += Command.WrapEth(ADDRESS_THIS, p.amountIn)
            val toStable = V3Hop(weth, via.fee, via.stable)
            when (data) {
                // Both legs v3: one multi-hop path.
                is UniV3 -> commands += Command.V3SwapExactIn(
                    p.wallet, p.amountIn, p.minOut, listOf(toStable, V3Hop(via.stable, data.fee, token)), false,
                )
                else -> {
                    // Native → stable into the router, then the router's whole stable balance → token.
                    commands += Command.V3SwapExactIn(ADDRESS_THIS, p.amountIn, INNER_MIN, listOf(toStable), false)
                    if (data is UniV2) {
                        commands += Command.V2SwapExactIn(p.wallet, CONTRACT_BALANCE, p.minOut, listOf(via.stable, token), false)
                    } else {
                        val key = (data as UniV4).key
                        val tokenIs0 = sameAddress(key.currency0, token)
                        commands += Command.V4Swap(
                            listOf(
                                V4Action.Settle(via.stable, CONTRACT_BALANCE, false),
                                swap(key, !tokenIs0, OPEN_DELTA, p.minOut),
                                V4Action.Take(token, p.wallet, OPEN_DELTA),
                            ),
                        )
                    }
                }
            }
        } else {
            when (data) {
                is UniV2 -> {
                    commands += Command.WrapEth(ADDRESS_THIS, p.amountIn)
                    commands += Command.V2SwapExactIn(p.wallet, p.amountIn, p.minOut, listOf(weth, token), false)
                }
                is UniV3 -> {
                    commands += Command.WrapEth(ADDRESS_THIS, p.amountIn)
                    commands += Command.V3SwapExactIn(p.wallet, p.amountIn, p.minOut, listOf(V3Hop(weth, data.fee, token)), false)
                }
                is UniV4 -> {
                    val tokenIs0 = sameAddress(data.key.currency0, token)
                    val native = sameAddress(data.quote, NATIVE)
                    if (!native) commands += Command.WrapEth(ADDRESS_THIS, p.amountIn)
                    commands += Command.V4Swap(
                        listOf(
                            swap(data.key, !tokenIs0, p.amountIn, p.minOut),
                            if (native) V4Action.SettleAll(NATIVE, p.amountIn) else V4Action.Settle(weth, p.amountIn, false),
                            V4Action.Take(token, p.wallet, OPEN_DELTA),
                        ),
                    )
                }
            }
        }
        return TxDraft(ctx.chain.uniswap.universalRouter, UniversalRouter.encodeExecute(commands, p.deadline), p.amountIn)
    }

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft {
        val data = pool.data as UniPool
        val weth = ctx.chain.wrappedNative
        val token = pool.token
        val commands = mutableListOf<Command>()
        val via = data.via
        p.permit?.let { commands += Command.Permit2Permit(it.permit, it.signature) }
        if (via?.v4Key != null && data is UniV4) {
            // Token → hop asset, then its whole credit → native, in one unlock.
            commands += Command.V4Swap(
                listOf(
                    swap(data.key, sameAddress(data.key.currency0, token), p.amountIn, INNER_MIN),
                    swap(via.v4Key, !sameAddress(via.v4Key.currency0, NATIVE), OPEN_DELTA, p.minOut),
                    V4Action.SettleAll(token, p.amountIn),
                    V4Action.Take(NATIVE, p.wallet, OPEN_DELTA),
                ),
            )
        } else if (via != null) {
            val toNative = V3Hop(via.stable, via.fee, weth)
            if (data is UniV3) {
                commands += Command.V3SwapExactIn(
                    ADDRESS_THIS, p.amountIn, p.minOut, listOf(V3Hop(token, data.fee, via.stable), toNative), true,
                )
            } else {
                // Token → stable into the router, then the router's whole stable balance → native.
                if (data is UniV2) {
                    commands += Command.V2SwapExactIn(ADDRESS_THIS, p.amountIn, INNER_MIN, listOf(token, via.stable), true)
                } else {
                    val key = (data as UniV4).key
                    val tokenIs0 = sameAddress(key.currency0, token)
                    commands += Command.V4Swap(
                        listOf(
                            swap(key, tokenIs0, p.amountIn, INNER_MIN),
                            V4Action.SettleAll(token, p.amountIn),
                            V4Action.Take(via.stable, ADDRESS_THIS, OPEN_DELTA),
                        ),
                    )
                }
                commands += Command.V3SwapExactIn(ADDRESS_THIS, CONTRACT_BALANCE, p.minOut, listOf(toNative), false)
            }
            commands += Command.UnwrapWeth(p.wallet, p.minOut)
        } else {
            when (data) {
                is UniV2 -> {
                    commands += Command.V2SwapExactIn(ADDRESS_THIS, p.amountIn, p.minOut, listOf(token, weth), true)
                    commands += Command.UnwrapWeth(p.wallet, p.minOut)
                }
                is UniV3 -> {
                    commands += Command.V3SwapExactIn(ADDRESS_THIS, p.amountIn, p.minOut, listOf(V3Hop(token, data.fee, weth)), true)
                    commands += Command.UnwrapWeth(p.wallet, p.minOut)
                }
                is UniV4 -> {
                    val tokenIs0 = sameAddress(data.key.currency0, token)
                    val native = sameAddress(data.quote, NATIVE)
                    commands += Command.V4Swap(
                        listOf(
                            swap(data.key, tokenIs0, p.amountIn, p.minOut),
                            V4Action.SettleAll(token, p.amountIn),
                            if (native) V4Action.Take(NATIVE, p.wallet, OPEN_DELTA) else V4Action.Take(weth, ADDRESS_THIS, OPEN_DELTA),
                        ),
                    )
                    if (!native) commands += Command.UnwrapWeth(p.wallet, p.minOut)
                }
            }
        }
        return TxDraft(ctx.chain.uniswap.universalRouter, UniversalRouter.encodeExecute(commands, p.deadline), BigInteger.ZERO)
    }

    /**
     * Sells what was already transferred to the Universal Router (by the Nesty Router, which
     * cannot sign Permit2): its whole balance of the token → native, paid to `p.wallet`.
     * v2/v3 pools only, direct or through a v3 stable hop.
     */
    override fun buildSellPrefunded(ctx: VenueCtx, pool: PoolRef, p: PrefundedParams): TxDraft? {
        val data = pool.data as UniPool
        val weth = ctx.chain.wrappedNative
        val token = pool.token
        val swap: Command = when {
            data is UniV3 -> {
                val first = V3Hop(token, data.fee, data.via?.stable ?: weth)
                val hop = if (data.via != null && data.via.v4Key == null) listOf(V3Hop(data.via.stable, data.via.fee, weth)) else emptyList()
                if (data.via != null && hop.isEmpty()) return null
                Command.V3SwapExactIn(ADDRESS_THIS, CONTRACT_BALANCE, p.minOut, listOf(first) + hop, false)
            }
            data is UniV2 && data.via == null -> Command.V2SwapExactIn(ADDRESS_THIS, CONTRACT_BALANCE, p.minOut, listOf(token, weth), false)
            else -> return null
        }
        val commands = listOf(swap, Command.UnwrapWeth(p.wallet, p.minOut))
        return TxDraft(ctx.chain.uniswap.universalRouter, UniversalRouter.encodeExecute(commands, p.deadline), BigInteger.ZERO)
    }

    /**
     * What a v4 pool's hook takes on each swap, when it declares it (Pons and other
     * HoodFi-style hooks). Already in quotes; reported so the tax shown matches what is paid.
     */
    override suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax {
        val none = VenueTax(0, 0)
        val data = pool.data as UniPool
        if (data !is UniV4 || isZero(data.key.hooks)) return none
        val r = orNull { ctx.reader.read(data.key.hooks, launches, data.poolId) } ?: return none
        if (r[0] != true || !sameAddress(r[2] as String, pool.token)) return none
        val bps = (r[7] as BigInteger).toInt() + (r[10] as BigInteger).toInt()
        return if (bps > 10_000) none else VenueTax(bps, bps)
    }

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef) = SellApproval(Permit2.ADDRESS, true, ctx.chain.uniswap.universalRouter)
}
