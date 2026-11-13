package trade.nesty.app

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import trade.nesty.app.data.ApiException
import trade.nesty.app.data.Chain
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.SseEvent
import trade.nesty.app.data.SseParser
import trade.nesty.app.data.StreamEvent

/** The client against a fake server: what it asks for, and what it makes of the answers. */
class ApiTest {
    private val asked = mutableListOf<String>()

    private fun api(answer: (String) -> Pair<Int, String>): NestyApi = fakeApi(asked, answer)

    @Test
    fun pathsAndQueries() = runBlocking {
        val a = api { 200 to Fixtures.text(if (it.contains("/feed/")) "feed56" else "candlesB") }
        a.feed(Chain.BSC, 50)
        a.candles(Chain.BASE, "0xabc", "1s", 1500)
        assertEquals("https://nesty.trade/api/v1/feed/56?limit=50", asked[0])
        assertEquals("https://nesty.trade/api/v1/candles/8453/0xabc?interval=1s&limit=1500", asked[1])
    }

    @Test
    fun theSignalWalletsAskSendsOnlyTheCoin() = runBlocking {
        val a = api { 200 to Fixtures.text("tradersSignal") }
        val answer = a.traders(Chain.BSC, "0xabc", 100)
        // No wallet goes to the server: it knows a signal's wallets itself.
        assertEquals("https://nesty.trade/api/v1/traders/56/0xabc?limit=100", asked.single())
        assertEquals(3, answer.signal?.wallets?.size)
    }

    @Test
    fun manyCoinsGoInOneQuery() = runBlocking {
        val a = api { 200 to Fixtures.text("sharesB") }
        a.shares(listOf(Chain.BSC to "0x1", Chain.ELYSIUM to "0x2", Chain.SOLANA to "So1"))
        assertEquals("https://nesty.trade/api/v1/shares?tokens=56:0x1,99901:0x2,solana:So1", asked.single().replace("%3A", ":").replace("%2C", ","))
    }

    @Test
    fun aRefusalIsAnApiException() = runBlocking {
        val a = api { 429 to """{"error":"busy"}""" }
        try {
            a.feed(Chain.BSC)
            fail("No exception")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
            assertEquals("busy", e.code)
            assertEquals(5, e.retryAfterSeconds)
            assertTrue(e.busy)
        }
    }

    @Test
    fun noAppReleaseYetIsNull() = runBlocking {
        val a = api { 404 to """{"error":"not_found"}""" }
        assertNull(a.app())
    }

    @Test
    fun anAnswerOffItsShapeSaysSo() = runBlocking {
        val a = api { 200 to "[1,2,3]" }
        try {
            a.token(Chain.BSC, "0x1")
            fail("No exception")
        } catch (e: ApiException) {
            assertEquals("shape", e.code)
        }
    }

    @Test
    fun theStreamOpensThenSendsEvents() = runBlocking {
        val body = buildString {
            append(": hello\n\n")
            append("event: market\ndata: {\"priceUsd\":0.5,\"mcapUsd\":500000,\"at\":1}\n\n")
            append("event: trade\ndata: {\"id\":\"t1\",\"tx\":\"0x\",\"at\":2,\"side\":\"sell\",\"tokens\":10,\"priceUsd\":0.5}\n\n")
            append("event: candle\ndata: {\"interval\":\"1s\",\"time\":3,\"open\":1,\"high\":2,\"low\":0.5,\"close\":1.5,\"volume\":9}\n\n")
            append("event: other\ndata: {}\n\n")
        }
        val events = api { 200 to body }.stream(Chain.BSC, "0x1", "1s").toList()
        assertEquals(StreamEvent.Open, events[0])
        assertEquals(500000.0, (events[1] as StreamEvent.MarketUpdate).market.mcapUsd)
        assertEquals("t1", (events[2] as StreamEvent.NewTrade).trade.id)
        assertEquals(1.5, (events[3] as StreamEvent.NewCandle).candle.toCandle().close, 0.0)
        assertEquals(4, events.size)
    }

    @Test
    fun sseLinesMakeEvents() {
        val p = SseParser()
        assertNull(p.line("event: coin"))
        assertNull(p.line("data: {\"a\":"))
        assertNull(p.line("data: 1}"))
        assertEquals(SseEvent("coin", "{\"a\":\n1}"), p.line(""))
        assertNull(p.line(": a comment"))
        assertNull(p.line(""))
        assertNull(p.line("data:x"))
        assertEquals(SseEvent("message", "x"), p.line(""))
    }
}
