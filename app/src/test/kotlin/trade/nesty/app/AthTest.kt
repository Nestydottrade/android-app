package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import trade.nesty.app.data.Candle
import trade.nesty.app.ui.token.Ath

/** The all-time high the token page shows: the site's rules. */
class AthTest {
    private fun c(t: Long, high: Double) = Candle(t, high / 2, high, high / 4, high / 2, 1.0)

    @Test
    fun neverFromTheCandlesAlone() {
        // A partial history would give a false high.
        assertNull(Ath.raise(null, null, listOf(c(1, 5.0))))
    }

    @Test
    fun theServersHighRaisedByTheCandlesDrawn() {
        assertEquals(5.0, Ath.raise(null, 3.0, listOf(c(1, 5.0), c(2, 1.0)))!!, 0.0)
        // Above every candle sent: the server keeps the high past the limit.
        assertEquals(9.0, Ath.raise(null, 9.0, listOf(c(1, 5.0)))!!, 0.0)
        assertEquals(9.0, Ath.raise(null, 9.0, emptyList())!!, 0.0)
    }

    @Test
    fun keptAcrossLengthsAndRaisedByNewCandles() {
        // Another length's answer without the high keeps the one known.
        assertEquals(9.0, Ath.raise(9.0, null, listOf(c(1, 2.0)))!!, 0.0)
        // A live candle above it raises it.
        assertEquals(12.0, Ath.raise(9.0, null, listOf(c(2, 12.0)))!!, 0.0)
        // A figure that is no price counts for nothing.
        assertNull(Ath.raise(null, 0.0, listOf(c(1, 2.0))))
        assertNull(Ath.raise(null, Double.NaN, emptyList()))
        assertEquals(9.0, Ath.raise(9.0, null, listOf(c(3, Double.POSITIVE_INFINITY)))!!, 0.0)
    }

    @Test
    fun shownAtSevenThousandOrFifteenMinutes() {
        val now = 100_000L
        // A $7K market cap shows it however young the coin is.
        assertEquals(7_000.0, Ath.shown(7_000.0, 1.0, now - 60, now)!!, 0.0)
        // Below it and young: not yet.
        assertNull(Ath.shown(6e-6, 1e9, now - 60, now))
        // Below it, with its first candle 15 minutes old: shown.
        assertEquals(6e-6, Ath.shown(6e-6, 1e9, now - 900, now)!!, 0.0)
        assertNull(Ath.shown(6e-6, 1e9, now - 899, now))
        // Without a supply only its age shows it.
        assertNull(Ath.shown(1.0, null, now - 60, now))
        assertEquals(1.0, Ath.shown(1.0, null, now - 900, now)!!, 0.0)
        assertNull(Ath.shown(6e-6, 1e9, null, now))
        // None known: nothing.
        assertNull(Ath.shown(null, 1e9, now - 9_000, now))
    }
}
