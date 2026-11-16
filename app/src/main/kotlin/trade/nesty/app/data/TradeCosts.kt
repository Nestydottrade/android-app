package trade.nesty.app.data

/**
 * What getting into a coin costs, from a quote. A venue's quote already holds what the venue
 * takes itself: on a launchpad's curve its fee and the coin's tax, in a hooked pool the hook's
 * fee. A coin's own transfer tax in a pool is taken on top of a pool's quote.
 *
 * The whole cost is measured against the coin's price now, which also counts what a route pays
 * on its way (a pool's fee, a swap into the token a curve trades in). It is kept between what the
 * route itself reports and that plus [MARKET_SLACK], so a price read a moment before a fast move
 * cannot swing it far.
 */
object TradeCosts {
    /** Below this a share is rounding. */
    private const val NOISE = 0.0005

    /** How far above the route's own figures the price's measure may go. */
    const val MARKET_SLACK = 0.10

    /**
     * The listed tax a quote leaves out: none on a launchpad's curve, where the venue takes the
     * coin's tax itself; in a pool, what the venue does not take itself.
     */
    fun taxOutside(listed: Double?, onCurve: Boolean, takeBps: Int?): Double {
        if (onCurve || listed == null || listed.isNaN()) return 0.0
        val left = listed.coerceIn(0.0, 0.99) - (takeBps ?: 0).coerceIn(0, 10_000) / 10_000.0
        return if (left < NOISE) 0.0 else left
    }

    /** A buy as the sheet shows it. Shares are of the amount paid. */
    data class Buy(
        /** Tokens it gets: the quote's, less a tax taken on top of it. */
        val tokens: Double,
        /** Lost to price impact. */
        val impact: Double,
        /** Lost to fees and taxes: the venue's, a pool's, a swap on the way, a transfer tax. */
        val fees: Double,
        /** Both together: lost to getting in, before the network fee. */
        val cost: Double,
    )

    /**
     * `quoted`: the tokens the quote gives, in whole tokens. `impact`: against a tiny trade on the
     * same route. `priceUsd`: the coin's price now, null when unknown.
     */
    fun buy(usd: Double, quoted: Double, impact: Double?, takeBps: Int?, outside: Double, priceUsd: Double?): Buy {
        val tokens = quoted * (1 - outside)
        val worth = if (priceUsd != null && priceUsd > 0 && priceUsd.isFinite() && usd > 0) tokens * priceUsd else null
        val s = split(usd, worth, impact, takeBps, outside)
        val fees = (1 - (1 - s.cost) / (1 - s.impact)).coerceAtLeast(0.0)
        return Buy(tokens, s.impact, if (fees < NOISE) 0.0 else fees, s.cost)
    }

    /**
     * A trade's cost in parts, each a share of what went in at the coin's price. [other] is what
     * the route took beyond the parts it reports: a pool's own fee, a swap on the way.
     */
    data class Split(val cost: Double, val impact: Double, val take: Double, val outside: Double) {
        val other: Double get() = (cost - impact - take - outside).let { if (it < NOISE) 0.0 else it }
    }

    /**
     * `value`: what went in at the coin's price (a buy's dollars, a sell's tokens at that price);
     * `back`: what came out at it (a buy's tokens at that price, a sell's dollars), null when the
     * coin is not priced: the route's own figures then.
     */
    fun split(value: Double, back: Double?, impact: Double?, takeBps: Int?, outside: Double): Split {
        val i = (impact ?: 0.0).coerceIn(0.0, 0.9999)
        val take = (takeBps ?: 0).coerceIn(0, 9_999) / 10_000.0
        val o = outside.coerceIn(0.0, 0.99)
        val floor = 1 - (1 - i) * (1 - take) * (1 - o)
        val market = if (back != null && back.isFinite() && back >= 0 && value > 0 && value.isFinite()) 1 - back / value else null
        val cost = market?.coerceIn(floor, minOf(1.0, floor + MARKET_SLACK)) ?: floor
        return Split(cost, i, (1 - i) * take, (1 - i) * (1 - take) * o)
    }
}
