package trade.nesty.app.evm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.math.BigInteger
import trade.nesty.app.i18n.tr

/**
 * Bonding-curve launchpads, ported from the extension's launchpads.ts and virtuals.ts. A coin
 * is routed here while it is still on its curve; after graduation it trades on a DEX and the
 * AMM venues pick it up. Curves quoted in an ERC-20 the launchpad cannot buy with native
 * itself are funded through the best native ↔ asset AMM pool (the leg).
 */

/** A quote asset's symbol for route labels (plain text, shortened). */
private val SYMBOL_JUNK = Regex("[^\\p{L}\\p{N}._-]")

suspend fun symbolOf(ctx: VenueCtx, asset: String): String {
    val s = orNull { ctx.reader.read1(asset, Erc20.symbol) } as String? ?: ""
    return s.replace(SYMBOL_JUNK, "").take(12).ifEmpty { "quote" }
}

/** native → asset, then asset → token (buy); token → asset, then asset → native (sell). */
private suspend fun fundedQuote(
    ctx: VenueCtx,
    leg: PoolRef,
    curve: suspend (Side, List<BigInteger>) -> List<BigInteger?>,
    side: Side,
    amounts: List<BigInteger>,
): List<BigInteger?> = if (side == Side.BUY) {
    chainQuotes(amounts, { Legs.quoteLeg(ctx, leg, Side.BUY, it) }, { curve(Side.BUY, it) })
} else {
    chainQuotes(amounts, { curve(Side.SELL, it) }, { Legs.quoteLeg(ctx, leg, Side.SELL, it) })
}

private fun reasonOnly(label: String, reason: String? = null) = listOf(act(label, reason))

private fun decodeAny(fns: List<AbiFunction>, data: ByteArray): Pair<AbiFunction, List<Any?>>? =
    fns.firstNotNullOfOrNull { f -> runCatching { f.decodeCall(data) }.getOrNull()?.let { f to it } }

private fun uint(v: Any?) = v as BigInteger

// ── Legs: native ↔ asset pools for funded curves ──────────────────────────────────

/** Legs found per chain and asset, for ten minutes; prices still come from fresh quotes. */
class LegCache {
    private val mutex = Mutex()
    private val map = HashMap<String, Pair<Long, PoolRef?>>()

    /** A search less than ten minutes old: its leg, or null when it found none. */
    class Hit(val leg: PoolRef?)

    suspend fun lookup(key: String, now: Long): Hit? = mutex.withLock {
        map[key]?.takeIf { now - it.first < TTL_MS }?.let { Hit(it.second) }
    }

    suspend fun store(key: String, now: Long, leg: PoolRef?) {
        mutex.withLock { map[key] = now to leg }
    }

    companion object {
        const val TTL_MS = 10 * 60_000L
    }
}

object Legs {
    /** The AMMs a leg may use (the same list the extension's legs.ts uses). */
    val amms: List<Venue> by lazy { listOf(UniswapVenue) + RouterVenues.venues }

    private fun amm(id: String) = amms.firstOrNull { it.id == id }

    /**
     * Pools the Nesty Router can also sell into: allowance-based routers, or Uniswap pools the
     * Universal Router can swap from its own balance (see buildSellPrefunded).
     */
    private fun routerFriendly(ref: PoolRef): Boolean {
        if (ref.venueId != UniswapVenue.id) return true
        val p = ref.data as UniPool
        if (p is UniV4 || p.via?.v4Key != null) return false
        return !(p is UniV2 && p.via != null)
    }

    /** The native ↔ asset pool that gives the most of the asset for a typical buy. */
    suspend fun findLeg(ctx: VenueCtx, asset: String): PoolRef? = coroutineScope {
        val key = "${ctx.chain.id}:${asset.lowercase()}"
        ctx.legs.lookup(key, ctx.now())?.let { return@coroutineScope it.leg }
        val probe = parseEther(ctx.chain.quickBuyAmounts.getOrNull(1) ?: "0.01")
        val found = amms.filter { it.supports(ctx.chain.id) }.map { v ->
            async {
                val pools = (orNull { v.discover(ctx, asset) } ?: emptyList()).filter { routerFriendly(it) }
                val outs = pools.map { p -> async { orNull { v.quote(ctx, p, Side.BUY, listOf(probe)).firstOrNull() } } }.awaitAll()
                pools.mapIndexed { i, p -> p to outs[i] }
            }
        }.awaitAll().flatten()
        var best: Pair<PoolRef, BigInteger>? = null
        for ((pool, out) in found) {
            if (out != null && out.signum() != 0 && (best == null || out > best.second)) best = pool to out
        }
        val leg = best?.first
        ctx.legs.store(key, ctx.now(), leg)
        leg
    }

    /** Native → asset (buy) or asset → native (sell) quotes on a leg found by findLeg. */
    suspend fun quoteLeg(ctx: VenueCtx, leg: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val venue = amm(leg.venueId) ?: return amounts.map { null }
        return venue.quote(ctx, leg, side, amounts)
    }
}

// ── four.meme (BNB Chain) ────────────────────────────────────────────────────────

/**
 * A tax token's rates (its feeRateBuy / feeRateSell, in percent there) in basis points of
 * each trade's value. On the curve the manager takes them itself, in the quote asset and on
 * top of its fee: a buy of F puts F / (1 + fee + tax) into the curve, a sell gets
 * cost × (1 − fee − tax). tryBuy and trySell count only the fee.
 */
data class FourTax(val buyBps: Int, val sellBps: Int)

data class FourPool(
    /** A curve quoted in a BEP-20 asset: four.meme's helper swaps BNB into it inside the same call. */
    val quote: String? = null,
    /** Only prices that swap. */
    val leg: PoolRef? = null,
    /** four.meme's own fee on each curve trade, in basis points (tradingFeeRate). */
    val feeBps: Int,
    val tax: FourTax? = null,
) : PoolData

