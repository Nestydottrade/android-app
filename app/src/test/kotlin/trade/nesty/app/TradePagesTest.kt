package trade.nesty.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.ui.token.MAX_TRADES
import trade.nesty.app.ui.token.Older
import trade.nesty.app.ui.token.Phase
import trade.nesty.app.ui.token.TokenModel

/** The Trades list going back a page at a time, and one wallet's trades read whole. */
class TradePagesTest {
    private val asked = mutableListOf<String>()
    private val coin = "0x7777777777777777777777777777777777777777"
    private val base = "https://nesty.trade/api/v1/trades/56/$coin"

    private fun trade(id: String, at: Long, wallet: String? = "0x1111111111111111111111111111111111111111") =
        """{"id":"$id","tx":"0x$id","at":$at,"side":"buy","tokens":1,"quote":1,"price":1,""" +
            """"priceUsd":1,"mcapUsd":1,"wallet":${wallet?.let { "\"$it\"" } ?: "null"},"tags":[]}"""

    /** `count` trades named `name`1… from `from` down, one a millisecond. */
    private fun page(name: String, from: Long, count: Int, older: String?, reading: Boolean = false, all: Boolean? = null): String {
        val trades = (0 until count).joinToString(",") { trade("$name${it + 1}", from - it) }
        val tail = all?.let { ""","all":$it""" } ?: ""
        return """{"trades":[$trades],"reading":$reading,"older":${older?.let { "\"$it\"" } ?: "null"}$tail}"""
    }

    /** As [page], from a server that says whether the page came from Nesty's book. */
    private fun booked(name: String, from: Long, count: Int, older: String?, booked: Boolean) =
        page(name, from, count, older).dropLast(1) + ""","booked":$booked}"""

    private fun model(answer: (String) -> Pair<Int, String>) = TokenModel(fakeApi(asked, answer), Chain.BSC, coin)

    /** The `before` asked for in each call after the newest page's. */
    private fun cursors() = asked.mapNotNull { Regex("before=([^&]+)").find(it)?.groupValues?.get(1) }

    @Test
    fun pagesBackToItsFirstTrade() = runBlocking {
        val m = model { url ->
            200 to when {
                url.endsWith("before=c1") -> page("b", 900, 100, "c2")
                url.endsWith("before=c2") -> page("c", 800, 50, null)
                else -> page("a", 1_000, 100, "c1")
            }
        }
        m.loadTrades()
        assertEquals("$base?limit=100", asked.single())
        assertEquals(Older.IDLE, m.older)
        m.loadOlder()
        assertEquals("$base?limit=100&before=c1", asked.last())
        assertEquals(200, m.trades.size)
        assertEquals(1, m.olderPages)
        assertEquals(Older.IDLE, m.older)
        m.loadOlder()
        assertEquals(250, m.trades.size)
        assertEquals(Older.DONE, m.older)
        // Its first trade is in: nothing more is asked.
        m.loadOlder()
        assertEquals(listOf("c1", "c2"), cursors())
        assertEquals((1_000L downTo 751L).toList(), m.trades.map { it.at })
    }

    @Test
    fun theNewestPageTellsWhereToStartWhenTheStreamCameFirst() = runBlocking {
        var reading = true
        val m = model { url ->
            200 to when {
                url.contains("before=") -> page("b", 50, 10, null)
                reading -> page("a", 100, 0, null, reading = true)
                else -> page("a", 100, 20, "c1")
            }
        }
        m.loadTrades()
        assertEquals(Older.IDLE, m.older)
        // Asked while the server's first read still runs: no way back yet, and none ruled out.
        m.loadOlder()
        assertEquals(Older.IDLE, m.older)
        reading = false
        m.loadOlder()
        assertEquals(listOf("c1"), cursors())
        assertEquals(30, m.trades.size)
        assertEquals(Older.DONE, m.older)
    }

    @Test
    fun aCoinWithNoWayBackIsDone() = runBlocking {
        val m = model { 200 to page("a", 100, 5, null) }
        m.loadTrades()
        assertEquals(Older.DONE, m.older)
        m.loadOlder()
        assertEquals(1, asked.size)
    }

