package trade.nesty.app.evm

import java.math.BigInteger
import trade.nesty.app.i18n.tr

/** A constant-product pool behind a v2-style router. */
data class V2RouterPool(
    val pair: String,
    val stable: Boolean,
    /** Stablecoin quote: the route goes native → stable (volatile pool) → token. */
    val via: String? = null,
) : PoolData

/** A concentrated-liquidity pool behind a SwapRouter02-style router. */
data class V3RouterPool(
    val pool: String,
    val fee: Int,
    /** Stablecoin quote, reached through the native/stable pool with this fee. */
    val via: V3RouterVia? = null,
) : PoolData

data class V3RouterVia(val stable: String, val fee: Int)

private const val ROUTER_MAX_DEADLINE_S = 30 * 60L

/**
 * Constant-product routers with fee-on-transfer support, ported from venues/v2router.ts. Two
 * flavours: Uniswap-v2 style (address[] path) and Aerodrome style (Route[] with a stable flag
 * and a factory). Approvals go to the router, bounded. Tokens quoted in a stablecoin route
 * native → stable → token in the same swap.
 */
class V2RouterVenue(
    override val id: String,
    private val label: String,
    private val chainId: Long,
    val router: String,
    private val factory: String,
    private val aerodrome: Boolean,
) : Venue {
    private val uniAmounts = AbiFunction("function getAmountsOut(uint256 amountIn, address[] path) view returns (uint256[] amounts)")
    private val uniBuy = AbiFunction(
        "function swapExactETHForTokensSupportingFeeOnTransferTokens(uint256 amountOutMin, address[] path, address to, uint256 deadline) payable",
    )
    private val uniSell = AbiFunction(
        "function swapExactTokensForETHSupportingFeeOnTransferTokens(uint256 amountIn, uint256 amountOutMin, address[] path, address to, uint256 deadline)",
    )
    private val aeroAmounts = AbiFunction(
        "function getAmountsOut(uint256 amountIn, (address from, address to, bool stable, address factory)[] routes) view returns (uint256[] amounts)",
    )
    private val aeroBuy = AbiFunction(
        "function swapExactETHForTokensSupportingFeeOnTransferTokens(uint256 amountOutMin, (address from, address to, bool stable, address factory)[] routes, address to, uint256 deadline) payable",
    )
    private val aeroSell = AbiFunction(
        "function swapExactTokensForETHSupportingFeeOnTransferTokens(uint256 amountIn, uint256 amountOutMin, (address from, address to, bool stable, address factory)[] routes, address to, uint256 deadline)",
    )
    private val uniGetPair = AbiFunction("function getPair(address, address) view returns (address)")
    private val aeroGetPool = AbiFunction("function getPool(address, address, bool) view returns (address)")

    override fun supports(chainId: Long) = chainId == this.chainId

    /** Assets in trade order: [native, (stable,) token] for a buy, reversed for a sell. */
    private fun assets(ctx: VenueCtx, ref: PoolRef, side: Side): List<String> {
        val pool = ref.data as V2RouterPool
        val path = listOf(ctx.chain.wrappedNative) + listOfNotNull(pool.via) + ref.token
        return if (side == Side.BUY) path else path.reversed()
    }

    /** Aerodrome routes: the token pool keeps its own stable flag; native/stable is volatile. */
    private fun routes(pool: V2RouterPool, path: List<String>, token: String): List<List<Any>> =
        path.drop(1).mapIndexed { i, to ->
            val from = path[i]
            val tokenHop = sameAddress(from, token) || sameAddress(to, token)
            listOf(from, to, if (tokenHop) pool.stable else false, factory)
        }

    private data class Lookup(val a: String, val b: String, val stable: Boolean)

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val weth = ctx.chain.wrappedNative
        val stables = hopStables(ctx.chain, token)
        val flags = if (aerodrome) listOf(false, true) else listOf(false)
        // Token pools (vs native and vs each stable), then the native/stable pools for the hop.
        val lookups = (listOf(weth) + stables).flatMap { q -> flags.map { Lookup(token, q, it) } } +
            stables.map { Lookup(weth, it, false) }
        val res = ctx.reader.multicall(
            lookups.map { l ->
                if (aerodrome) Call(factory, aeroGetPool.encode(l.a, l.b, l.stable)) else Call(factory, uniGetPair.encode(l.a, l.b))
            },
        )
        val found = lookups.mapIndexedNotNull { i, l ->
            val pair = res[i].decode(if (aerodrome) aeroGetPool else uniGetPair)?.get(0) as String?
            if (pair == null || sameAddress(pair, ZERO_ADDRESS)) null else l to pair
        }
        val bridged = found.filter { sameAddress(it.first.a, weth) }.map { it.first.b.lowercase() }.toSet()
        return found
            .filter { sameAddress(it.first.a, token) }
            .filter { sameAddress(it.first.b, weth) || it.first.b.lowercase() in bridged }
            .map { (l, pair) ->
                val via = if (sameAddress(l.b, weth)) null else l.b
                val kind = if (aerodrome) " · ${if (l.stable) "stable" else "volatile"}" else ""
                PoolRef(
                    id, pair.lowercase(), "$label$kind${via?.let { " via ${stableSymbol(ctx.chain, it)}" } ?: ""}", token,
                    V2RouterPool(pair, l.stable, via),
                )
            }
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val data = pool.data as V2RouterPool
        val path = assets(ctx, pool, side)
        val fn = if (aerodrome) aeroAmounts else uniAmounts
        val res = ctx.reader.multicall(
            amounts.map { a ->
                Call(router, if (aerodrome) aeroAmounts.encode(a, routes(data, path, pool.token)) else uniAmounts.encode(a, path))
            },
        )
        return res.map { r -> (r.decode(fn)?.get(0) as List<*>?)?.lastOrNull() as BigInteger? }
    }

    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft {
        val data = pool.data as V2RouterPool
        val path = assets(ctx, pool, Side.BUY)
        val calldata = if (aerodrome) aeroBuy.encode(p.minOut, routes(data, path, pool.token), p.wallet, p.deadline)
        else uniBuy.encode(p.minOut, path, p.wallet, p.deadline)
        return TxDraft(router, calldata, p.amountIn)
    }

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft {
        val data = pool.data as V2RouterPool
        val path = assets(ctx, pool, Side.SELL)
        val calldata = if (aerodrome) aeroSell.encode(p.amountIn, p.minOut, routes(data, path, pool.token), p.wallet, p.deadline)
        else uniSell.encode(p.amountIn, p.minOut, path, p.wallet, p.deadline)
        return TxDraft(router, calldata, BigInteger.ZERO)
    }

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef) = SellApproval(router, false, router)

    val rule = ContractRule(label, approvalSpender = true) { tx, ctx -> validate(tx, ctx) }

    private fun validate(tx: TxDraft, ctx: ValidationContext): List<BlockedAction> {
        val fns = if (aerodrome) listOf(aeroBuy, aeroSell, aeroAmounts) else listOf(uniBuy, uniSell, uniAmounts)
        val (fn, args) = fns.firstNotNullOfOrNull { f -> runCatching { f.decodeCall(tx.data) }.getOrNull()?.let { f to it } }
            ?: return listOf(act("$label call", tr("Unknown function")))
        if (fn === uniAmounts || fn === aeroAmounts) return listOf(act("getAmountsOut", tr("Not a swap")))
        val buy = fn === uniBuy || fn === aeroBuy
        val minOut = (if (buy) args[0] else args[1]) as BigInteger
        val path = (if (buy) args[1] else args[2]) as List<*>
        val to = (if (buy) args[2] else args[3]) as String
        val deadline = (if (buy) args[3] else args[4]) as BigInteger
        val hops: List<Pair<String, String>> = if (aerodrome) {
            path.map { r -> (r as List<*>).let { it[0] as String to it[1] as String } }
        } else {
            val p = path.map { it as String }
            p.drop(1).mapIndexed { i, t -> p[i] to t }
        }
        val l = "$label ${if (buy) "buy" else "sell"} → ${to.take(6)}…"
        val weth = ctx.chain.wrappedNative
        fun fail(reason: String) = listOf(act(l, reason))
        if (hops.isEmpty() || hops.size > 2) return fail(tr("Only direct swaps or one stablecoin hop are allowed"))
        for (i in 1 until hops.size) if (!sameAddress(hops[i - 1].second, hops[i].first)) return fail(tr("Route hops are not connected"))
        val from = hops.first().first
        val dest = hops.last().second
        val middle = hops.drop(1).map { it.first }
        // Native on one end, the traded token on the other, at most one stablecoin between.
        val (nativeEnd, tokenEnd) = if (buy) from to dest else dest to from
        if (!sameAddress(nativeEnd, weth)) return fail(tr("Unexpected direction"))
        if (ctx.token == null || !sameAddress(tokenEnd, ctx.token)) return fail(tr("Path does not trade the selected token"))
        if (middle.any { !Validator.isStable(ctx.chain, it) }) return fail(tr("Only a stablecoin may sit between native and the token"))
        if (aerodrome && path.any { !sameAddress((it as List<*>)[3] as String, factory) }) return fail(tr("Unknown pool factory"))
        if (!Validator.isOwn(ctx, to)) return fail(tr("Recipient is not one of your wallets"))
        if (minOut.signum() == 0) return fail(tr("No minimum output (no slippage protection)"))
        if (deadline < big(ctx.nowS) || deadline > big(ctx.nowS + ROUTER_MAX_DEADLINE_S)) return fail(tr("Deadline outside the allowed window"))
        if (!buy && tx.value.signum() != 0) return fail(tr("Sell must not carry native value"))
        return listOf(act(l))
    }
}

