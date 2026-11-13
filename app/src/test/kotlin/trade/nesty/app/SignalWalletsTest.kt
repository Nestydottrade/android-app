package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Quote
import trade.nesty.app.data.SignalRow
import trade.nesty.app.data.TraderRow
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.signal.Figure
import trade.nesty.app.ui.signal.SignalWallets

/**
 * The signal wallets sheet. `tradersSignal` is the saved real answer with the `signal` field
 * added in the server's shape (three wallets: one still holding, one that sold all with no
 * unrealized figure, one made-up address with no row); `tradersB` is the answer of a server
 * without the field.
 */
class SignalWalletsTest {
    private val supply = 1_000_162_393.0
    private val holder = "0xfe73221dde14e6ae9c902d8c9435609012002280"
    private val seller = "0x9261c6780b8f9fb5a36eaf601389dd9537acad02"
    private val madeUp = "0x00000000000000000000000000000000000c0ffe"

    @Test
    fun theSignalFieldIsRead() {
        val s = Fixtures.read<TradersAnswer>("tradersSignal").signal!!
        assertEquals(3, s.size)
        assertEquals(listOf(holder, seller, madeUp), s.wallets)
        assertEquals(2, s.rows.size)
        assertNull(s.rows[1].unrealized)
        assertEquals(0.0, s.rows[1].remaining!!, 0.0)
        assertNull(Fixtures.read<TradersAnswer>("tradersB").signal)
    }

    @Test
    fun theServersSignalNamesTheWalletsInItsOrder() {
        val answer = Fixtures.read<TradersAnswer>("tradersSignal")
        val head = SignalWallets.coinHead(answer, listed = SignalRow(size = 2, at = 5, wallets = listOf("0xother")))!!
        assertEquals(3, head.size)
        assertEquals(answer.signal!!.at, head.at)
        val view = SignalWallets.view(head, answer)
        assertEquals(listOf(holder, seller, madeUp), view.wallets.map { it.wallet })
        assertEquals(holder, view.wallets[0].row!!.wallet)
        // The signal's own row wins over the tables': this one has no unrealized figure.
        assertNull(view.wallets[1].row!!.unrealized)
        assertNull(view.wallets[2].row)
        assertTrue(view.partial)
        assertEquals("NVDAB", view.quote?.symbol)
    }

    @Test
    fun anOlderServerFallsBackToTheListAndTheTraderTables() {
        val answer = Fixtures.read<TradersAnswer>("tradersB")
        val top = answer.top[1].wallet
        val early = answer.early[1].wallet
        val listed = SignalRow(token = answer.address, size = 3, at = 1_794_462_300_000, first = 1_794_462_000_000, wallets = listOf(top.uppercase().replace("0X", "0x"), early, madeUp, early))
        val head = SignalWallets.coinHead(answer, listed)!!
        assertEquals(3, head.size)
        assertEquals(1_794_462_300_000, head.at)
        val view = SignalWallets.view(head, answer)
        // A wallet named twice is listed once; the address keeps the list's spelling.
        assertEquals(3, view.wallets.size)
        assertEquals(top, view.wallets[0].row!!.wallet)
        assertEquals(early, view.wallets[1].row!!.wallet)
        assertNull(view.wallets[2].row)
        assertNull(SignalWallets.coinHead(answer, null))
        assertNull(SignalWallets.coinHead(null, null))
    }

    @Test
    fun aWalletThatStillHolds() {
        val answer = Fixtures.read<TradersAnswer>("tradersSignal")
        val f = SignalWallets.figures(answer.signal!!.rows[0], answer.quote, supply)
        assertEquals(Figure("$1,633", "55.1M · 1 tx · $29.6K MC"), f.bought)
        assertEquals(Figure(Fmt.DASH), f.sold)
        assertEquals("$0", f.realized)
        assertEquals(0, f.realizedSign)
        assertEquals("+$11.3K", f.unrealized)
        assertEquals(1, f.unrealizedSign)
        // It holds more than it bought here: tokens sent to it count.
        assertEquals(Figure("95.8M", "174% of bought"), f.holds)
        assertEquals(
            "Bought $1,633 in 1 trade at $29.6K market cap. Sold nothing. Nothing realized. " +
                "Unrealized profit $11,262. Holds 95.8M tokens, 174% of bought.",
            f.spoken,
        )
    }