object FourMeme : Venue {
    override val id = "fourmeme"
    const val TOKEN_MANAGER = "0x5c952063c7fc8610ffdb798152d69f0b9550762b"
    const val HELPER = "0xf251f83e40a78868fcfa3fa4599dad6494e46034"

    /** four.meme trades token amounts in whole multiples of 1e9 units. */
    private val UNIT = BigInteger.TEN.pow(9)

    private val getTokenInfo = AbiFunction(
        "function getTokenInfo(address token) view returns (uint256 version, address tokenManager, address quote, uint256 lastPrice, uint256 tradingFeeRate, uint256 minTradingFee, uint256 launchTime, uint256 offers, uint256 maxOffers, uint256 funds, uint256 maxFunds, bool liquidityAdded)",
    )
    private val tryBuy = AbiFunction(
        "function tryBuy(address token, uint256 amount, uint256 funds) view returns (address tokenManager, address quote, uint256 estimatedAmount, uint256 estimatedCost, uint256 estimatedFee, uint256 amountMsgValue, uint256 amountApproval, uint256 amountFunds)",
    )
    private val trySell = AbiFunction(
        "function trySell(address token, uint256 amount) view returns (address tokenManager, address quote, uint256 funds, uint256 fee)",
    )
    private val buyTokenAMAP = AbiFunction("function buyTokenAMAP(address token, address to, uint256 funds, uint256 minAmount) payable")
    private val sellToken = AbiFunction("function sellToken(uint256 origin, address token, uint256 amount, uint256 minFunds)")
    private val buyWithEth = AbiFunction(
        "function buyWithEth(uint256 origin, address token, address to, uint256 funds, uint256 minAmount) payable",
    )

    // The manager pulls the tokens from tx.origin: only ever sent by the wallet itself.
    private val sellForEth = AbiFunction(
        "function sellForEth(uint256 origin, address token, uint256 amount, uint256 minFunds, uint256 feeRate, address feeRecipient)",
    )
    private val all = listOf(getTokenInfo, tryBuy, trySell, buyTokenAMAP, sellToken, buyWithEth, sellForEth)

    /** A tax token's rates; an older one has a single feeRate for both. */
    private val feeRateBuy = AbiFunction("function feeRateBuy() view returns (uint256)")
    private val feeRateSell = AbiFunction("function feeRateSell() view returns (uint256)")
    private val feeRate = AbiFunction("function feeRate() view returns (uint256)")