    @Test
    fun emptyPagesWaitForThePerson() = runBlocking {
        val m = model { url ->
            200 to when {
                url.endsWith("before=c1") -> page("x", 0, 0, "c2")
                url.endsWith("before=c2") -> page("x", 0, 0, "c3")
                url.endsWith("before=c3") -> page("x", 0, 0, "c4")
                url.endsWith("before=c4") -> page("b", 50, 1, null)
                else -> page("a", 100, 3, "c1")
            }
        }
        m.loadTrades()
        m.loadOlder()
        assertEquals(listOf("c1", "c2", "c3"), cursors())
        assertEquals(Older.STALLED, m.older)
        assertEquals(0, m.olderPages)
        m.loadOlder()
        assertEquals(4, m.trades.size)
        assertEquals(Older.DONE, m.older)
    }

    @Test
    fun aFailedPageIsAskedForAgain() = runBlocking {
        var down = true
        val m = model { url ->
            when {
                url.contains("before=") && down -> 502 to """{"error":"unreadable"}"""
                url.contains("before=") -> 200 to page("b", 50, 5, null)
                else -> 200 to page("a", 100, 3, "c1")
            }
        }
        m.loadTrades()
        m.loadOlder()
        assertEquals(Older.FAILED, m.older)
        assertEquals(3, m.trades.size)
        down = false
        m.loadOlder()
        assertEquals(listOf("c1", "c1"), cursors())
        assertEquals(8, m.trades.size)
        assertEquals(Older.DONE, m.older)
    }

    @Test
    fun aGapStartsTheListAgain() = runBlocking {
        var newest = page("a", 10_000, 100, "c1")
        val m = model { url ->
            200 to when {
                url.endsWith("before=c1") -> page("b", 9_000, 100, "c2")
                url.endsWith("before=n1") -> page("o", 19_000, 10, null)
                url.contains("before=") -> page("z", 0, 0, null)
                else -> newest
            }
        }
        m.loadTrades()
        m.loadOlder()
        assertEquals(200, m.trades.size)
        // The same newest trades again: the list and its way back stay.
        m.loadTrades()
        assertEquals(200, m.trades.size)
        // A full page none of whose trades the list holds: what lay between went unread.
        newest = page("n", 20_000, 100, "n1")
        m.loadTrades()
        assertEquals(100, m.trades.size)
        assertTrue(m.trades.all { it.id.startsWith("n") })
        assertEquals(Older.IDLE, m.older)
        m.loadOlder()
        assertEquals(listOf("c1", "n1"), cursors())
        assertEquals(110, m.trades.size)
    }

    @Test
    fun aFullListStopsGoingBack() = runBlocking {
        val m = model { url ->
            val n = Regex("before=c(\\d+)").find(url)?.groupValues?.get(1)?.toInt()
            200 to if (n == null) page("a", 1_000_000, 100, "c1") else page("p${n}x", 1_000_000L - n * 1_000, 1_000, "c${n + 1}")
        }
        m.loadTrades()
        repeat(10) { m.loadOlder() }
        assertEquals(MAX_TRADES, m.trades.size)
        assertEquals(Older.DONE, m.older)
        assertEquals(5, cursors().size)
    }

    @Test
    fun aWalletsTradesAreReadWholeWithThoseHeardSince() = runBlocking {
        val wallet = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        val m = model { url ->
            200 to when {
                url.contains("wallet=") ->
                    """{"trades":[${trade("w2", 50, wallet)},${trade("w1", 40, wallet)}],"reading":false,"older":null,"all":true}"""
                else ->
                    """{"trades":[${trade("h1", 90, wallet.uppercase().replace("0X", "0x"))},${trade("w2", 50, wallet)},${trade("o1", 45)}],"reading":false,"older":null}"""
            }
        }
        m.loadTrades()
        m.readWallet(wallet)
        assertEquals("$base?wallet=$wallet", asked.last())
        val read = m.walletRead!!
        assertEquals(Phase.READY, read.phase)
        assertTrue(read.all)
        assertEquals(listOf("h1", "w2", "w1"), m.walletTrades(wallet).map { it.id })
        m.clearWallet()
        assertEquals(null, m.walletRead)
    }

