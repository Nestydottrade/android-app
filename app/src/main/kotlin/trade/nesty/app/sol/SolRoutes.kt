package trade.nesty.app.sol

import trade.nesty.app.evm.Side
import trade.nesty.app.sol.dex.Dex
import trade.nesty.app.sol.dex.DexVenue
import java.math.BigInteger

/**
 * How a coin trades: through pump.fun (its curve, its PumpSwap pool, or a route of them), or on
 * one pool of another program. Ported from src/background/solana/routes.ts.
 */
sealed class SolRoute {
    data class OnPump(val pump: PumpRoute) : SolRoute()

    data class OnDex(val venue: DexVenue) : SolRoute()
}

/** A route and what it pays for an amount. */
data class Best<R>(val route: R, val out: BigInteger)

/** A trade through SOL of a coin priced in a token: both its legs, and what each pays. */
data class FundedQuote(
    val route: SolRoute,
    /** The pool between SOL and the pay token. */
    val leg: DexVenue,
    /** The pay token in between: what the leg buys (a buy), or what the coin sells for. */
    val mid: BigInteger,
    /** Coins for a buy, lamports for a sell. */
    val out: BigInteger,
)

/** One wallet's trade: `amount` of what `side` spends, at least `minOut` back. */
fun interface TradeBuilder {
    fun build(side: Side, user: String, amount: BigInteger, minOut: BigInteger): TradeIxs
}

object SolRoutes {
    /** What a route is bought with and sells into. */
    fun pay(r: SolRoute): QuoteAsset = when (r) {
        is SolRoute.OnPump -> r.pump.pay
        is SolRoute.OnDex -> r.venue.quote
    }

    /** The coin a route ends in, and its token program. */
    fun token(r: SolRoute): QuoteAsset = when (r) {
        is SolRoute.OnPump -> Pump.routeCoin(r.pump).let { QuoteAsset(it.mint, it.tokenProgram) }
        is SolRoute.OnDex -> QuoteAsset(r.venue.mint, r.venue.tokenProgram)
    }

    /** What `amountIn` of the pay currency buys of the coin. */
    fun buyQuote(r: SolRoute, amountIn: BigInteger): BigInteger = when (r) {
        is SolRoute.OnPump -> Pump.routeBuyQuote(r.pump, amountIn)
        is SolRoute.OnDex -> Dex.module(r.venue).quote(r.venue, Side.BUY, amountIn)
    }

    /** What selling `tokens` of the coin pays in the pay currency. */
    fun sellQuote(r: SolRoute, tokens: BigInteger): BigInteger = when (r) {
        is SolRoute.OnPump -> Pump.routeSellQuote(r.pump, tokens)
        is SolRoute.OnDex -> Dex.module(r.venue).quote(r.venue, Side.SELL, tokens)
    }

    /** Pay units per raw coin unit at the current price. */
    fun spotPrice(r: SolRoute): Double = when (r) {
        is SolRoute.OnPump -> Pump.routeSpotPrice(r.pump)
        is SolRoute.OnDex -> Dex.module(r.venue).spot(r.venue)
    }

    /** Why the route cannot trade right now, or null. */
    fun idleAt(r: SolRoute, nowSec: Double): String? = when (r) {
        is SolRoute.OnPump -> Pump.routeIdle(r.pump)
        is SolRoute.OnDex -> Dex.module(r.venue).idle(r.venue, nowSec)
    }

    /** The route that pays most for `amountIn` (a buy) or `tokens` (a sell), and what it pays. */
    fun bestRoute(routes: List<SolRoute>, side: Side, amountIn: BigInteger): Best<SolRoute>? {
        var best: Best<SolRoute>? = null
        for (route in routes) {
            val out = if (side == Side.BUY) buyQuote(route, amountIn) else sellQuote(route, amountIn)
            if (out.signum() > 0 && (best == null || out > best.out)) best = Best(route, out)
        }
        return best
    }

    /** The pool between SOL and a token that pays most, of `legs`. */
    fun bestLeg(legs: List<DexVenue>, side: Side, amountIn: BigInteger): Best<DexVenue>? =
        bestRoute(legs.map { SolRoute.OnDex(it) }, side, amountIn)?.let { Best((it.route as SolRoute.OnDex).venue, it.out) }

    /**
     * A buy of `amountIn` lamports: SOL into the pay token on the leg that pays most, then that on
     * the route that pays most. A sell of `amountIn` coins: the other way round.
     */
    fun fundedBest(routes: List<SolRoute>, legs: List<DexVenue>, side: Side, amountIn: BigInteger): FundedQuote? {
        if (side == Side.BUY) {
            val leg = bestLeg(legs, Side.BUY, amountIn) ?: return null
            val coin = bestRoute(routes, Side.BUY, leg.out) ?: return null
            return FundedQuote(coin.route, leg.route, leg.out, coin.out)
        }
        val coin = bestRoute(routes, Side.SELL, amountIn) ?: return null
        val leg = bestLeg(legs, Side.SELL, coin.out) ?: return null
        return FundedQuote(coin.route, leg.route, coin.out, leg.out)
    }