    val rule = ContractRule("four.meme", approvalSpender = true) { tx, ctx ->
        val (fn, a) = decodeAny(all, tx.data) ?: return@ContractRule reasonOnly("four.meme call", tr("Unknown function"))
        when (fn) {
            buyTokenAMAP -> {
                val l = "four.meme buy"
                when {
                    ctx.token == null || !sameAddress(a[0] as String, ctx.token) -> reasonOnly(l, tr("Different token"))
                    !Validator.isOwn(ctx, a[1] as String) -> reasonOnly(l, tr("Recipient is not one of your wallets"))
                    uint(a[3]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    tx.value != uint(a[2]) -> reasonOnly(l, tr("Value does not match funds"))
                    else -> reasonOnly(l)
                }
            }
            sellToken -> {
                val l = "four.meme sell"
                when {
                    uint(a[0]).signum() != 0 -> reasonOnly(l, tr("Unexpected origin"))
                    ctx.token == null || !sameAddress(a[1] as String, ctx.token) -> reasonOnly(l, tr("Different token"))
                    uint(a[3]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    tx.value.signum() != 0 -> reasonOnly(l, tr("Sell must not carry native value"))
                    else -> reasonOnly(l)
                }
            }
            else -> reasonOnly("four.meme ${fn.name}", tr("Not a trade"))
        }
    }

    val helperRule = ContractRule("four.meme helper") { tx, ctx ->
        val (fn, a) = decodeAny(all, tx.data) ?: return@ContractRule reasonOnly("four.meme helper call", tr("Unknown function"))
        when (fn) {
            buyWithEth -> {
                val l = "four.meme buy"
                when {
                    uint(a[0]).signum() != 0 -> reasonOnly(l, tr("Unexpected origin"))
                    ctx.token == null || !sameAddress(a[1] as String, ctx.token) -> reasonOnly(l, tr("Different token"))
                    !Validator.isOwn(ctx, a[2] as String) -> reasonOnly(l, tr("Recipient is not one of your wallets"))
                    uint(a[4]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    tx.value != uint(a[3]) -> reasonOnly(l, tr("Value does not match funds"))
                    else -> reasonOnly(l)
                }
            }
            sellForEth -> {
                val l = "four.meme sell"
                when {
                    uint(a[0]).signum() != 0 -> reasonOnly(l, tr("Unexpected origin"))
                    ctx.token == null || !sameAddress(a[1] as String, ctx.token) -> reasonOnly(l, tr("Different token"))
                    uint(a[3]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    uint(a[4]).signum() != 0 || !sameAddress(a[5] as String, ZERO_ADDRESS) -> reasonOnly(l, tr("Pays a fee to a third party"))
                    tx.value.signum() != 0 -> reasonOnly(l, tr("Sell must not carry native value"))
                    else -> reasonOnly(l)
                }
            }
            else -> reasonOnly("four.meme ${fn.name}", tr("Not a trade"))
        }
    }

    private fun pool(ref: PoolRef) = ref.data as FourPool

    private class State(val info: List<Any?>, val feeBps: Int, val tax: FourTax?)

    /** getTokenInfo and, for a tax token, its rates, in one request. */
    private suspend fun state(ctx: VenueCtx, token: String): State? {
        val r = orNull {
            ctx.reader.multicall(
                listOf(
                    Call(HELPER, getTokenInfo.encode(token)),
                    Call(token, feeRateBuy.encode()),
                    Call(token, feeRateSell.encode()),
                    Call(token, feeRate.encode()),
                ),
            )
        } ?: return null
        val info = r[0].decode(getTokenInfo) ?: return null
        val b = r[1].decode(feeRateBuy)?.get(0) as BigInteger?
        val s = r[2].decode(feeRateSell)?.get(0) as BigInteger?
        val o = r[3].decode(feeRate)?.get(0) as BigInteger?
        val buyPct = b ?: o ?: BigInteger.ZERO
        val sellPct = if (b == null) o ?: BigInteger.ZERO else s ?: BigInteger.ZERO
        val feeBps = uint(info[4]).toInt()
        val hundred = big(100)
        val tax = if ((buyPct.signum() > 0 || sellPct.signum() > 0) && buyPct <= hundred && sellPct <= hundred && feeBps < 10_000) {
            FourTax(buyPct.toInt() * 100, sellPct.toInt() * 100)
        } else {
            null
        }
        return State(info, feeBps, tax)
    }

    /** What tryBuy must be given for `funds` to price the curve's real share of them. */
    private fun fundsForTryBuy(funds: BigInteger, feeBps: Int, t: FourTax) = funds * big(10_000 + feeBps) / big(10_000 + feeBps + t.buyBps)

    /** trySell's funds less the tax, which is on the trade's cost: funds plus fee. */
    private fun afterSellTax(funds: BigInteger, fee: BigInteger, t: FourTax): BigInteger {
        val net = funds - (funds + fee) * big(t.sellBps) / big(10_000)
        return if (net.signum() > 0) net else BigInteger.ZERO
    }

    private suspend fun curveQuote(ctx: VenueCtx, ref: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val token = ref.token
        val p = pool(ref)
        val res = ctx.reader.multicall(
            amounts.map { a ->
                if (side == Side.BUY) Call(HELPER, tryBuy.encode(token, 0, p.tax?.let { fundsForTryBuy(a, p.feeBps, it) } ?: a))
                else Call(HELPER, trySell.encode(token, a / UNIT * UNIT))
            },
        )
        return res.map { r ->
            val v = r.decode(if (side == Side.BUY) tryBuy else trySell) ?: return@map null
            val out = uint(v[2])
            if (side == Side.SELL && p.tax != null) afterSellTax(out, uint(v[3]), p.tax) else out
        }
    }

    override fun supports(chainId: Long) = chainId == 56L

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val st = state(ctx, token) ?: return emptyList()
        val info = st.info
        if (uint(info[0]) != big(2) || !sameAddress(info[1] as String, TOKEN_MANAGER) || info[11] == true) return emptyList()
        val id = "four:${token.lowercase()}"
        val quote = info[2] as String
        if (sameAddress(quote, ZERO_ADDRESS)) return listOf(PoolRef(this.id, id, "four.meme curve", token, FourPool(feeBps = st.feeBps, tax = st.tax)))
        val leg = Legs.findLeg(ctx, quote) ?: return emptyList()
        return listOf(
            PoolRef(this.id, id, "four.meme curve via ${symbolOf(ctx, quote)}", token, FourPool(quote, leg, st.feeBps, st.tax)),
        )
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val leg = pool(pool).leg
        val curve: suspend (Side, List<BigInteger>) -> List<BigInteger?> = { s, a -> curveQuote(ctx, pool, s, a) }
        return if (leg != null) fundedQuote(ctx, leg, curve, side, amounts) else curve(side, amounts)
    }

    // four.meme's fee and a tax token's buy or sell rate, already in quotes, counted as tax.
    override suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax {
        val p = pool(pool)
        return VenueTax(p.feeBps + (p.tax?.buyBps ?: 0), p.feeBps + (p.tax?.sellBps ?: 0))
    }

    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft =
        if (pool(pool).quote != null) TxDraft(HELPER, buyWithEth.encode(0, pool.token, p.wallet, p.amountIn, p.minOut), p.amountIn)
        else TxDraft(TOKEN_MANAGER, buyTokenAMAP.encode(pool.token, p.wallet, p.amountIn, p.minOut), p.amountIn)

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft {
        val amount = p.amountIn / UNIT * UNIT
        return if (pool(pool).quote != null) {
            TxDraft(HELPER, sellForEth.encode(0, pool.token, amount, p.minOut, 0, ZERO_ADDRESS), BigInteger.ZERO)
        } else {
            TxDraft(TOKEN_MANAGER, sellToken.encode(0, pool.token, amount, p.minOut), BigInteger.ZERO)
        }
    }

    // A sold-out curve stops trading until the manager adds the PancakeSwap liquidity.
    override suspend fun idle(ctx: VenueCtx, token: String): String? {
        val info = orNull { ctx.reader.read(HELPER, getTokenInfo, token) } ?: return null
        if (uint(info[0]) != big(2) || !sameAddress(info[1] as String, TOKEN_MANAGER) || info[11] == true) return null
        return if (uint(info[7]).signum() == 0 || uint(info[9]) >= uint(info[10])) tr("four.meme curve full · waiting for DEX") else null
    }

    // The manager pulls the tokens either way.
    override fun sellApproval(ctx: VenueCtx, pool: PoolRef) =
        SellApproval(TOKEN_MANAGER, false, if (pool(pool).quote != null) HELPER else TOKEN_MANAGER)

    override fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> =
        if (pool(pool).quote != null) listOf(TOKEN_MANAGER to rule, HELPER to helperRule) else listOf(TOKEN_MANAGER to rule)
}

// ── Flap (BNB Chain, Base, Robinhood) ──────────────────────────────────────────────

data class FlapPool(
    val portal: String,
    /** A curve quoted in an ERC-20 the portal cannot buy with native itself, funded through `leg`. */
    val quote: String? = null,
    val symbol: String? = null,
    val leg: PoolRef? = null,
) : PoolData

object Flap : Venue {
    override val id = "flap"

    /** Portals per chain (Flap redeployed on Base; older coins stay on the old one). */
    val PORTALS = mapOf(
        56L to listOf("0xe2ce6ab80874fa9fa2aae65d277dd6b8e65c9de0"),
        8453L to listOf("0x0000bc1c4fd15dd79029af8f5d77d68ae4490000", "0x00214f8c22a076575f4b67f3b4aa62f99e166e36"),
        4663L to listOf("0x26605f322f7ff986f381bb9a6e3f5dab0beaeb09"),
    )

    /** getTokenV3 status: 1 = trading on the curve. */
    private const val TRADABLE = 1

    /** Flap's fee on each curve trade (1% of its value). */
    private const val FEE_BPS = 100

    /** An untaxed TokenV3 (getTokenV8Safe version 7): no Flap fee, 1.25% for its tax processor. */
    private const val UNTAXED_V3 = 7
    private const val UNTAXED_V3_FEE_BPS = 125

    private val getTokenV3 = AbiFunction(
        "function getTokenV3(address token) view returns (uint8 status, uint256 reserve, uint256 circulatingSupply, uint256 price, uint8 tokenVersion, uint256 r, uint256 dexSupplyThresh, address quoteToken, bool nativeToQuoteSwapEnabled)",
    )
    private val getTokenV8Safe = AbiFunction(
        "function getTokenV8Safe(address token) view returns ((uint8 status, uint256 reserve, uint256 circulatingSupply, uint256 price, uint8 tokenVersion, uint256 r, uint256 h, uint256 k, uint256 dexSupplyThresh, address quoteTokenAddress, bool nativeToQuoteSwapEnabled, bytes32 extensionID, uint256 buyTaxRate, uint256 sellTaxRate, address pool, uint256 progress, uint8 lpFeeProfile, uint8 dexId))",
    )
    private val quoteExactInput = AbiFunction("function quoteExactInput((address inputToken, address outputToken, uint256 inputAmount)) returns (uint256)")
    private val swapExactInput = AbiFunction(
        "function swapExactInput((address inputToken, address outputToken, uint256 inputAmount, uint256 minOutputAmount, bytes permitData)) payable returns (uint256)",
    )
    private val all = listOf(getTokenV3, getTokenV8Safe, quoteExactInput, swapExactInput)

    fun rule(quote: String?): ContractRule {
        // What the curve trades against: native (address 0) or the pool's ERC-20.
        val asset = quote ?: ZERO_ADDRESS
        return ContractRule("Flap", approvalSpender = true) { tx, ctx ->
            val (fn, a) = decodeAny(all, tx.data) ?: return@ContractRule reasonOnly(tr("Flap call"), tr("Unknown function"))
            if (fn !== swapExactInput) return@ContractRule reasonOnly("Flap ${fn.name}", tr("Not a trade"))
            val s = a[0] as List<*>
            val l = tr("Flap swap")
            val input = s[0] as String
            val output = s[1] as String
            fun isTok(x: String) = ctx.token != null && sameAddress(x, ctx.token)
            val buy = sameAddress(input, asset) && isTok(output)
            val sell = isTok(input) && sameAddress(output, asset)
            when {
                !buy && !sell -> reasonOnly(l, tr("Unexpected assets"))
                (s[4] as ByteArray).isNotEmpty() -> reasonOnly(l, tr("Embedded permits are not allowed"))
                uint(s[3]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                buy && tx.value != (if (quote != null) BigInteger.ZERO else uint(s[2])) -> reasonOnly(l, tr("Value does not match input"))
                sell && tx.value.signum() != 0 -> reasonOnly(l, tr("Sell must not carry native value"))
                // Output goes to msg.sender (the validated `from`).
                else -> reasonOnly(l)
            }
        }
    }

    private fun pool(ref: PoolRef) = ref.data as FlapPool

    /** Curve quotes in the curve's own asset (native, or the pool's ERC-20). */
    private suspend fun curveQuote(ctx: VenueCtx, ref: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val p = pool(ref)
        val asset = p.quote ?: ZERO_ADDRESS
        val (input, output) = if (side == Side.BUY) asset to ref.token else ref.token to asset
        val res = ctx.reader.multicall(amounts.map { a -> Call(p.portal, quoteExactInput.encode(listOf(input, output, a))) })
        return res.map { it.decode(quoteExactInput)?.get(0) as BigInteger? }
    }

    private fun swap(ref: PoolRef, side: Side, p: BuyParams): TxDraft {
        val d = pool(ref)
        val asset = d.quote ?: ZERO_ADDRESS
        val (input, output) = if (side == Side.BUY) asset to ref.token else ref.token to asset
        return TxDraft(
            d.portal,
            swapExactInput.encode(listOf(input, output, p.amountIn, p.minOut, ByteArray(0))),
            if (side == Side.BUY && d.quote == null) p.amountIn else BigInteger.ZERO,
        )
    }

    override fun supports(chainId: Long) = chainId in PORTALS

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> = coroutineScope {
        val portals = PORTALS[ctx.chain.id].orEmpty()
        val infos = portals.map { portal -> async { orNull { ctx.reader.read(portal, getTokenV3, token) } } }.awaitAll()
        val live = infos.map { info -> if (info != null && uint(info[0]).toInt() == TRADABLE) info else null }
        val id = "flap:${token.lowercase()}"
        // Native in and out: native-quoted, or the portal swaps native into the quote itself.
        val direct = live.indexOfFirst { it != null && (sameAddress(it[7] as String, ZERO_ADDRESS) || it[8] == true) }
        if (direct >= 0) return@coroutineScope listOf(PoolRef(this@Flap.id, id, "Flap curve", token, FlapPool(portals[direct])))
        val at = live.indexOfFirst { it != null }
        if (at < 0) return@coroutineScope emptyList()
        val info = live[at]!!
        val quote = info[7] as String
        val leg = Legs.findLeg(ctx, quote) ?: return@coroutineScope emptyList()
        val symbol = symbolOf(ctx, quote)
        listOf(PoolRef(this@Flap.id, id, "Flap curve via $symbol", token, FlapPool(portals[at], quote, symbol, leg)))
    }

    /** Flap's fee and a tax token's rates, read from the portal (it keeps them for every version). */
    override suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax {
        val r = ctx.reader.read(pool(pool).portal, getTokenV8Safe, pool.token) ?: throw IOException("getTokenV8Safe failed")
        val s = r[0] as List<*>
        if (uint(s[4]).toInt() == UNTAXED_V3) return VenueTax(UNTAXED_V3_FEE_BPS, UNTAXED_V3_FEE_BPS)
        fun rate(bps: BigInteger) = (if (bps < big(10_000)) bps else big(10_000)).toInt()
        return VenueTax(FEE_BPS + rate(uint(s[12])), FEE_BPS + rate(uint(s[13])))
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val leg = pool(pool).leg
        val curve: suspend (Side, List<BigInteger>) -> List<BigInteger?> = { s, a -> curveQuote(ctx, pool, s, a) }
        return if (leg != null) fundedQuote(ctx, leg, curve, side, amounts) else curve(side, amounts)
    }

    // On a funded pool, amounts are in the quote asset (buy) or tokens (sell).
    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams) = swap(pool, Side.BUY, p)
    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams) = swap(pool, Side.SELL, p.asBuy())

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef): SellApproval {
        val portal = pool(pool).portal
        return SellApproval(portal, false, portal)
    }

    override fun funding(ctx: VenueCtx, pool: PoolRef): Funding? {
        val p = pool(pool)
        if (p.quote == null || p.leg == null) return null
        return Funding(p.quote, p.symbol ?: "quote", p.leg) { c, side, amounts -> curveQuote(c, pool, side, amounts) }
    }

    override fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> {
        val p = pool(pool)
        return listOf(p.portal to rule(p.quote))
    }
}

// ── Pons (Robinhood Chain) ─────────────────────────────────────────────────────

data class PonsPool(
    val curve: String,
    /** A curve quoted in an ERC-20 (USDG): it trades in that asset, funded through `leg`. */
    val quote: String? = null,
    val symbol: String? = null,
    val leg: PoolRef? = null,
) : PoolData

object Pons : Venue {
    override val id = "pons"
    const val FACTORY = "0x7ed598bcef8bd9edd8c97a195c6d13f40801ec7e"