    @Test
    fun aWalletThatSoldAll() {
        val answer = Fixtures.read<TradersAnswer>("tradersSignal")
        val f = SignalWallets.figures(answer.signal!!.rows[1], answer.quote, supply)
        assertEquals(Figure("$247.10", "85.5M · 1 tx · $2.9K MC"), f.bought)
        assertEquals(Figure("$239.74", "85.5M · 1 tx · $2.8K MC"), f.sold)
        assertEquals("${Fmt.MINUS}$7.36", f.realized)
        assertEquals(-1, f.realizedSign)
        assertEquals(Fmt.DASH, f.unrealized)
        assertEquals(0, f.unrealizedSign)
        assertEquals(Figure("0", "0% of bought"), f.holds)
        assertEquals(
            "Bought $247.10 in 1 trade at $2.9K market cap. Sold $239.74 in 1 trade at $2.8K market cap. " +
                "Realized loss $7.36. Holds none.",
            f.spoken,
        )
    }

    @Test
    fun aProfitTooSmallToWriteIsNothing() {
        // The saved answer's sniper: it lost a quarter of a cent.
        val answer = Fixtures.read<TradersAnswer>("tradersB")
        val f = SignalWallets.figures(answer.early[1], answer.quote, supply)
        assertEquals("$0.07", f.bought.value)
        assertEquals("$0", f.realized)
        assertEquals(0, f.realizedSign)
        // The table's 0 for a wallet that holds nothing is not an unrealized figure.
        assertEquals(Fmt.DASH, f.unrealized)
    }

    @Test
    fun withoutAPriceTheAmountsStayInTheQuote() {
        val row = TraderRow(
            wallet = madeUp, buys = 2, sells = 1, bought = 0.42, sold = 0.0034, boughtTokens = 1_000_000.0, soldTokens = 250_000.0,
            remaining = 750_000.0, realized = -0.012, unrealized = 0.2,
        )
        val f = SignalWallets.figures(row, Quote(symbol = "BNB", usd = null), supply)
        // No price, so no market cap either.
        assertEquals(Figure("0.42 BNB", "1.00M · 2 tx"), f.bought)
        assertEquals(Figure("0.0034 BNB", "250K · 1 tx"), f.sold)
        assertEquals("${Fmt.MINUS}0.012 BNB", f.realized)
        assertEquals("+0.2 BNB", f.unrealized)
        assertEquals(Figure("750K", "75% of bought"), f.holds)
        assertTrue(f.spoken.contains("Realized loss 0.012 BNB"))

        // No supply: the dollars stay, the market cap goes.
        val g = SignalWallets.figures(row, Quote(symbol = "BNB", usd = 600.0), null)
        assertEquals(Figure("$252.00", "1.00M · 2 tx"), g.bought)
        assertEquals("${Fmt.MINUS}$7.20", g.realized)
    }

    @Test
    fun tokensItHoldsButDidNotBuyHere() {
        val sent = SignalWallets.figures(TraderRow(wallet = madeUp, remaining = 1_200.4), Quote("BNB", null, 600.0), supply)
        assertEquals(Figure(Fmt.DASH), sent.bought)
        assertEquals(Figure("1,200", "not bought here"), sent.holds)
        assertTrue(sent.spoken.startsWith("Did not buy here. Sold nothing."))
        val none = SignalWallets.figures(TraderRow(wallet = madeUp, remaining = 0.0), Quote("BNB", null, 600.0), supply)
        assertEquals(Figure("0"), none.holds)
        val unknown = SignalWallets.figures(TraderRow(wallet = madeUp, remaining = null, unrealized = 3.0), Quote("BNB", null, 600.0), supply)
        assertEquals(Figure(Fmt.DASH), unknown.holds)
        assertEquals(Fmt.DASH, unknown.unrealized)
    }

    @Test
    fun theTitleSaysAnAgeNeverAClockTime() {
        val now = 1_794_500_000_000
        assertEquals("3 proven wallets bought KILN 3h ago", SignalWallets.title(3, "KILN", now - 3 * 3_600_000L - 5_000, now))
        assertEquals("5 proven wallets bought DONOTBUY 41s ago", SignalWallets.title(5, "DONOTBUY", now - 41_000, now))
    }

    @Test
    fun amountsOfACoin() {
        assertEquals("0", Fmt.amount(0.0))
        assertEquals("0.0034", Fmt.amount(0.0034))
        assertEquals("0.42", Fmt.amount(0.42))
        assertEquals("0.00001234", Fmt.amount(0.000012344))
        assertEquals("4.20", Fmt.amount(4.2))
        assertEquals("1.2K", Fmt.amount(1_200.0))
        assertEquals("${Fmt.MINUS}0.5", Fmt.amount(-0.5))
        assertNotNull(Fmt.amount(Double.NaN))
    }
}