    /** Compute a pump.fun trade asks for when it is not simulated first (unused units are free). */
    private fun pumpUnits(variant: TradeVariant, side: Side): Int = when (variant) {
        TradeVariant.CURVE_V3 -> if (side == Side.BUY) 220_000 else 160_000
        TradeVariant.CURVE_V2 -> if (side == Side.BUY) 260_000 else 200_000
        TradeVariant.POOL_V2 -> if (side == Side.BUY) 300_000 else 240_000
        TradeVariant.POOL_V1 -> if (side == Side.BUY) 320_000 else 280_000
        TradeVariant.MULTI_HOP -> 0
    }

    /** A pump.fun route: about 200k per hop, plus the accounts around it. */
    private const val UNITS_PER_HOP = 200_000

    /** Compute a trade of `amount` (what `side` spends) asks for when it is not simulated first. */
    fun tradeUnits(r: SolRoute, side: Side, amount: BigInteger): Int = when (r) {
        is SolRoute.OnDex -> Dex.module(r.venue).let { m -> m.unitsFor(r.venue, side, amount) ?: m.units }
        is SolRoute.OnPump -> {
            val variant = Pump.routeVariant(r.pump)
            if (variant == TradeVariant.MULTI_HOP) UNITS_PER_HOP * r.pump.hops.size + 100_000 else pumpUnits(variant, side)
        }
    }

    /** Builds the trades of one click on `r`; null when pump.fun lists no fee accounts Nesty knows. */
    fun tradeBuilder(r: SolRoute, pick: ((Int) -> Int)? = null): TradeBuilder? = when (r) {
        is SolRoute.OnDex -> TradeBuilder { side, user, amount, minOut -> dexTrade(r.venue, side, user, amount, minOut) }
        is SolRoute.OnPump -> {
            val pump = r.pump
            val rec = (if (pick != null) Pump.pickRecipients(pump, pick) else Pump.pickRecipients(pump))
            if (rec == null) {
                null
            } else {
                TradeBuilder { side, user, amount, minOut ->
                    if (side == Side.BUY) Pump.buyInstructions(pump, user, amount, minOut, rec) else Pump.sellInstructions(pump, user, amount, minOut, rec)
                }
            }
        }
    }

    fun dexTrade(r: DexVenue, side: Side, user: String, amount: BigInteger, minOut: BigInteger): TradeIxs {
        val swap = Dex.module(r).swap(r, side, user, amount, minOut)
        val openQuote = Spl.createAtaIdempotent(user, r.quote.mint, r.quote.program)
        // Pools trade wrapped SOL: it is wrapped for a buy, and unwrapped after either side.
        val sol = r.quote.isSol
        val wsol = SolAddr.wsolAccount(user)
        val unwrap = if (sol) listOf(Spl.closeAccount(wsol, user)) else emptyList()
        if (side == Side.SELL) return TradeIxs(listOf(openQuote, swap) + unwrap, BigInteger.ZERO)
        val wrap = if (sol) listOf(openQuote, Spl.transfer(user, wsol, amount), Spl.syncNative(wsol)) else emptyList()
        return TradeIxs(wrap + Spl.createAtaIdempotent(user, r.mint, r.tokenProgram) + swap + unwrap, amount)
    }

    /** How much more of `mint` `owner` holds after the transaction than before. */
    fun tokenDelta(tx: TxMeta, owner: String, mint: String): BigInteger {
        fun held(list: List<TokenBalance>) = list.filter { it.mint == mint && it.owner == owner }.fold(BigInteger.ZERO) { s, b -> s + b.amount }
        return held(tx.postTokenBalances) - held(tx.preTokenBalances)
    }

    /** What a confirmed buy or sell delivered to `owner`: the coin, or what it was sold for. */
    fun tradeDelivered(tx: TxMeta, owner: String, side: Side, mint: String, pay: QuoteAsset): BigInteger {
        if (side == Side.BUY) return tokenDelta(tx, owner, mint)
        if (!pay.isSol) return tokenDelta(tx, owner, pay.mint)
        val at = tx.accountKeys.indexOf(owner)
        if (at < 0) return BigInteger.ZERO
        // What left the wrapped SOL account counts once: closing it also returns what it held
        // before, and its rent.
        val wsolAt = tx.accountKeys.indexOf(SolAddr.wsolAccount(owner))
        val unwrapped = if (wsolAt >= 0) (tx.preBalances.getOrNull(wsolAt) ?: BigInteger.ZERO) - (tx.postBalances.getOrNull(wsolAt) ?: BigInteger.ZERO) else BigInteger.ZERO
        val delta = (tx.postBalances.getOrNull(at) ?: BigInteger.ZERO) - (tx.preBalances.getOrNull(at) ?: BigInteger.ZERO)
        return delta + tx.fee - unwrapped
    }

    /** Why a buy is quoted nothing, when more can be said than that no route fills it. */
    fun noFill(r: SolRoute): String? {
        if (r !is SolRoute.OnPump) return "This pool cannot fill that amount. Try a smaller one."
        val coin = Pump.routeCoin(r.pump)
        // Past a curve's end, where no migration fills the rest.
        return if (coin is CurveVenue && (Pump.routeVariant(r.pump) == TradeVariant.CURVE_V2 || coin.curve.isMayhemMode)) {
            "This buy is more than the curve has left. Try a smaller amount."
        } else {
            null
        }
    }
}