    private val tokenCurve = AbiFunction("function curve() view returns (address)")
    private val token = AbiFunction("function token() view returns (address)")
    private val factory = AbiFunction("function factory() view returns (address)")
    private val graduated = AbiFunction("function graduated() view returns (bool)")
    private val isNativeQuote = AbiFunction("function isNativeQuote() view returns (bool)")
    private val pairToken = AbiFunction("function pairToken() view returns (address)")
    private val getReserves = AbiFunction("function getReserves() view returns (uint256 quoteReserve, uint256 tokenReserve)")
    private val feeBps = AbiFunction("function feeBps() view returns (uint256)")
    private val creatorTaxBps = AbiFunction("function creatorTaxBps() view returns (uint256)")
    private val snipeTax = AbiFunction("function currentSnipeTaxBps(address recipient) view returns (uint256)")
    private val buy = AbiFunction("function buy(uint256 quoteIn, uint256 minTokensOut, address recipient) payable returns (uint256 tokensOut)")
    private val sell = AbiFunction("function sell(uint256 tokensIn, uint256 minQuoteOut, address recipient) returns (uint256 quoteOut)")
    private val all = listOf(token, factory, graduated, isNativeQuote, pairToken, getReserves, feeBps, creatorTaxBps, snipeTax, buy, sell)

