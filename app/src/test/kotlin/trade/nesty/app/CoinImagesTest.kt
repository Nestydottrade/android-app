package trade.nesty.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.CoinImages
import trade.nesty.app.data.FourMemeRecords
import trade.nesty.app.data.RestDisk
import trade.nesty.app.data.Served
import java.io.IOException

/** The coin pictures: one read per coin, only its address sent, kept once found. */
class CoinImagesTest {
    private val asked = mutableListOf<String>()
    private var clock = 1_000_000L
    /** The waits four.meme's reads were held for. */
    private val waited = mutableListOf<Long>()
    private val rest = MemRest()

    private class MemRest : RestDisk {
        var until = 0L
        override fun restUntil() = until
        override fun rest(until: Long) {
            this.until = until
        }
    }

    private fun records(answer: (String) -> Pair<Int, String>) =
        FourMemeRecords(fakeHttp(asked, answer), rest, clock = { clock }, pause = { waited += it; clock += it })

    private fun images(answer: (String) -> Pair<Int, String>) =
        CoinImages(fakeApi(asked, answer), records(answer), retryMs = 0, noneMs = 60_000, clock = { clock })

    private fun fourUrl(token: String) = "https://four.meme/meme-api/v1/private/token/get/v2?address=$token"

    private fun record(address: String, image: String?) =
        """{"code":0,"msg":"success","data":{"address":"$address","image":${image?.let { "\"$it\"" } ?: "null"},"name":"X"}}"""

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
    fun aSolanaCoinsPictureComesFromItsCoinAnswer() = runBlocking {
        // /image reads EVM launchpads only: a mint is read from its coin answer.
        val images = images { 200 to """{"feed":"solana","address":"So1Mint","symbol":"S","image":"https://ipfs.example/s.png"}""" }
        images.ask(Chain.SOLANA, "So1Mint")
        assertEquals(listOf("https://nesty.trade/api/v1/token/solana/So1Mint"), asked)
        assertEquals("https://ipfs.example/s.png", images.of(Chain.SOLANA, "So1Mint"))
        // A mint differing only in case is another coin.
        assertNull(images.of(Chain.SOLANA, "so1mint"))
    }

    @Test
    fun aPoolCoinsPictureComesFromItsListingInTheCoinAnswer() = runBlocking {
        val images = images { url ->
            if ("/image/" in url) 200 to """{"image":null}"""
            else 200 to """{"feed":"4663","address":"0xP","venue":"pool","launchpad":null,"image":"https://cdn.example/p.png"}"""
        }
        images.ask(Chain.ROBINHOOD, "0xP")
        assertEquals(listOf("https://nesty.trade/api/v1/image/4663/0xP", "https://nesty.trade/api/v1/token/4663/0xP"), asked)
        assertEquals("https://cdn.example/p.png", images.of(Chain.ROBINHOOD, "0xP"))
    }

    @Test
    fun aFourMemeCoinsPictureComesFromFourMemesOwnRecord() = runBlocking {
        val images = images { url ->
            when {
                url.startsWith("https://four.meme/") -> 200 to record("0xf00d", "https://static.four.meme/market/f.png")
                "/image/" in url -> 200 to """{"image":null}"""
                else -> 200 to """{"feed":"56","address":"0xF00D","venue":"launchpad","launchpad":"four.meme","image":null}"""
            }
        }
        images.ask(Chain.BSC, "0xF00D")
        assertEquals(
            listOf("https://nesty.trade/api/v1/image/56/0xF00D", "https://nesty.trade/api/v1/token/56/0xF00D", fourUrl("0xF00D")),
            asked,
        )
        assertEquals("https://static.four.meme/market/f.png", images.of(Chain.BSC, "0xf00d"))
    }