/**
 * Concentrated-liquidity venues behind a SwapRouter02-style router (PancakeSwap v3
 * SmartRouter), ported from venues/v3router.ts. Buys pay with value (the router wraps); sells
 * swap to the router and unwrap to the wallet in one multicall. Approvals go to the router.
 */
class V3RouterVenue(
    override val id: String,
    private val label: String,
    private val chainId: Long,
    val router: String,
    private val factory: String,
    private val quoter: String,
    private val fees: List<Int>,
) : Venue {
    private val exactInputSingle = AbiFunction(
        "function exactInputSingle((address tokenIn, address tokenOut, uint24 fee, address recipient, uint256 amountIn, uint256 amountOutMinimum, uint160 sqrtPriceLimitX96) params) payable returns (uint256 amountOut)",
    )
    private val exactInput = AbiFunction(
        "function exactInput((bytes path, address recipient, uint256 amountIn, uint256 amountOutMinimum) params) payable returns (uint256 amountOut)",
    )
    private val unwrapWETH9 = AbiFunction("function unwrapWETH9(uint256 amountMinimum, address recipient) payable")
    private val multicall = AbiFunction("function multicall(uint256 deadline, bytes[] data) payable returns (bytes[])")
    private val getPool = AbiFunction("function getPool(address, address, uint24) view returns (address)")
    private val liquidity = AbiFunction("function liquidity() view returns (uint128)")
    private val quoteSingle = AbiFunction(
        "function quoteExactInputSingle((address tokenIn, address tokenOut, uint256 amountIn, uint24 fee, uint160 sqrtPriceLimitX96)) returns (uint256 amountOut, uint160, uint32, uint256)",
    )
    private val quoteMulti = AbiFunction(
        "function quoteExactInput(bytes path, uint256 amountIn) returns (uint256 amountOut, uint160[], uint32[], uint256)",
    )

    override fun supports(chainId: Long) = chainId == this.chainId

    /** Hops in trade order; one hop for native-quoted pools, two through a stable. */
    private fun hops(ctx: VenueCtx, ref: PoolRef, side: Side): List<V3Hop> {
        val pool = ref.data as V3RouterPool
        val weth = ctx.chain.wrappedNative
        val buy = if (pool.via != null) {
            listOf(V3Hop(weth, pool.via.fee, pool.via.stable), V3Hop(pool.via.stable, pool.fee, ref.token))
        } else {
            listOf(V3Hop(weth, pool.fee, ref.token))
        }
        return if (side == Side.BUY) buy else buy.reversed().map { V3Hop(it.tokenOut, it.fee, it.tokenIn) }
    }

    private data class Lookup(val a: String, val b: String, val fee: Int)

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val weth = ctx.chain.wrappedNative
        val stables = hopStables(ctx.chain, token)
        val lookups = (listOf(weth) + stables).flatMap { q -> fees.map { Lookup(token, q, it) } } +
            // Native/stable pools: the hop every stable-quoted route shares.
            stables.flatMap { s -> fees.map { Lookup(weth, s, it) } }
        val found = ctx.reader.multicall(lookups.map { l -> Call(factory, getPool.encode(l.a, l.b, l.fee)) })
        val pools = lookups.mapIndexedNotNull { i, l ->
            val pool = found[i].decode(getPool)?.get(0) as String?
            if (pool == null || sameAddress(pool, ZERO_ADDRESS)) null else l to pool
        }
        if (pools.isEmpty()) return emptyList()
        val liq = ctx.reader.multicall(pools.map { (_, pool) -> Call(pool, liquidity.encode()) })
        val live = pools.mapIndexedNotNull { i, p ->
            val l = liq[i].decode(liquidity)?.get(0) as BigInteger? ?: BigInteger.ZERO
            if (l.signum() > 0) Triple(p.first, p.second, l) else null
        }
        // Per stable, the native/stable pool with the most in-range liquidity.
        val bridge = HashMap<String, Pair<Int, BigInteger>>()
        for ((l, _, liquidity) in live.filter { sameAddress(it.first.a, weth) }) {
            val k = l.b.lowercase()
            if (liquidity > (bridge[k]?.second ?: BigInteger.ZERO)) bridge[k] = l.fee to liquidity
        }
        return live.filter { sameAddress(it.first.a, token) }.mapNotNull { (l, pool, _) ->
            val native = sameAddress(l.b, weth)
            val b = bridge[l.b.lowercase()]
            if (!native && b == null) return@mapNotNull null
            val via = if (native || b == null) null else V3RouterVia(l.b, b.first)
            PoolRef(
                id, pool.lowercase(), "$label · ${percent(l.fee)}%${via?.let { " via ${stableSymbol(ctx.chain, it.stable)}" } ?: ""}",
                token, V3RouterPool(pool, l.fee, via),
            )
        }
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val path = hops(ctx, pool, side)
        val single = path.singleOrNull()
        // The factory's own pools run only the DEX's code and the tokens': shared with other quotes.
        val res = ctx.reader.shared(
            amounts.map { a ->
                if (single != null) Call(quoter, quoteSingle.encode(listOf(single.tokenIn, single.tokenOut, a, single.fee, 0)))
                else Call(quoter, quoteMulti.encode(UniversalRouter.encodeV3Path(path), a))
            },
        )
        return res.map { firstOut(it, if (single != null) quoteSingle else quoteMulti) }
    }

    private fun swap(path: List<V3Hop>, recipient: String, amountIn: BigInteger, minOut: BigInteger): ByteArray {
        val only = path.singleOrNull()
        return if (only != null) {
            exactInputSingle.encode(listOf(only.tokenIn, only.tokenOut, only.fee, recipient, amountIn, minOut, 0))
        } else {
            exactInput.encode(listOf(UniversalRouter.encodeV3Path(path), recipient, amountIn, minOut))
        }
    }

    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft {
        val inner = swap(hops(ctx, pool, Side.BUY), p.wallet, p.amountIn, p.minOut)
        return TxDraft(router, multicall.encode(p.deadline, listOf(inner)), p.amountIn)
    }

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft {
        val inner = listOf(
            swap(hops(ctx, pool, Side.SELL), UniversalRouter.ADDRESS_THIS, p.amountIn, p.minOut),
            unwrapWETH9.encode(p.minOut, p.wallet),
        )
        return TxDraft(router, multicall.encode(p.deadline, inner), BigInteger.ZERO)
    }

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef) = SellApproval(router, false, router)

    /** Native on one end, the traded token on the other, at most one stablecoin between. */
    private fun checkPath(path: List<V3Hop>, ctx: ValidationContext): String? {
        val weth = ctx.chain.wrappedNative
        if (path.isEmpty() || path.size > 2) return tr("Only direct swaps or one stablecoin hop are allowed")
        val ends = listOf(path.first().tokenIn, path.last().tokenOut)
        if (ends.none { sameAddress(it, weth) }) return tr("Unexpected asset")
        if (ctx.token == null || ends.none { sameAddress(it, ctx.token) }) return tr("Path does not trade the selected token")
        if (path.drop(1).any { !Validator.isStable(ctx.chain, it.tokenIn) }) return tr("Only a stablecoin may sit between native and the token")
        return null
    }

    val rule = ContractRule(label, approvalSpender = true) { tx, ctx -> validate(tx, ctx) }

    private val inner = listOf(exactInputSingle, exactInput, unwrapWETH9, multicall)

    private fun validate(tx: TxDraft, ctx: ValidationContext): List<BlockedAction> {
        val out = mutableListOf<BlockedAction>()
        val (outerFn, outer) = inner.firstNotNullOfOrNull { f -> runCatching { f.decodeCall(tx.data) }.getOrNull()?.let { f to it } }
            ?: return listOf(act("$label call", tr("Unknown function")))
        if (outerFn !== multicall) return listOf(act("$label ${outerFn.name}", tr("Only multicall(deadline, …) is allowed")))
        val deadline = outer[0] as BigInteger
        val calls = outer[1] as List<*>
        if (deadline < big(ctx.nowS) || deadline > big(ctx.nowS + ROUTER_MAX_DEADLINE_S)) {
            out += act("Deadline", tr("Deadline outside the allowed window"))
        }
        var paysUser = false
        for (data in calls) {
            val (fn, args) = inner.firstNotNullOfOrNull { f -> runCatching { f.decodeCall(data as ByteArray) }.getOrNull()?.let { f to it } }
                ?: run {
                    out += act(tr("Inner call"), tr("Unknown function"))
                    null
                } ?: continue
            when {
                fn === exactInputSingle || fn === exactInput -> {
                    val s = args[0] as List<*>
                    val path: List<V3Hop>
                    val recipient: String
                    val minOut: BigInteger
                    if (fn === exactInputSingle) {
                        path = listOf(V3Hop(s[0] as String, (s[2] as BigInteger).toInt(), s[1] as String))
                        recipient = s[3] as String
                        minOut = s[5] as BigInteger
                    } else {
                        try {
                            path = UniversalRouter.decodeV3Path(s[0] as ByteArray)
                        } catch (e: Exception) {
                            out += act("$label swap", tr("Malformed path"))
                            continue
                        }
                        recipient = s[1] as String
                        minOut = s[3] as BigInteger
                    }
                    val tokens = listOf(path.firstOrNull()?.tokenIn ?: "?") + path.map { it.tokenOut }
                    val label = "${this.label} swap ${tokens.joinToString(" → ") { "${it.take(6)}…" }}"
                    val toRouter = sameAddress(recipient, UniversalRouter.ADDRESS_THIS)
                    val bad = checkPath(path, ctx)
                    out += when {
                        bad != null -> act(label, bad)
                        !toRouter && !Validator.isOwn(ctx, recipient) -> act(label, tr("Recipient is not one of your wallets"))
                        minOut.signum() == 0 -> act(label, tr("No minimum output (no slippage protection)"))
                        else -> act(label)
                    }
                    if (!toRouter && Validator.isOwn(ctx, recipient)) paysUser = true
                }
                fn === unwrapWETH9 -> {
                    val recipient = args[1] as String
                    val label = tr("Unwrap to {1}…", recipient.take(6))
                    if (Validator.isOwn(ctx, recipient)) {
                        out += act(label)
                        paysUser = true
                    } else {
                        out += act(label, tr("Recipient is not one of your wallets"))
                    }
                }
                else -> out += act(fn.name, tr("Not allowed inside multicall"))
            }
        }
        if (!paysUser) out += act("Output", tr("No step sends the output to your wallet"))
        return out
    }
}

