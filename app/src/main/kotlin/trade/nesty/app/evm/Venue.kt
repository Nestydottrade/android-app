package trade.nesty.app.evm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger

/**
 * Venues: where a coin trades (a launchpad curve, a DEX pool), ported from the extension's
 * trade/types.ts. Quotes stay in native terms; a pool quoted in an ERC-20 says so through
 * `funding`.
 */
enum class Side { BUY, SELL }

/** What a venue keeps about one of its pools. */
sealed interface PoolData

/** A pool that needs nothing beyond its token (a Virtuals curve). */
data object NoPoolData : PoolData

/** A tradable pool for `token` against the chain's native coin. */
data class PoolRef(
    val venueId: String,
    /** Stable id: a pool address, a v4 pool id, or "venue:token". */
    val id: String,
    /** Short label, e.g. "Uniswap v3 · 1%". */
    val label: String,
    val token: String,
    val data: PoolData,
)

data class BuyParams(val wallet: String, val amountIn: BigInteger, val minOut: BigInteger, val deadline: BigInteger)

/** A Permit2 permit and its signature, for a Universal Router sell. */
class SignedPermit(val permit: PermitSingle, val signature: ByteArray)

data class SellParams(
    val wallet: String,
    val amountIn: BigInteger,
    val minOut: BigInteger,
    val deadline: BigInteger,
    /** Set when the Permit2 allowance of the router is too low for this sell. */
    val permit: SignedPermit? = null,
) {
    fun asBuy() = BuyParams(wallet, amountIn, minOut, deadline)
}

/** A sell of tokens already handed to the venue's router (by the Nesty Router). */
data class PrefundedParams(val wallet: String, val minOut: BigInteger, val deadline: BigInteger)

/** Who must hold an allowance for sells, and whether it is Permit2. */
data class SellApproval(val spender: String, val viaPermit2: Boolean, val router: String)

/** What the venue itself takes on each trade, in basis points; already in its quotes. */
data class VenueTax(val buyBps: Int, val sellBps: Int)

/**
 * A pool that trades against an ERC-20 asset instead of native (a Virtuals curve in VIRTUAL).
 * Quotes stay in native terms; the Nesty Router turns native into the asset around the curve
 * trade, in one transaction.
 */
class Funding(
    val asset: String,
    val symbol: String,
    /** The AMM pool that turns native into `asset` and back. */
    val leg: PoolRef,
    /** In the asset's terms: buy = asset in → token out; sell = token in → asset out. */
    val quoteAsset: suspend (VenueCtx, Side, List<BigInteger>) -> List<BigInteger?>,
)

class VenueCtx(
    val chain: EvmChain,
    val reader: ChainReader,
    val now: () -> Long = { System.currentTimeMillis() },
    /** v4 pool ids the server named for this coin. Each is checked on chain before use. */
    val poolHints: List<String> = emptyList(),
    val legs: LegCache = LegCache(),
)

interface Venue {
    val id: String
    fun supports(chainId: Long): Boolean
    suspend fun discover(ctx: VenueCtx, token: String): List<PoolRef>

    /** amounts[i] → output, or null where the quote failed. */
    suspend fun quote(ctx: VenueCtx, pool: PoolRef, side: Side, amounts: List<BigInteger>): List<BigInteger?>
    fun buildBuy(ctx: VenueCtx, pool: PoolRef, p: BuyParams): TxDraft
    fun buildSell(ctx: VenueCtx, pool: PoolRef, p: SellParams): TxDraft

    /** A sell for a contract caller that cannot sign Permit2 (the Nesty Router), or null. */
    fun buildSellPrefunded(ctx: VenueCtx, pool: PoolRef, p: PrefundedParams): TxDraft? = null
    fun sellApproval(ctx: VenueCtx, pool: PoolRef): SellApproval

    /** What the venue takes itself, when it says (null: nothing it reports). */
    suspend fun venueTax(ctx: VenueCtx, pool: PoolRef): VenueTax? = null
    fun funding(ctx: VenueCtx, pool: PoolRef): Funding? = null

    /** Validator rules for contracts specific to this pool (a per-token curve). */
    fun poolRules(ctx: VenueCtx, pool: PoolRef): List<Pair<String, ContractRule>> = emptyList()

    /** Why a coin this venue knows cannot trade right now (a full curve), or null. */
    suspend fun idle(ctx: VenueCtx, token: String): String? = null
}

/** The transaction for a trade. */
fun buildTx(venue: Venue, ctx: VenueCtx, pool: PoolRef, side: Side, p: SellParams): TxDraft =
    if (side == Side.BUY) venue.buildBuy(ctx, pool, p.asBuy()) else venue.buildSell(ctx, pool, p)

// ── helpers shared by the venues ───────────────────────────────────────────────

/** Stablecoins and hop tokens that may sit between native and `token`; none for one of them. */
fun hopStables(chain: EvmChain, token: String): List<String> {
    val stables = chain.routeAssets.map { it.first }
    if (sameAddress(token, chain.wrappedNative)) return emptyList()
    if (stables.any { sameAddress(it, token) }) return emptyList()
    return stables
}

fun stableSymbol(chain: EvmChain, a: String): String = chain.routeAssets.firstOrNull { sameAddress(it.first, a) }?.second ?: "stable"

/** Runs `second` on the positive outputs of `first`, keeping each amount's place. */
suspend fun chainQuotes(
    amounts: List<BigInteger>,
    first: suspend (List<BigInteger>) -> List<BigInteger?>,
    second: suspend (List<BigInteger>) -> List<BigInteger?>,
): List<BigInteger?> {
    val mid = first(amounts)
    val live = mid.indices.filter { i -> mid[i]?.let { it.signum() > 0 } == true }
    val result = MutableList<BigInteger?>(amounts.size) { null }
    if (live.isEmpty()) return result
    val outs = second(live.map { mid[it]!! })
    live.forEachIndexed { k, i -> result[i] = outs.getOrNull(k) }
    return result
}

/** A read that may fail: null instead of an exception (cancellation still cancels). */
suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Decodes one Multicall3 answer, or null when the call failed or answered nonsense. */
fun CallResult.decode(fn: AbiFunction): List<Any?>? {
    if (!success) return null
    return try {
        fn.decodeResult(data)
    } catch (e: AbiException) {
        null
    }
}

/** Calls that go out together with the other reads of this moment (sharedMulticall). */
suspend fun ChainReader.shared(calls: List<Call>): List<CallResult> = coroutineScope {
    calls.map { c -> async { readRaw(c) } }.awaitAll()
}

/** The first value of a quoter's answer, or null where it failed. */
fun firstOut(r: CallResult, fn: AbiFunction): BigInteger? = r.decode(fn)?.firstOrNull() as? BigInteger

fun BigInteger.lowerHex(): String = toString(16)

/** An address compared as a number (Uniswap sorts pool currencies this way). */
fun addressNumber(a: String): BigInteger = BigInteger(a.removePrefix("0x").ifEmpty { "0" }, 16)

fun sortPair(a: String, b: String): Pair<String, String> = if (addressNumber(a) < addressNumber(b)) a to b else b to a

object Erc20 {
    val symbol = AbiFunction("function symbol() view returns (string)")
    val decimals = AbiFunction("function decimals() view returns (uint8)")
    val balanceOf = AbiFunction("function balanceOf(address) view returns (uint256)")
    val allowance = AbiFunction("function allowance(address owner, address spender) view returns (uint256)")
    val approve = AbiFunction("function approve(address spender, uint256 amount) returns (bool)")
    val transfer = AbiFunction("function transfer(address to, uint256 amount) returns (bool)")
}
