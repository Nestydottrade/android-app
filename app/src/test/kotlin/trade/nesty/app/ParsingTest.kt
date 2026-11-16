package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.CandlesAnswer
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Feed
import trade.nesty.app.data.HoldersAnswer
import trade.nesty.app.data.LeaderboardAnswer
import trade.nesty.app.data.NestyJson
import trade.nesty.app.data.RecordAnswer
import trade.nesty.app.data.SharesAnswer
import trade.nesty.app.data.SignalsAnswer
import trade.nesty.app.data.StatsAnswer
import trade.nesty.app.data.Tokens
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.data.TradesAnswer
import trade.nesty.app.data.UpdatesAnswer
import trade.nesty.app.data.WalletAnswer

/** Every answer the app reads, decoded from a real one. */
class ParsingTest {
    @Test
    fun feeds() {
        val bnb = Fixtures.read<Feed>("feed56")
        assertEquals("56", bnb.feed)
        assertTrue(bnb.fresh.isNotEmpty() && bnb.near.isNotEmpty() && bnb.graduated.isNotEmpty())
        val row = bnb.fresh.first()
        assertEquals("0x8a5c9FdCfD6947D50Bbd11C425cFb67109CfFffF", row.address)
        assertEquals("four.meme", row.venue)
        assertEquals(0.03, row.tax?.buy)
        assertNotNull(row.createdAt)
        assertTrue(Fixtures.read<Feed>("feedsol").fresh.isNotEmpty())
        assertTrue(Fixtures.read<Feed>("feedely").fresh.isNotEmpty())
    }

    @Test
    fun coins() {
        val curve = Fixtures.read<Coin>("token56")
        assertTrue(curve.onCurve)
        val pool = Fixtures.read<Coin>("tokenB")
        assertFalse(pool.onCurve)
        assertEquals("pancakeswap", pool.pool?.dex)
        assertEquals(168.224299, pool.quote?.usd)
        assertEquals(-3.27, pool.change?.m5)
        assertNotNull(Fixtures.read<Coin>("tokensol").address)
    }

    @Test
    fun candlesAreSixNumbers() {
        val a = Fixtures.read<CandlesAnswer>("candlesB")
        val c = a.parsed()
        assertEquals(a.candles.size, c.size)
        assertEquals(1_000_162_393.0, a.supply)
        assertTrue(c.zipWithNext().all { (x, y) -> x.time < y.time })
        assertTrue(c.all { it.high >= it.low })
    }

    @Test
    fun candlesCarryTheAllTimeHigh() {
        // The 1s answer holds no candle and still carries the high.
        val a = NestyJson.decodeFromString<CandlesAnswer>("""{"interval":"1s","supply":1e9,"candles":[],"partial":false,"ath":0.0000123}""")
        assertEquals(0.0000123, a.ath!!, 0.0)
        assertTrue(a.parsed().isEmpty())
        assertNull(NestyJson.decodeFromString<CandlesAnswer>("""{"interval":"1m","candles":[],"partial":false,"ath":null}""").ath)
        // An answer saved before the field: none.
        assertNull(Fixtures.read<CandlesAnswer>("candlesB").ath)
    }

    @Test
    fun candlesSkipARowThatIsNotWhole() {
        val a = NestyJson.decodeFromString<CandlesAnswer>("""{"interval":"1m","supply":1e9,"candles":[[1,2,3,1,2,10],[2,2,null,1,2,0],[3,1]]}""")
        assertEquals(1, a.parsed().size)
    }

    @Test
    fun tradesHoldersTraders() {
        val t = Fixtures.read<TradesAnswer>("tradesB").trades
        assertTrue(t.isNotEmpty())
        assertTrue(t.first().isBuy)
        assertNull(t.first().wallet)
        assertEquals(10559.0696 * 0.000234754345, t.first().usd!!, 1e-9)

        val h = Fixtures.read<HoldersAnswer>("holdersB")
        assertEquals("complete", h.state)
        assertEquals("pool", h.list.first().role)

        val tr = Fixtures.read<TradersAnswer>("tradersB")
        assertEquals(929, tr.trades)
        assertTrue(tr.partial)
        assertEquals("NVDAB", tr.quote?.symbol)
    }

    @Test
    fun sharesAreNullOnSolana() {
        val evm = Fixtures.read<SharesAnswer>("sharesB").shares.single()
        assertEquals(0.199393, evm.top10)
        assertNull(evm.team)
        val sol = Fixtures.read<SharesAnswer>("sharessol").shares.single()
        assertNull(sol.top10)
        assertNull(sol.holders)
    }

    @Test
    fun signalsAndRecord() {
        val s = Fixtures.read<SignalsAnswer>("signals56").signals
        assertEquals(200, s.proven)
        assertTrue(s.list.all { it.size >= 2 })
        val r = Fixtures.read<RecordAnswer>("record").record
        assertEquals(7, r.days)
        assertEquals(722, r.all.sizes["3"]?.n)
        assertTrue(r.chains.any { it.chain == 56L })
    }

    @Test
    fun leaderboardStatsUpdatesWallet() {
        val b = Fixtures.read<LeaderboardAnswer>("lb56").board
        assertEquals(listOf("profit", "roi", "winRate", "volume"), b.order.keys.toList())
        assertEquals("sniper", b.rows.first().tag)

        val st = Fixtures.read<StatsAnswer>("stats").stats
        assertEquals(3_600_000L, st.step)
        assertTrue(st.chains.all { c -> c.bars.all { it.size == 2 } })

        val u = Fixtures.read<UpdatesAnswer>("updates")
        assertEquals(102, u.total)
        assertEquals("2026-11-13", u.updates.first().day)

        val w = Fixtures.read<WalletAnswer>("wallet56").report!!
        assertEquals("sniper", w.totals.tag)
        assertTrue(w.trades.isNotEmpty())
        assertEquals(0.999, w.quotes.values.first(), 0.0)
    }

    @Test
    fun listsOfTokens() {
        assertEquals(20, Fixtures.read<Tokens>("trending56").tokens.size)
        assertTrue(Fixtures.read<Tokens>("search").tokens.isNotEmpty())
    }

    @Test
    fun aFieldTheServerAddsBreaksNothing() {
        val c = NestyJson.decodeFromString<Coin>("""{"feed":"56","address":"0x1","newField":{"a":1},"venue":"launchpad","priceUsd":null}""")
        assertTrue(c.onCurve)
        assertNull(c.priceUsd)
    }
}