    fun rule(nativeQuote: Boolean) = ContractRule("Pons curve", approvalSpender = true) { tx, ctx ->
        val (fn, a) = decodeAny(all, tx.data) ?: return@ContractRule reasonOnly(tr("Pons call"), tr("Unknown function"))
        when (fn) {
            buy -> {
                val l = tr("Pons buy")
                when {
                    !Validator.isOwn(ctx, a[2] as String) -> reasonOnly(l, tr("Recipient is not one of your wallets"))
                    uint(a[1]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    tx.value != (if (nativeQuote) uint(a[0]) else BigInteger.ZERO) -> reasonOnly(l, tr("Value does not match input"))
                    else -> reasonOnly(l)
                }
            }
            sell -> {
                val l = tr("Pons sell")
                when {
                    !Validator.isOwn(ctx, a[2] as String) -> reasonOnly(l, tr("Recipient is not one of your wallets"))
                    uint(a[1]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
                    tx.value.signum() != 0 -> reasonOnly(l, tr("Sell must not carry native value"))
                    else -> reasonOnly(l)
                }
            }
            else -> reasonOnly("Pons ${fn.name}", tr("Not a trade"))
        }
    }

    /** Constant product on the curve's (virtual) reserves, after fee and taxes. */
    private fun curveOut(side: Side, amountIn: BigInteger, quoteReserve: BigInteger, tokenReserve: BigInteger, totalFeeBps: BigInteger): BigInteger {
        if (amountIn.signum() <= 0 || quoteReserve.signum() <= 0 || tokenReserve.signum() <= 0) return BigInteger.ZERO
        val tenK = big(10_000)
        if (side == Side.BUY) {
            val net = amountIn * (tenK - totalFeeBps) / tenK
            return tokenReserve * net / (quoteReserve + net)
        }
        val gross = quoteReserve * amountIn / (tokenReserve + amountIn)
        return gross * (tenK - totalFeeBps) / tenK
    }

    /** All of a multicall's answers, or an exception when one failed (allowFailure: false). */
    private suspend fun strict(ctx: VenueCtx, calls: List<Pair<String, AbiFunction>>, args: List<List<Any?>>): List<List<Any?>> {
        val res = ctx.reader.multicall(calls.mapIndexed { i, (to, fn) -> Call(to, fn.encodeList(args[i])) })
        return res.mapIndexed { i, r -> r.decode(calls[i].second) ?: throw IOException("${calls[i].second.name} failed") }
    }

    private suspend fun fees(ctx: VenueCtx, curve: String): BigInteger {
        val r = strict(ctx, listOf(curve to feeBps, curve to creatorTaxBps, curve to snipeTax), listOf(emptyList(), emptyList(), listOf(ZERO_ADDRESS)))
        return uint(r[0][0]) + uint(r[1][0]) + uint(r[2][0])
    }

    /** Curve quotes in the curve's own asset (native, or the pool's ERC-20). */
    private suspend fun curveQuote(ctx: VenueCtx, curve: String, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val r = strict(
            ctx,
            listOf(curve to getReserves, curve to feeBps, curve to creatorTaxBps, curve to snipeTax),
            listOf(emptyList(), emptyList(), emptyList(), listOf(ZERO_ADDRESS)),
        )
        val q = uint(r[0][0])
        val t = uint(r[0][1])
        val total = uint(r[1][0]) + uint(r[2][0]) + uint(r[3][0])
        return amounts.map { curveOut(side, it, q, t, total) }
    }

    override fun supports(chainId: Long) = chainId == 4663L

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val curve = orNull { ctx.reader.read1(token, tokenCurve) } as String?
        if (curve == null || sameAddress(curve, ZERO_ADDRESS)) return emptyList()
        val fns = listOf(this.token, factory, graduated, isNativeQuote, pairToken)
        val r = ctx.reader.multicall(fns.map { Call(curve, it.encode()) }).mapIndexed { i, x -> x.decode(fns[i])?.get(0) }
        val (t, f, g, n, q) = r
        if (t !is String || !sameAddress(t, token)) return emptyList()
        if (f !is String || !sameAddress(f, FACTORY)) return emptyList()
        if (g != false || n !is Boolean) return emptyList()
        val id = "pons:${curve.lowercase()}"
        if (n) return listOf(PoolRef(this.id, id, "Pons curve", token, PonsPool(curve)))
        val quote = q as String?
        if (quote == null || sameAddress(quote, ZERO_ADDRESS)) return emptyList()
        val leg = Legs.findLeg(ctx, quote) ?: return emptyList()
        val symbol = symbolOf(ctx, quote)
        return listOf(PoolRef(this.id, id, "Pons curve via $symbol", token, PonsPool(curve, quote, symbol, leg)))
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val p = pool.data as PonsPool
        val own: suspend (Side, List<BigInteger>) -> List<BigInteger?> = { s, a -> curveQuote(ctx, p.curve, s, a) }
        return if (p.leg != null) fundedQuote(ctx, p.leg, own, side, amounts) else own(side, amounts)
    }

    // On a funded pool, amounts are in the quote asset (buy) or tokens (sell).
    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft {
        val d = pool.data as PonsPool
        return TxDraft(d.curve, buy.encode(p.amountIn, p.minOut, p.wallet), if (d.quote != null) BigInteger.ZERO else p.amountIn)
    }

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft {
        val d = pool.data as PonsPool
        return TxDraft(d.curve, sell.encode(p.amountIn, p.minOut, p.wallet), BigInteger.ZERO)
    }

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef): SellApproval {
        val curve = (pool.data as PonsPool).curve
        return SellApproval(curve, false, curve)
    }

