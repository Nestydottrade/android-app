package trade.nesty.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.ui.discover.CoinSearch
import trade.nesty.app.ui.venueName

/**
 * Search, and the way in for a coin the search does not list: a pasted address is asked for as a
 * coin on each chain it can be on. `token56` is a saved real answer of a launchpad coin on BNB.
 */
class SearchTest {
    private val asked = mutableListOf<String>()
    private val evm = "0x8049A38433eeFc879bb5f85a04d7D95252437777"
    private val mint = "CCiojyX7AzjBJmEZbNCWPPV32nnDak3UnUgBa4bLs9FJ"
    private val none = """{"tokens":[]}"""
    private val notFound = 404 to """{"error":"not_found"}"""

    private fun tokenAsks() = asked.filter { it.contains("/token/") }

    @Test
    fun anAddressCanBeACoinOnEveryChainOfItsKind() {
        assertEquals(listOf(Chain.BSC, Chain.BASE, Chain.ROBINHOOD, Chain.ETHEREUM, Chain.ELYSIUM), CoinSearch.chainsOf(evm))
        assertEquals(listOf(Chain.SOLANA), CoinSearch.chainsOf(mint))
        assertTrue(CoinSearch.chainsOf("pepe").isEmpty())
        assertTrue(CoinSearch.chainsOf("0x8049A38433eeFc879bb5").isEmpty())
        assertTrue(CoinSearch.chainsOf(evm + "00").isEmpty())
    }

    @Test
    fun aLaunchpadCoinTheSearchDoesNotListIsFoundByItsAddress() = runBlocking {
        val api = fakeApi(asked) { url ->
            when {
                url.contains("/search") -> 200 to none
                url.contains("/token/56/") -> 200 to Fixtures.text("token56")
                else -> notFound
            }
        }
        val found = CoinSearch.find(api, evm)!!.rows
        assertEquals(1, found.size)
        assertEquals("56", found.single().feed)
        assertEquals("ACTZ", found.single().symbol)
        assertTrue(found.single().address.equals(Fixtures.read<trade.nesty.app.data.Coin>("token56").address, ignoreCase = true))
        // Each EVM chain is asked once, Solana is not, and only the address goes out.
        assertEquals(listOf("1", "4663", "56", "8453", "99901"), tokenAsks().map { it.substringAfter("/token/").substringBefore("/") }.sorted())
        assertTrue(tokenAsks().all { it.endsWith("/$evm") })
    }

    @Test
    fun aListedCoinIsNotAskedForAgain() = runBlocking {
        val api = fakeApi(asked) { 200 to Fixtures.text("search") }
        val found = CoinSearch.find(api, evm)!!.rows
        assertTrue(found.isNotEmpty())
        assertTrue(tokenAsks().isEmpty())
    }

    @Test
    fun aNameIsOnlySearched() = runBlocking {
        val api = fakeApi(asked) { 200 to none }
        assertEquals(emptyList<Any>(), CoinSearch.find(api, "donotbuy")!!.rows)
        assertEquals(1, asked.size)
    }

    @Test
    fun anAddressNoChainKnowsIsNoCoin() = runBlocking {
        val api = fakeApi(asked) { url -> if (url.contains("/search")) 200 to none else notFound }
        assertEquals(emptyList<Any>(), CoinSearch.find(api, evm)!!.rows)
        assertEquals(5, tokenAsks().size)
    }

    @Test
    fun aChainThatDoesNotAnswerIsNotNoCoin() = runBlocking {
        val api = fakeApi(asked) { url ->
            when {
                url.contains("/search") -> 200 to none
                url.contains("/token/4663/") -> 502 to """{"error":"unreadable"}"""
                else -> notFound
            }
        }
        assertNull(CoinSearch.find(api, evm))
    }

