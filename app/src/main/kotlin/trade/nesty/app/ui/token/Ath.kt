package trade.nesty.app.ui.token

import trade.nesty.app.data.Candle
import kotlin.math.max

/**
 * A coin's all-time high as the token page shows it, as the site does: the most it ever traded
 * at, in dollars per token. Known only where the server says its whole life is known (the chart
 * answer's `ath`): a high worked out from the candles alone could be one of a partial history.
 */
object Ath {
    /** Shown once it is a market cap of this much, or the coin's first candle this old. */
    const val MCAP_USD = 7_000.0
    const val AGE_S = 15 * 60L

    private fun good(v: Double?): Double? = v?.takeIf { it.isFinite() && it > 0 }

    /**
     * The high known on the page: [had], the one known so far, and the server's [served] raised by
     * the highest of [candles]. Without either, null whatever the candles hold.
     */
    fun raise(had: Double?, served: Double?, candles: List<Candle>): Double? {
        val high = candles.mapNotNull { good(it.high) }.maxOrNull()
        val base = listOfNotNull(good(had), good(served)).maxOrNull() ?: return null
        return if (high != null) max(base, high) else base
    }

    /**
     * The high to show: once it is a market cap worth marking ([supply] known), or the first candle
     * read ([firstAt], Unix seconds) is old enough for one to mean much; either is enough.
     */
    fun shown(ath: Double?, supply: Double?, firstAt: Long?, nowS: Long): Double? {
        val a = good(ath) ?: return null
        val big = supply != null && supply > 0 && a * supply >= MCAP_USD
        val old = firstAt != null && nowS - firstAt >= AGE_S
        return if (big || old) a else null
    }
}