    override fun funding(ctx: VenueCtx, pool: PoolRef): Funding? {
        val p = pool.data as PonsPool
        if (p.quote == null || p.leg == null) return null
        return Funding(p.quote, p.symbol ?: "quote", p.leg) { c, side, amounts -> curveQuote(c, p.curve, side, amounts) }
    }

    // Fee, creator tax and snipe tax apply to either side; the platform fee counts too.
    override suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax {
        val bps = fees(ctx, (pool.data as PonsPool).curve).toInt()
        return VenueTax(bps, bps)
    }

    override fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> {
        val p = pool.data as PonsPool
        return listOf(p.curve to rule(p.quote == null))
    }
}

// ── Montra (Elysium) ───────────────────────────────────────────────────────────

data class MontraPool(val key: PoolKey) : PoolData

/**
 * Montra has no curve: a coin trades for life in one Uniswap v4 pool carrying Montra's hook.
 * The hook takes the pool's fee inside the swap; trades go through Montra's LaunchRouter.
 */
object Montra : Venue {
    override val id = "montra"
    const val FACTORY = "0x38d3ef3a06d18fa3286fb5deafe611be725fcd15"
    const val ROUTER = "0xc6e9a8e1a99023b67067b8a3605403b8d7b3a157"

    /** The hooks Montra's registry lists; a pool with any other hook is not Montra's. */
    val HOOKS = listOf("0x6b1216a40b65c83e9c9e18a562c446697b8cf8ec")
    private const val CHAIN = 99901L

    /** Every Montra key has the dynamic-fee flag and its quote's spacing. */
    private const val FEE = 0x800000
    private val SPACINGS = listOf(200, 100, 60, 10, 1)
    private val MAX_UINT128: BigInteger = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
    private const val MAX_DEADLINE_S = 30 * 60L

    private val factory = AbiFunction("function factory() view returns (address)")
    private val hook = AbiFunction("function hook() view returns (address)")
    private val poolId = AbiFunction("function poolId() view returns (bytes32)")
    private val buyTaxRate = AbiFunction("function buyTaxRate() view returns (uint256)")
    private val sellTaxRate = AbiFunction("function sellTaxRate() view returns (uint256)")
    private val quoteExactIn = AbiFunction(
        "function quoteExactIn((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks) key, bool zeroForOne, uint128 amountIn) returns (uint256)",
    )
    private val swapExactIn = AbiFunction(
        "function swapExactIn(((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks) key, bool zeroForOne, uint128 amountIn, uint128 minAmountOut, address recipient, uint256 deadline) s) payable returns (uint256)",
    )

    private fun isHook(a: String) = HOOKS.any { sameAddress(it, a) }

