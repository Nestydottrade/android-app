package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Candle
import trade.nesty.app.data.ChainStats
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Holder
import trade.nesty.app.data.Share
import trade.nesty.app.data.Tax
import trade.nesty.app.data.TeamShare
import trade.nesty.app.data.Trade
import trade.nesty.app.data.TraderRow
import trade.nesty.app.data.WalletAnswer
import trade.nesty.app.ui.Checks
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.holders.holdState
import trade.nesty.app.ui.menu.sumBars
import trade.nesty.app.ui.token.CandleMath
import trade.nesty.app.ui.token.Frame
import trade.nesty.app.ui.token.followedTrades
import trade.nesty.app.ui.wallet.unrealized

class LogicTest {
    private fun c(t: Long, o: Double, h: Double, l: Double, cl: Double, v: Double = 1.0) = Candle(t, o, h, l, cl, v)

    @Test
    fun fifteenSecondCandlesFromOneSecondOnes() {
        val ones = listOf(
            c(100, 1.0, 1.2, 0.9, 1.1), c(101, 1.1, 1.5, 1.0, 1.4), c(114, 1.4, 1.4, 0.8, 0.9),
            c(115, 0.9, 1.0, 0.85, 0.95), c(129, 0.95, 2.0, 0.95, 1.9),
        )
        // Buckets start at whole multiples of 15: 90, 105, 120.
        val out = CandleMath.aggregate(ones, 15)
        assertEquals(3, out.size)
        assertEquals(Candle(90, 1.0, 1.5, 0.9, 1.4, 2.0), out[0])
        assertEquals(Candle(105, 1.4, 1.4, 0.8, 0.95, 2.0), out[1])
        assertEquals(Candle(120, 0.95, 2.0, 0.95, 1.9, 1.0), out[2])
    }

    @Test
    fun theChartOpensInFiveMinutesWithFourLengthsOnItAndEveryLengthInItsList() {
        assertEquals(Frame.M5, Frame.DEFAULT)
        assertEquals(listOf("5m", "15m", "1h", "4h"), Frame.QUICK.map { it.label })
        assertEquals(
            listOf("1s", "15s", "1m", "5m", "15m", "30m", "1h", "4h", "6h", "12h", "1d", "3d", "1w"),
            Frame.ROWS.flatten().map { it.label },
        )
        assertEquals(Frame.entries.toList(), Frame.ROWS.flatten())
        for (f in Frame.entries) {
            // Each is a whole number of the server's candles it reads, and starts where one of those does.
            assertEquals(f.label, 0L, f.seconds % f.step)
            assertEquals(f.label, 0L, f.anchor % f.step)
            assertTrue(f.label, f.interval in setOf("1s", "1m", "5m", "15m", "1h", "4h", "D"))
            assertTrue(f.label, f.limit in 1..1_500)
        }
    }

    @Test
    fun longerLengthsAreSummedFromTheServersCandles() {
        // 15 minute candles from midnight: two to each half hour.
        val quarters = listOf(c(0, 1.0, 2.0, 1.0, 2.0), c(900, 2.0, 3.0, 2.0, 3.0), c(1_800, 3.0, 3.0, 1.0, 1.5), c(2_700, 1.5, 4.0, 1.5, 4.0))
        val halves = CandleMath.aggregate(quarters, Frame.M30.seconds, Frame.M30.anchor)
        assertEquals(listOf(Candle(0, 1.0, 3.0, 1.0, 3.0, 2.0), Candle(1_800, 3.0, 4.0, 1.0, 4.0, 2.0)), halves)
        // 4 hour candles: three to the first 12 hours of a day, one to the next.
        val fours = (0 until 4).map { c(it * 14_400L, 1.0, 1.0 + it, 1.0, 1.0) }
        assertEquals(listOf(0L, 43_200L), CandleMath.aggregate(fours, Frame.H12.seconds, Frame.H12.anchor).map { it.time })
    }

    @Test
    fun aWeekOpensOnAMonday() {
        val monday = 1_794_182_400L // 2026-11-09, a Monday
        // Daily candles from the Sunday before it to the Tuesday after the next.
        val days = (-1..8).map { c(monday + it * 86_400L, 1.0, 1.0, 1.0, 1.0) }
        val weeks = CandleMath.aggregate(days, Frame.W1.seconds, Frame.W1.anchor)
        assertEquals(listOf(monday - 7 * 86_400L, monday, monday + 7 * 86_400L), weeks.map { it.time })
        assertEquals(listOf(1.0, 7.0, 2.0), weeks.map { it.volume })
        // Three days count from 1970 on, as the server's days do.
        assertTrue(CandleMath.aggregate(days, Frame.D3.seconds).all { it.time % Frame.D3.seconds == 0L })
    }

    @Test
    fun aStreamCandleReplacesTheSameSecond() {
        var list = listOf(c(1, 1.0, 1.0, 1.0, 1.0), c(2, 1.0, 1.0, 1.0, 1.0))
        list = CandleMath.upsert(list, c(2, 1.0, 3.0, 1.0, 3.0), 10)
        assertEquals(2, list.size)
        assertEquals(3.0, list.last().close, 0.0)
        list = CandleMath.upsert(list, c(3, 3.0, 3.0, 3.0, 3.0), 2)
        assertEquals(listOf(2L, 3L), list.map { it.time })
        list = CandleMath.upsert(list, c(1, 9.0, 9.0, 9.0, 9.0), 5)
        assertEquals(listOf(1L, 2L, 3L), list.map { it.time })
    }

