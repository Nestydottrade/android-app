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