    @Test
    fun aCoinIsStillFoundWhenTheSearchFails() = runBlocking {
        val api = fakeApi(asked) { url ->
            when {
                url.contains("/search") -> 502 to """{"error":"unreadable"}"""
                url.contains("/token/56/") -> 200 to Fixtures.text("token56")
                else -> notFound
            }
        }
        assertEquals("ACTZ", CoinSearch.find(api, evm)!!.rows.single().symbol)
        // A search that fails with no address to ask for says so, not "no coin".
        asked.clear()
        assertNull(CoinSearch.find(api, "actz"))
        // And when no chain knows the address either, it still cannot be said.
        val silent = fakeApi(asked) { url -> if (url.contains("/search")) 502 to """{"error":"unreadable"}""" else notFound }
        assertNull(CoinSearch.find(silent, evm))
    }

    @Test
    fun aSolanaMintIsAskedOnSolanaOnly() = runBlocking {
        val api = fakeApi(asked) { url ->
            when {
                url.contains("/search") -> 200 to none
                url.contains("/token/solana/") -> 200 to Fixtures.text("tokensol")
                else -> 400 to """{"error":"bad_request"}"""
            }
        }
        val found = CoinSearch.find(api, mint)!!.rows
        assertEquals("solana", found.single().feed)
        assertEquals(1, tokenAsks().size)
    }

    @Test
    fun anAnswerWithNothingInItIsNotACoin() = runBlocking {
        val api = fakeApi(asked) { url ->
            when {
                url.contains("/search") -> 200 to none
                url.contains("/token/8453/") -> 200 to """{"feed":"8453","address":"$evm","name":null,"symbol":null,"priceUsd":null}"""
                else -> notFound
            }
        }
        assertEquals(emptyList<Any>(), CoinSearch.find(api, evm)!!.rows)
    }

    /** `searchLaunchpad` is the real answer to q=donot, saved the day the search began to list launchpad coins. */
    @Test
    fun theSearchListsLaunchpadCoinsAfterTheListedOnes() = runBlocking {
        val api = fakeApi(asked) { 200 to Fixtures.text("searchLaunchpad") }
        val found = CoinSearch.find(api, "donot")!!
        assertEquals(false, found.partial)
        assertEquals(10, found.rows.size)
        assertEquals("uniswap", found.rows.first().dex)
        // A coin the server has only trades of: a price, but no market cap, image or pair.
        val bare = found.rows.first { it.feed == "4663" && it.symbol == "DONOTBUY" }
        assertNull(bare.mcapUsd)
        assertNull(bare.image)
        assertEquals("", bare.pair)
        assertEquals("Pons", bare.dex)
        assertTrue(bare.priceUsd!! > 0)
        // A name is never asked for on the chains.
        assertTrue(tokenAsks().isEmpty())
    }

    @Test
    fun aCoinListedTwiceIsOneRow() = runBlocking {
        val row = """{"feed":"56","address":"$evm","symbol":"TWICE"}"""
        val lower = """{"feed":"56","address":"${evm.lowercase()}","symbol":"TWICE"}"""
        val other = """{"feed":"8453","address":"$evm","symbol":"TWICE"}"""
        val api = fakeApi(asked) { 200 to """{"tokens":[$row,$lower,$other]}""" }
        // The same address on another chain is another coin.
        assertEquals(listOf("56", "8453"), CoinSearch.find(api, "twice")!!.rows.map { it.feed })
    }

    @Test
    fun withoutTheListingsSomeCoinsMayBeMissing() = runBlocking {
        val row = """{"feed":"56","address":"$evm","symbol":"ONLY","dex":"Flap"}"""
        val some = fakeApi(asked) { 200 to """{"tokens":[$row],"partial":true}""" }
        val found = CoinSearch.find(some, "only")!!
        assertTrue(found.partial)
        assertEquals(1, found.rows.size)
        // Nothing found without the listings is not "no coin".
        val empty = fakeApi(asked) { 200 to """{"tokens":[],"partial":true}""" }
        assertNull(CoinSearch.find(empty, "only"))
    }

    @Test
    fun launchpadsKeepTheirOwnSpelling() {
        assertEquals("Flap", venueName("Flap"))
        assertEquals("Pons", venueName("Pons"))
        assertEquals("four.meme", venueName("four.meme"))
        assertEquals("pump.fun", venueName("pumpfun"))
        assertEquals("Uniswap", venueName("uniswap"))
        assertNull(venueName(""))
        assertNull(venueName(null))
    }
}