    @Test
    fun aServerThatKnowsNoWalletReadShowsOnlyThatWallet() = runBlocking {
        val wallet = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        // It answers the coin's newest trades, whoever made them, and says nothing of `all`.
        val m = model { 200 to """{"trades":[${trade("w1", 50, wallet)},${trade("o1", 45)}],"reading":false}""" }
        m.loadTrades()
        m.readWallet(wallet)
        assertEquals(listOf("w1"), m.walletTrades(wallet).map { it.id })
        assertFalse(m.walletRead!!.all)
    }

    @Test
    fun aNewestPageWithoutAllIsNotAWallet() = runBlocking {
        val m = model { 200 to page("a", 100, 1, null) }
        val answer = fakeApi(asked) { 200 to page("a", 100, 1, null) }.trades(Chain.BSC, coin)
        assertFalse(answer.all)
        assertEquals(null, answer.older)
        m.loadTrades()
        assertEquals(1, m.trades.size)
    }
    @Test
    fun readsItsPastFromTheBookWithoutBeingAsked() = runTest {
        val m = model { url ->
            200 to when {
                url.contains("before=c1") -> booked("b", 900, 200, "c2", true)
                url.contains("before=c2") -> booked("c", 700, 150, null, true)
                else -> page("a", 1_000, 100, "c1")
            }
        }
        m.loadTrades()
        m.readPast()
        assertEquals(listOf("c1", "c2"), cursors())
        // The most the server sends a page.
        assertTrue(asked.drop(1).all { it.contains("limit=200") })
        assertEquals(450, m.trades.size)
        assertEquals((1_000L downTo 551L).toList(), m.trades.map { it.at })
        assertEquals(Older.DONE, m.older)
    }

    @Test
    fun aPageFromTheChainLeavesTheRestToTheListsEnd() = runTest {
        val m = model { url ->
            200 to when {
                url.contains("before=") -> booked("b", 900, 200, "c2", false)
                else -> page("a", 1_000, 100, "c1")
            }
        }
        m.loadTrades()
        m.readPast()
        assertEquals(listOf("c1"), cursors())
        assertEquals(300, m.trades.size)
        assertEquals(Older.IDLE, m.older)
        // A server that does not say where its pages come from is read one page back too, no more.
        asked.clear()
        val old = model { url -> 200 to if (url.contains("before=")) page("b", 900, 100, "c2") else page("a", 1_000, 100, "c1") }
        old.loadTrades()
        old.readPast()
        assertEquals(listOf("c1"), cursors())
    }

    @Test
    fun waitsForTheServersFirstReadOfTheCoin() = runTest {
        var newest = 0
        val m = model { url ->
            200 to when {
                url.contains("before=") -> booked("b", 50, 10, null, true)
                newest++ < 2 -> page("a", 100, 0, null, reading = true)
                else -> page("a", 100, 20, "c1")
            }
        }
        m.loadTrades()
        m.readPast()
        assertEquals(listOf("c1"), cursors())
        assertEquals(30, m.trades.size)
        assertEquals(Older.DONE, m.older)
    }

    @Test
    fun aFailedPageIsAskedOnceMoreThenLeftToTheList() = runTest {
        var failed = 0
        val m = model { url ->
            if (url.contains("before=")) {
                failed++
                502 to """{"error":"unreadable"}"""
            } else {
                200 to page("a", 1_000, 100, "c1")
            }
        }
        m.loadTrades()
        m.readPast()
        assertEquals(2, failed)
        assertEquals(Older.FAILED, m.older)
        assertEquals(100, m.trades.size)
    }

    @Test
    fun aChainWithoutTheBookIsNotReadWithoutBeingAsked() = runTest {
        val mint = "Mint111111111111111111111111111111111111111"
        val m = TokenModel(fakeApi(asked) { 200 to page("a", 1_000, 100, "s0.x") }, Chain.SOLANA, mint)
        m.loadTrades()
        m.readPast()
        assertEquals(1, asked.size)
    }
}
