package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.data.TradeCosts

class TradeCostsTest {
    private val eps = 1e-9

    @Test
    fun aCurveTakesItsFeeAndTaxInsideItsQuote() {
        // four.meme 1% fee + 10% tax, Flap 1%, Pons fee + creator tax: all in the venue's quote.
        assertEquals(0.0, TradeCosts.taxOutside(0.11, onCurve = true, takeBps = null), eps)
        assertEquals(0.0, TradeCosts.taxOutside(0.01, onCurve = true, takeBps = 100), eps)
    }

    @Test
    fun aPoolQuoteLeavesOutTheCoinsTransferTax() {
        // A graduated tax coin in a plain pool: the venue takes nothing itself.
        assertEquals(0.03, TradeCosts.taxOutside(0.03, onCurve = false, takeBps = 0), eps)
        assertEquals(0.03, TradeCosts.taxOutside(0.03, onCurve = false, takeBps = null), eps)
        // A hooked pool that takes the coin's tax inside the swap.
        assertEquals(0.0, TradeCosts.taxOutside(0.03, onCurve = false, takeBps = 300), eps)
        // No listed tax.
        assertEquals(0.0, TradeCosts.taxOutside(null, onCurve = false, takeBps = null), eps)
    }

    @Test
    fun flapCurveViaItsQuoteTokenCountsTheSwapOnTheWay() {
        // The measured BNB coin: $100 in, 22.72M out, impact 2.3%, Flap's 1% in the quote,
        // coin MC $4,235 on a billion supply. The quote's tokens are not cut again.
        val b = TradeCosts.buy(100.0, 22_720_000.0, 0.023, 100, 0.0, 4_235.0 / 1e9)
        assertEquals(22_720_000.0, b.tokens, 1e-3)
        assertEquals(1 - 22_720_000.0 * 4_235.0 / 1e9 / 100, b.cost, eps)
        // 2.3% impact, the rest (Flap's 1% and the swap into its quote token) as fees.
        assertEquals(0.023, b.impact, eps)
        assertEquals(1 - (1 - b.cost) / (1 - 0.023), b.fees, eps)
        assertEquals(0.0151, b.fees, 0.0002)
    }

    @Test
    fun aPoolTaxComesOffTheTokens() {
        val b = TradeCosts.buy(50.0, 1_000.0, 0.01, 0, 0.05, null)
        assertEquals(950.0, b.tokens, eps)
        // No price: what the route reports, impact and the tax.
        assertEquals(1 - 0.99 * 0.95, b.cost, eps)
        assertEquals(0.05, b.fees, eps)
    }

    @Test
    fun aLandedTradeSplitsItsCostIntoItsParts() {
        // The Flap buy: what the route reports, and the swap into FXIon as the rest.
        val s = TradeCosts.split(100.0, 22_720_000.0 * 4_235.0 / 1e9, 0.023, 100, 0.0)
        assertEquals(0.023, s.impact, eps)
        assertEquals(0.977 * 0.01, s.take, eps)
        assertEquals(0.0, s.outside, eps)
        assertEquals(s.cost - s.impact - s.take, s.other, eps)
        assertEquals(1 - 22_720_000.0 * 4_235.0 / 1e9 / 100, s.cost, eps)
    }

    @Test
    fun aSellIsMeasuredAgainstItsTokensAtThePrice() {
        // 3.1M tokens at $0.0000762 are worth $236.22; $230 came back: 2.63% lost.
        val worth = 3_100_000.0 * 0.0000762
        val s = TradeCosts.split(worth, 230.0, 0.013, 30, 0.0)
        assertEquals(1 - 230.0 / worth, s.cost, eps)
        // Not priced: the route's own figures, nothing more.
        val bare = TradeCosts.split(1.0, null, 0.013, 30, 0.0)
        assertEquals(1 - 0.987 * 0.997, bare.cost, eps)
        assertEquals(0.0, bare.other, eps)
    }

    @Test
    fun aTaxOutsideTheQuoteIsItsOwnPart() {
        val s = TradeCosts.split(100.0, null, 0.02, 0, 0.05)
        assertEquals(0.98 * 0.05, s.outside, eps)
        assertEquals(1 - 0.98 * 0.95, s.cost, eps)
        assertEquals(0.0, s.other, eps)
    }

    @Test
    fun aStalePriceCannotSwingTheCostFar() {
        // Floor: 1% impact and a 1% take.
        val floor = 1 - 0.99 * 0.99
        // The coin ran up since its price was read: the tokens look worth little.
        val low = TradeCosts.buy(100.0, 1_000.0, 0.01, 100, 0.0, 0.01)
        assertEquals(floor + TradeCosts.MARKET_SLACK, low.cost, eps)
        // It fell since: the tokens look worth more than was paid.
        val high = TradeCosts.buy(100.0, 1_000.0, 0.01, 100, 0.0, 0.2)
        assertEquals(floor, high.cost, eps)
    }
}