    val rule = ContractRule("Montra router", approvalSpender = true) { tx, ctx ->
        val (fn, a) = decodeAny(listOf(quoteExactIn, swapExactIn), tx.data) ?: return@ContractRule reasonOnly(tr("Montra call"), tr("Unknown function"))
        if (fn !== swapExactIn) return@ContractRule reasonOnly("Montra ${fn.name}", tr("Not a trade"))
        val s = a[0] as List<*>
        val key = PoolKey.of(s[0])
        val buy = s[1] as Boolean
        val amountIn = uint(s[2])
        val l = if (buy) tr("Montra buy") else tr("Montra sell")
        val deadline = uint(s[5])
        when {
            !isHook(key.hooks) -> reasonOnly(l, tr("Not a Montra pool"))
            !sameAddress(key.currency0, ZERO_ADDRESS) -> reasonOnly(l, tr("Pool is not paired with native"))
            ctx.token != null && !sameAddress(key.currency1, ctx.token) -> reasonOnly(l, tr("Pool is not the token being traded"))
            !Validator.isOwn(ctx, s[4] as String) -> reasonOnly(l, tr("Recipient is not one of your wallets"))
            uint(s[3]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
            deadline < big(ctx.nowS) || deadline > big(ctx.nowS + MAX_DEADLINE_S) -> reasonOnly(l, tr("Deadline out of range"))
            buy && tx.value != amountIn -> reasonOnly(l, tr("Value does not match input"))
            !buy && tx.value.signum() != 0 -> reasonOnly(l, tr("Sell must not carry native value"))
            else -> reasonOnly(l)
        }
    }

    private fun swap(ref: PoolRef, zeroForOne: Boolean, p: BuyParams): TxDraft {
        val key = (ref.data as MontraPool).key
        return TxDraft(
            ROUTER,
            swapExactIn.encode(listOf(key.abi(), zeroForOne, p.amountIn, p.minOut, p.wallet, p.deadline)),
            if (zeroForOne) p.amountIn else BigInteger.ZERO,
        )
    }

    override fun supports(chainId: Long) = chainId == CHAIN

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val fns = listOf(factory, hook, poolId)
        val r = ctx.reader.multicall(fns.map { Call(token, it.encode()) }).mapIndexed { i, x -> x.decode(fns[i])?.get(0) }
        val f = r[0] as String?
        val h = r[1] as String?
        val id = (r[2] as ByteArray?)?.toHex()
        if (f == null || !sameAddress(f, FACTORY)) return emptyList()
        if (h == null || !isHook(h)) return emptyList()
        if (id == null) return emptyList()
        // Native-quoted pools only: HYPE (address 0) is always currency0, and the key is
        // proved by hashing it to the coin's own pool id.
        val key = SPACINGS.map { PoolKey(ZERO_ADDRESS, token.lowercase(), FEE, it, h.lowercase()) }.firstOrNull { poolIdOf(it) == id }
            ?: return emptyList()
        return listOf(PoolRef(this.id, "montra:$id", "Montra", token, MontraPool(key)))
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val key = (pool.data as MontraPool).key
        val usable = amounts.map { it.signum() > 0 && it <= MAX_UINT128 }
        val asked = amounts.filterIndexed { i, _ -> usable[i] }
        if (asked.isEmpty()) return amounts.map { null }
        // quoteExactIn runs the swap inside the PoolManager and undoes it: an eth_call.
        val r = ctx.reader.multicall(asked.map { a -> Call(ROUTER, quoteExactIn.encode(key.abi(), side == Side.BUY, a)) })
        var next = 0
        return usable.map { ok -> if (!ok) null else r.getOrNull(next++)?.decode(quoteExactIn)?.get(0) as BigInteger? }
    }

    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams) = swap(pool, true, p)
    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams) = swap(pool, false, p.asBuy())
    override fun sellApproval(ctx: VenueCtx, pool: PoolRef) = SellApproval(ROUTER, false, ROUTER)

    // The coin reads its pool's rates in basis points; a buy's is higher in an anti-snipe window.
    override suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax {
        val fns = listOf(buyTaxRate, sellTaxRate)
        val r = ctx.reader.multicall(fns.map { Call(pool.token, it.encode()) })
        val v = r.mapIndexed { i, x -> x.decode(fns[i])?.get(0) as BigInteger? ?: throw IOException("${fns[i].name} failed") }
        return VenueTax(v[0].toInt(), v[1].toInt())
    }

    override fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> = listOf(ROUTER to rule)
}

// ── Virtuals (Base) ────────────────────────────────────────────────────────────

/**
 * Virtuals bonding curves (BondingV5 + FRouterV3). They trade in VIRTUAL, not native: quotes
 * chain the WETH/VIRTUAL Uniswap pool with the curve, and the Nesty Router runs the native ↔
 * VIRTUAL swap around the curve trade.
 */
object Virtuals : Venue {
    override val id = "virtuals"

    private class Deployment(
        val bonding: String,
        /** FRouterV3: pulls VIRTUAL on buys and the coin on sells, so it is the spender. */
        val router: String,
        val asset: String,
        /** WETH/VIRTUAL Uniswap v3 pool used for funding. */
        val bridgePool: String,
        val bridgeFee: Int,
    )

    private val DEPLOYMENTS = mapOf(
        8453L to Deployment(
            "0x1a540088125d00dd3990f9da45ca0859af4d3b01",
            "0x02fe8ec3d9bbf7318eb54590bcc39198a8b47ded",
            "0x0b3e328455c4059eeb9e3f84b5543f74e24e7e1b",
            "0x9c087eb773291e50cf6c6a90ef0f4500e349b903",
            500,
        ),
    )

    private val buy = AbiFunction("function buy(uint256 amountIn, address tokenAddress, uint256 amountOutMin, uint256 deadline) payable returns (bool)")
    private val sell = AbiFunction("function sell(uint256 amountIn, address tokenAddress, uint256 amountOutMin, uint256 deadline) returns (bool)")
    private val tokenInfo = AbiFunction(
        "function tokenInfo(address) view returns (address creator, address token, address pair, address agentToken, (address token, string name, string _name, string ticker, uint256 supply, uint256 price, uint256 marketCap, uint256 liquidity, uint256 volume, uint256 volume24H, uint256 prevPrice, uint256 lastUpdated) data, string description, string image, string twitter, string telegram, string youtube, string website, bool trading, bool tradingOnUniswap, uint256 applicationId, uint256 initialPurchase, uint256 virtualId, bool launchExecuted)",
    )
    private val getAmountsOut = AbiFunction("function getAmountsOut(address token, address assetToken_, uint256 amountIn) view returns (uint256)")
    private const val DEADLINE_MAX_S = 30 * 60L