    @Test
    fun aScreenWithTheServersAnswerAsksOnlyWhatItCouldNot() = runBlocking {
        val images = images { url ->
            if (url.startsWith("https://four.meme/")) 200 to record("0xf1", "https://static.four.meme/market/1.png")
            else 200 to """{"image":"https://ipfs.example/flap.png"}"""
        }
        // A four.meme coin: its record alone.
        images.ask(Chain.BSC, "0xF1", Served("four.meme"))
        assertEquals(listOf(fourUrl("0xF1")), asked)
        // A coin of another launchpad: the server reads its metadata to the end.
        images.ask(Chain.BSC, "0xF2", Served("Flap"))
        assertEquals(listOf(fourUrl("0xF1"), "https://nesty.trade/api/v1/image/56/0xF2"), asked)
        assertEquals("https://ipfs.example/flap.png", images.of(Chain.BSC, "0xF2"))
        // A coin of no launchpad whose listing has no picture: nothing is left to ask.
        images.ask(Chain.BSC, "0xF3", Served(null))
        assertEquals(2, asked.size)
        assertNull(images.of(Chain.BSC, "0xF3"))
        // four.meme is on BNB Chain only.
        images.ask(Chain.BASE, "0xF4", Served("four.meme"))
        assertEquals("https://nesty.trade/api/v1/image/8453/0xF4", asked.last())
    }

    @Test
    fun fourMemeIsReadOneASecond() = runBlocking {
        val images = images { url -> 200 to record(url.substringAfter("address="), "https://static.four.meme/market/x.png") }
        images.ask(Chain.BSC, "0xa1", Served("four.meme"))
        images.ask(Chain.BSC, "0xa2", Served("four.meme"))
        clock += 5_000
        images.ask(Chain.BSC, "0xa3", Served("four.meme"))
        assertEquals(3, asked.size)
        // The second waited out the first's second; the third came long after.
        assertEquals(listOf(1_000L), waited)
    }

    @Test
    fun aRefusalRestsFourMemeForFifteenMinutesAcrossRestarts() = runBlocking {
        val answer: (String) -> Pair<Int, String> = { 429 to """{"code":429}""" }
        val images = images(answer)
        images.ask(Chain.BSC, "0xb1", Served("four.meme"))
        // Refused: not asked again at once, and the rest is kept.
        assertEquals(1, asked.size)
        assertEquals(clock + 15 * 60_000, rest.until)
        images.ask(Chain.BSC, "0xb2", Served("four.meme"))
        assertEquals(1, asked.size)
        // A restart keeps resting.
        val again = CoinImages(fakeApi(asked, answer), records(answer), retryMs = 0, clock = { clock })
        again.ask(Chain.BSC, "0xb2", Served("four.meme"))
        assertEquals(1, asked.size)
        // Once the rest is over, the coin refused is read again when drawn.
        clock += 15 * 60_000
        again.ask(Chain.BSC, "0xb1", Served("four.meme"))
        assertEquals(2, asked.size)
    }

    @Test
    fun aRecordOfAnotherCoinIsNone() = runBlocking {
        val images = images { 200 to record("0xother", "https://static.four.meme/market/o.png") }
        images.ask(Chain.BSC, "0xc1", Served("four.meme"))
        images.ask(Chain.BSC, "0xc1", Served("four.meme"))
        assertEquals(1, asked.size)
        assertNull(images.of(Chain.BSC, "0xc1"))
    }

    @Test
    fun aRecordNamesOnlyAWebPicture() {
        assertEquals("https://static.four.meme/a.webp", FourMemeRecords.pictureIn(record("0xA", "https://static.four.meme/a.webp"), "0xa"))
        assertNull(FourMemeRecords.pictureIn(record("0xA", "ipfs://QmX"), "0xa"))
        // No record, or one without its picture yet, is no answer: asked again later.
        assertThrows(IOException::class.java) { FourMemeRecords.pictureIn("""{"code":-1010,"msg":"not found"}""", "0xa") }
        assertThrows(IOException::class.java) { FourMemeRecords.pictureIn(record("0xA", null), "0xa") }
        assertThrows(IOException::class.java) { FourMemeRecords.pictureIn("<html>blocked</html>", "0xa") }
    }

    @Test
    fun noneFoundIsAskedAgainOnlyAfterAWhile() = runBlocking {
        val images = images { 200 to """{"image":null}""" }
        images.ask(Chain.BSC, "0x1")
        // No launchpad picture, and the coin's answer has none either.
        assertEquals(listOf("https://nesty.trade/api/v1/image/56/0x1", "https://nesty.trade/api/v1/token/56/0x1"), asked)
        images.ask(Chain.BSC, "0x1")
        assertEquals(2, asked.size)
        assertNull(images.of(Chain.BSC, "0x1"))
        clock += 60_000
        images.ask(Chain.BSC, "0x1")
        assertEquals(4, asked.size)
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