/** Non-Uniswap AMMs, ported from venues.ts. Addresses verified on chain by the extension. */
object RouterVenues {
    val pancakeV2Bsc = V2RouterVenue(
        "pancake-v2-bsc", "PancakeSwap v2", 56,
        "0x10ed43c718714eb63d5aa57b78b54704e256024e", "0xca143ce32fe78f1f7019d7d551a6402fc5350c73", aerodrome = false,
    )
    val pancakeV3Bsc = V3RouterVenue(
        "pancake-v3-bsc", "PancakeSwap v3", 56,
        "0x13f4ea83d0bd40e75c8222255bc855a974568dd4", "0x0bfbcf9fa4f9c56b0f40a671ad40e0805a091865",
        "0xb048bbc1ee6b733fffcfb9e9cef7375518e25997", listOf(100, 500, 2500, 10000),
    )

    // PancakeSwap v3 on Ethereum and Base (Clanker's Ethereum launches pair there).
    val pancakeV3Eth = V3RouterVenue(
        "pancake-v3-eth", "PancakeSwap v3", 1,
        "0x13f4ea83d0bd40e75c8222255bc855a974568dd4", "0x0bfbcf9fa4f9c56b0f40a671ad40e0805a091865",
        "0xb048bbc1ee6b733fffcfb9e9cef7375518e25997", listOf(100, 500, 2500, 10000),
    )
    val pancakeV3Base = V3RouterVenue(
        "pancake-v3-base", "PancakeSwap v3", 8453,
        "0x678aa4bf4e210cf2166753e054d5b7c31cc7fa86", "0x0bfbcf9fa4f9c56b0f40a671ad40e0805a091865",
        "0xb048bbc1ee6b733fffcfb9e9cef7375518e25997", listOf(100, 500, 2500, 10000),
    )
    val aerodromeBase = V2RouterVenue(
        "aerodrome-base", "Aerodrome", 8453,
        "0xcf77a3ba9a5ca399b7c97c74d54e5b1beb874e43", "0x420dd381b31aef6683db6b902084cb0ffece40da", aerodrome = true,
    )

    val venues: List<Venue> = listOf(pancakeV2Bsc, pancakeV3Bsc, pancakeV3Eth, pancakeV3Base, aerodromeBase)

    /** Validator rules for these venues' routers on one chain, by lowercase address. */
    fun rules(chainId: Long): Map<String, ContractRule> {
        val m = LinkedHashMap<String, ContractRule>()
        for (v in venues) {
            if (!v.supports(chainId)) continue
            when (v) {
                is V2RouterVenue -> m[v.router.lowercase()] = v.rule
                is V3RouterVenue -> m[v.router.lowercase()] = v.rule
            }
        }
        return m
    }
}