    @Test
    fun roundSteps() {
        assertEquals(5_000.0, CandleMath.niceStep(12_000.0, 3), 0.0)
        assertEquals(2.0, CandleMath.niceStep(5.0, 3), 0.0)
        assertEquals(0.01, CandleMath.niceStep(0.025, 3), 1e-12)
    }

    private val coin = Coin(feed = "56", address = "0x1", tax = Tax(0.0, 0.0))

    @Test
    fun checksWarnAtTheSiteThresholds() {
        val share = Share(top10 = 0.31, creator = 0.03, snipers = 0.04, bundlers = 0.09, team = TeamShare(0.12, 2))
        val checks = Checks.of(coin, share, emptyList(), solana = false)
        val byLabel = checks.associateBy { it.label }
        assertEquals(Tone.WARN, byLabel["Top 10 hold"]!!.tone)
        assertEquals(Tone.OK, byLabel["Bundlers hold"]!!.tone)
        assertEquals(Tone.WARN, byLabel["Team holds"]!!.tone)
        assertEquals("0%", byLabel["Tax"]!!.value)
        assertEquals("None holding", byLabel["Cluster"]!!.value)
        // Worst first.
        assertTrue(checks.takeWhile { it.tone == Tone.WARN }.size == 2)
    }

    @Test
    fun clusterWalletsAreARedFlagFromTenPercent() {
        val holders = listOf(
            Holder("0xpool", share = 0.5, role = "pool", tags = listOf("cluster")),
            Holder("0xa", share = 0.07, tags = listOf("cluster")),
            Holder("0xb", share = 0.04, tags = listOf("sniper", "cluster")),
        )
        assertEquals(0.11, Checks.clusterShare(holders), 1e-9)
        val c = Checks.of(coin, null, holders, solana = false).first()
        assertEquals(Tone.BAD, c.tone)
        assertEquals("Cluster", c.label)
    }

    @Test
    fun solanaSaysWhatIsNotCounted() {
        val c = Checks.of(coin.copy(feed = "solana", tax = null), null, null, solana = true)
        assertEquals(1, c.size)
        assertEquals(Tone.NONE, c.single().tone)
        assertEquals("Not counted on Solana", c.single().value)
    }

    @Test
    fun aShareOfOnlyNullsIsNotRead() {
        val c = Checks.of(coin.copy(tax = null), Share(feed = "56"), null, solana = false, followed = false)
        assertEquals("Not counted for this coin", c.single().value)
    }

    @Test
    fun listFlagsPutASignalFirstThenTheLargestWarning() {
        val share = Share(top10 = 0.41, bundlers = 0.15, snipers = 0.12)
        assertEquals("3 proven wallets bought", Checks.flag(3, share, null, null)!!.text)
        assertEquals("Top 10 hold 41%", Checks.flag(null, share, null, null)!!.text)
        assertEquals("Bundlers hold 15%", Checks.flag(null, share.copy(top10 = 0.2), null, null)!!.text)
        assertEquals(Tone.INFO, Checks.flag(null, Share(top10 = 0.16), null, null)!!.tone)
        assertEquals("Tax 10%", Checks.flag(null, Share(top10 = 0.16), Tax(0.1, 0.05), null)!!.text)
        assertEquals("On four.meme", Checks.flag(null, Share(top10 = 0.0), Tax(0.0, 0.0), "four.meme")!!.text)
        assertEquals("On four.meme", Checks.flag(null, null, Tax(0.0, 0.0), "four.meme")!!.text)
        assertNull(Checks.flag(null, null, null, null))
    }

    @Test
    fun earlyBuyerStates() {
        assertEquals("Holds all", holdState(TraderRow(boughtTokens = 100.0, remaining = 100.0)))
        assertEquals("Sold all", holdState(TraderRow(boughtTokens = 100.0, soldTokens = 100.0, remaining = 0.0)))
        assertEquals("Sold 44%", holdState(TraderRow(boughtTokens = 100.0, soldTokens = 44.0, remaining = 56.0)))
        assertEquals("Holds all", holdState(TraderRow(boughtTokens = 100.0, remaining = 130.0)))
    }

    @Test
    fun statsBarsLineUpAtTheNewest() {
        val a = ChainStats(bars = listOf(listOf(1.0, 0.0), listOf(2.0, 0.0), listOf(3.0, 0.0)))
        val b = ChainStats(bars = listOf(listOf(10.0, 0.0), listOf(20.0, 0.0)))
        assertEquals(listOf(1.0, 12.0, 23.0), sumBars(listOf(a, b)))
    }

    @Test
    fun unrealizedProfitOfAWalletPage() {
        val r = Fixtures.read<WalletAnswer>("wallet56").report!!
        val expected = r.holdings.sumOf { h ->
            val f = r.book[h.token]!!
            h.held * f.usd!! - h.cost * r.quotes[f.quote]!!
        }
        assertEquals(expected, unrealized(r), 1e-6)
    }

    @Test
    fun followedWalletsInACoinsTrades() {
        val trades = listOf(
            Trade(id = "1", at = 3, side = "buy", wallet = "0xAA"),
            Trade(id = "2", at = 2, side = "sell", wallet = "0xbb"),
            Trade(id = "3", at = 1, side = "buy", wallet = "0xaa"),
            Trade(id = "4", at = 0, side = "buy", wallet = null),
        )
        val names = mapOf("0xaa" to "Marlo")
        val out = followedTrades(trades) { w -> w?.lowercase()?.let { names[it] } }
        assertEquals(1, out.size)
        assertEquals("1", out.single().trade.id)
        assertEquals("Marlo", out.single().name)
    }
}
