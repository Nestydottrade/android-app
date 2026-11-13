package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Balances
import trade.nesty.app.data.Chain
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.Fill
import trade.nesty.app.data.Holding
import trade.nesty.app.data.Positions
import trade.nesty.app.data.WalletBalance
import trade.nesty.app.data.Coin
import trade.nesty.app.data.PoolRef
import trade.nesty.app.data.ChartRef
import trade.nesty.app.ui.trade.cleanAmount
import java.math.BigInteger

/** A position from its fills, and the buy sheet's amount box. The addresses are made up. */
class PositionsTest {
    private val wallet = "0x00000000000000000000000000000000000000aa"
    private val coin = "0x00000000000000000000000000000000000000c0"
    private fun tokens(n: Long) = BigInteger.valueOf(n).multiply(BigInteger.TEN.pow(18)).toString()

    private fun fill(side: String, tx: String, at: Long, raw: String, usd: Double?, fee: Double?, mcap: Double? = null, feed: String = "56", token: String = coin) =
        Fill(wallet, feed, token, "KILN", side, tx, at, raw, 18, usd, fee, mcap)

    @Test
    fun fillsAddUpToAPosition() {
        val fills = listOf(
            fill("buy", "0x1", 1_000, tokens(1_000_000), 25.0, 0.05, 15_000.0),
            fill("buy", "0x2", 2_000, tokens(3_000_000), 50.0, 0.05, 25_000.0),
            fill("sell", "0x3", 3_000, tokens(1_000_000), 40.0, 0.04),
        )
        val p = Positions.of(fills).single()
        assertEquals(Chain.BSC, p.chain)
        assertEquals(BigInteger(tokens(4_000_000)), p.bought)
        assertEquals(BigInteger(tokens(1_000_000)), p.sold)
        assertEquals(BigInteger(tokens(3_000_000)), p.booked)
        // Paid counts the buys' network fees, taken is the sells' dollars less theirs.
        assertEquals(75.10, p.paid, 1e-9)
        assertEquals(39.96, p.taken, 1e-9)
        // The entry market cap is weighted by the tokens of each buy: (1 × 15K + 3 × 25K) / 4.
        assertEquals(22_500.0, p.entryMcap!!, 1e-6)
        assertEquals(1_000, p.firstAt)
        assertEquals(3_000, p.lastAt)
    }

    @Test
    fun eachCoinAndChainIsItsOwnPosition() {
        val fills = listOf(
            fill("buy", "0x1", 1_000, tokens(1), 10.0, null),
            fill("buy", "0x2", 2_000, tokens(1), 10.0, null, token = "0x00000000000000000000000000000000000000c1"),
            fill("buy", "0x3", 3_000, tokens(1), 10.0, null, feed = "8453"),
            fill("buy", "0x4", 4_000, tokens(1), 10.0, null, feed = "nowhere"),
        )
        val list = Positions.of(fills)
        assertEquals(3, list.size)
        // Newest first; a chain the app does not know is left out.
        assertEquals(listOf(Chain.BASE, Chain.BSC, Chain.BSC), list.map { it.chain })
    }

    @Test
    fun aHoldingIsWorthItsBalanceAtThePriceNow() {
        val p = Positions.of(listOf(fill("buy", "0x1", 1_000, tokens(1_000_000), 25.0, 0.10, 15_000.0))).single()
        // Up: worth $50 against $25.10 paid. Initials sell what was paid back.
        val up = Holding(p, BigInteger(tokens(1_000_000)), 0.00005)
        assertEquals(50.0, up.value!!, 1e-9)
        assertEquals(24.90, up.pnl!!, 1e-9)
        assertTrue(up.open)
        assertEquals(25.10, up.initials!!, 1e-9)
        // Down: no initials to take out.
        val down = Holding(p, BigInteger(tokens(1_000_000)), 0.00002)
        assertEquals(-5.10, down.pnl!!, 1e-9)
        assertNull(down.initials)
        // Not priced: still open, nothing to say of its worth.
        val unpriced = Holding(p, BigInteger(tokens(1_000_000)), null)
        assertTrue(unpriced.open)
        assertNull(unpriced.pnl)
        // Dust under a cent is closed.
        assertFalse(Holding(p, BigInteger.valueOf(100), 0.00005).open)
        assertFalse(Holding(p, BigInteger.ZERO, 0.00005).open)
    }

    @Test
    fun initialsAreAlreadyOutOnceTheSellsPaidItBack() {
        val fills = listOf(
            fill("buy", "0x1", 1_000, tokens(2_000_000), 40.0, 0.0),
            fill("sell", "0x2", 2_000, tokens(1_000_000), 60.0, 0.0),
        )
        val p = Positions.of(fills).single()
        val h = Holding(p, BigInteger(tokens(1_000_000)), 0.00005)
        assertEquals(50.0 + 60.0 - 40.0, h.pnl!!, 1e-9)
        assertNull(h.initials)
    }

    @Test
    fun theBalanceOfTheChainWinsOverTheFills() {
        val fills = listOf(fill("buy", "0x1", 1_000, tokens(1_000_000), 25.0, 0.0))
        val read = WalletBalance(emptyMap(), emptySet(), tokens = mapOf(Balances.tokenKey(Chain.BSC, coin.uppercase().replace("0X", "0x")) to BigInteger(tokens(400_000))))
        val h = Positions.holdings(fills, read) { _, _ -> 0.00005 }.single()
        assertEquals(BigInteger(tokens(400_000)), h.held)
        // Without a reading, what the fills say is held.
        assertEquals(BigInteger(tokens(1_000_000)), Positions.holdings(fills, null) { _, _ -> null }.single().held)
    }

    @Test
    fun theServerNamesOnlyV4PoolIdsAsHints() {
        val id = "0x" + "ab".repeat(32)
        val info = CoinInfo.of(Coin(symbol = "KILN", decimals = 18, supply = 1e9, pool = PoolRef(address = id.uppercase().replace("0X", "0x")), chart = ChartRef(pool = "0x" + "12".repeat(20))))
        assertEquals(listOf(id), info.hints)
        assertEquals("KILN", info.label)
        assertEquals("this coin", CoinInfo.of(null).label)
    }

    @Test
    fun theAmountBoxTakesDollarsAndCents() {
        assertEquals("25", cleanAmount("25"))
        assertEquals("25.5", cleanAmount("25,5"))
        assertEquals("0.01", cleanAmount("0.01"))
        assertEquals("", cleanAmount(""))
        assertNull(cleanAmount("1.234"))
        assertNull(cleanAmount("1.2.3"))
        assertNull(cleanAmount("-5"))
        assertNull(cleanAmount("12e3"))
        assertNull(cleanAmount("12345678"))
    }
}
