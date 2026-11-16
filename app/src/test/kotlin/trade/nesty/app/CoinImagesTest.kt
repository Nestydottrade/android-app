package trade.nesty.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.CoinImages

/** The signals' coin pictures: one read per coin, only its address sent, kept once found. */
class CoinImagesTest {
    private val asked = mutableListOf<String>()
    private var clock = 1_000_000L

    private fun images(answer: (String) -> Pair<Int, String>) =
        CoinImages(fakeApi(asked, answer), retryMs = 0, noneMs = 60_000, clock = { clock })

    @Test
    fun aCoinIsReadOnceAndKept() = runBlocking {
        val images = images { 200 to """{"image":"https://ipfs.example/abc.png"}""" }
        images.ask(Chain.BSC, "0xAbC")
        images.ask(Chain.BSC, "0xabc")
        assertEquals(listOf("https://nesty.trade/api/v1/image/56/0xAbC"), asked)
        assertEquals("https://ipfs.example/abc.png", images.of(Chain.BSC, "0xABC"))
        // The same address on another chain is another coin.
        assertNull(images.of(Chain.BASE, "0xabc"))
    }

    @Test
    fun noneFoundIsAskedAgainOnlyAfterAWhile() = runBlocking {
        val images = images { 200 to """{"image":null}""" }
        images.ask(Chain.BSC, "0x1")
        images.ask(Chain.BSC, "0x1")
        assertEquals(1, asked.size)
        assertNull(images.of(Chain.BSC, "0x1"))
        clock += 60_000
        images.ask(Chain.BSC, "0x1")
        assertEquals(2, asked.size)
    }

    @Test
    fun aReadWithNoAnswerIsMadeAgain() = runBlocking {
        var n = 0
        val images = images { if (n++ == 0) 503 to """{"error":"busy"}""" else 200 to """{"image":"https://ipfs.example/x.png"}""" }
        images.ask(Chain.BASE, "0x2")
        assertEquals(2, asked.size)
        assertEquals("https://ipfs.example/x.png", images.of(Chain.BASE, "0x2"))
    }

    @Test
    fun aCoinTheServerCannotReadIsNotAskedAgainAtOnce() = runBlocking {
        val images = images { 404 to """{"error":"not_found"}""" }
        images.ask(Chain.ROBINHOOD, "0x3")
        images.ask(Chain.ROBINHOOD, "0x3")
        assertEquals(1, asked.size)
        assertNull(images.of(Chain.ROBINHOOD, "0x3"))
    }
}