    private val bondingRule = ContractRule("Virtuals curve") { tx, ctx ->
        val (fn, a) = decodeAny(listOf(buy, sell, tokenInfo), tx.data) ?: return@ContractRule reasonOnly(tr("Virtuals call"), tr("Unknown function"))
        if (fn !== buy && fn !== sell) return@ContractRule reasonOnly("Virtuals ${fn.name}", tr("Not a trade"))
        val l = "Virtuals ${fn.name}"
        val deadline = uint(a[3])
        when {
            ctx.token == null || !sameAddress(a[1] as String, ctx.token) -> reasonOnly(l, tr("Wrong token"))
            tx.value.signum() != 0 -> reasonOnly(l, tr("Must not carry native value (paid in VIRTUAL)"))
            uint(a[0]).signum() == 0 -> reasonOnly(l, tr("Zero amount"))
            uint(a[2]).signum() == 0 -> reasonOnly(l, tr("No minimum output (no slippage protection)"))
            deadline < big(ctx.nowS) || deadline > big(ctx.nowS + DEADLINE_MAX_S) -> reasonOnly(l, tr("Deadline outside the allowed window"))
            // The curve pays msg.sender (the validated `from`).
            else -> reasonOnly(l)
        }
    }

    /** The router is only ever an approval spender, never called directly. */
    private val spenderRule = ContractRule("Virtuals router", approvalSpender = true) { _, _ ->
        reasonOnly(tr("Call to the Virtuals router"), tr("Only approvals are allowed"))
    }

    private fun deployment(ctx: VenueCtx) = DEPLOYMENTS.getValue(ctx.chain.id)

    private fun bridgeRef(ctx: VenueCtx, d: Deployment) = PoolRef(
        UniswapVenue.id, d.bridgePool.lowercase(), "Uniswap v3", d.asset,
        UniV3(d.bridgePool, ctx.chain.wrappedNative, d.bridgeFee),
    )

    private suspend fun curveQuote(ctx: VenueCtx, d: Deployment, token: String, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        if (amounts.isEmpty()) return emptyList()
        // getAmountsOut(token, asset, x) quotes asset → token; (token, 0, x) quotes token → asset.
        val res = ctx.reader.multicall(
            amounts.map { a -> Call(d.router, getAmountsOut.encode(token, if (side == Side.BUY) d.asset else ZERO_ADDRESS, a)) },
        )
        return res.map { it.decode(getAmountsOut)?.get(0) as BigInteger? }
    }

    override fun supports(chainId: Long) = chainId in DEPLOYMENTS

    override suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef> {
        val d = DEPLOYMENTS[ctx.chain.id] ?: return emptyList()
        val info = orNull { ctx.reader.read(d.bonding, tokenInfo, token) } ?: return emptyList()
        val trading = info[11] == true
        val onUniswap = info[12] == true
        val launched = info[16] == true
        if (!sameAddress(info[1] as String, token) || !trading || onUniswap || !launched) return emptyList()
        return listOf(PoolRef(id, "virtuals:${token.lowercase()}", "Virtuals curve via VIRTUAL", token, NoPoolData))
    }

    override suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?> {
        val d = deployment(ctx)
        val bridge = bridgeRef(ctx, d)
        fun pick(xs: List<BigInteger?>) = xs.map { x -> x?.takeIf { it.signum() > 0 } }
        val result = MutableList<BigInteger?>(amounts.size) { null }
        if (side == Side.BUY) {
            val asset = pick(UniswapVenue.quote(ctx, bridge, Side.BUY, amounts))
            val live = asset.indices.filter { asset[it] != null }
            val outs = curveQuote(ctx, d, pool.token, Side.BUY, live.map { asset[it]!! })
            live.forEachIndexed { k, i -> result[i] = outs.getOrNull(k) }
            return result
        }
        val asset = pick(curveQuote(ctx, d, pool.token, Side.SELL, amounts))
        val live = asset.indices.filter { asset[it] != null }
        val outs = UniswapVenue.quote(ctx, bridge, Side.SELL, live.map { asset[it]!! })
        live.forEachIndexed { k, i -> result[i] = outs.getOrNull(k) }
        return result
    }

    // Amounts here are in VIRTUAL (buy) or tokens (sell); the Nesty Router funds the buy.
    override fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams) =
        TxDraft(deployment(ctx).bonding, buy.encode(p.amountIn, pool.token, p.minOut, p.deadline), BigInteger.ZERO)

    override fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams) =
        TxDraft(deployment(ctx).bonding, sell.encode(p.amountIn, pool.token, p.minOut, p.deadline), BigInteger.ZERO)

    override fun sellApproval(ctx: VenueCtx, pool: PoolRef): SellApproval {
        val d = deployment(ctx)
        return SellApproval(d.router, false, d.bonding)
    }

    override fun funding(ctx: VenueCtx, pool: PoolRef): Funding {
        val d = deployment(ctx)
        return Funding(d.asset, "VIRTUAL", bridgeRef(ctx, d)) { c, side, amounts -> curveQuote(c, d, pool.token, side, amounts) }
    }

    override fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> {
        val d = deployment(ctx)
        return listOf(d.bonding to bondingRule, d.router to spenderRule)
    }
}

/** Every EVM venue, in the extension's routing order (it decides ties). */
object EvmVenues {
    val launchpads: List<Venue> by lazy { listOf(FourMeme, Flap, Pons, Virtuals, Montra) }
    val all: List<Venue> by lazy { listOf(UniswapVenue) + RouterVenues.venues + launchpads }

    fun byId(id: String): Venue? = all.firstOrNull { it.id == id }
}
