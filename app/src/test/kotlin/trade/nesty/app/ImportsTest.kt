package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ImportDisk
import trade.nesty.app.data.ImportStore
import trade.nesty.app.data.Imported
import trade.nesty.app.sol.SolVectors

/** The coins added to Portfolio by hand, as the phone keeps them. Every wallet and coin is made up. */
class ImportsTest {
    /** The app's private file, in memory. */
    private class MemoryDisk(var text: String? = null) : ImportDisk {
        var writes = 0

        override fun read(): String? = text

        override fun write(text: String) {
            this.text = text
            writes++
        }
    }

    private val wallet = "0x00000000000000000000000000000000000000aa"

    private fun coin(n: Int) = "0x" + n.toString(16).padStart(40, '0')

    private fun capitals(a: String) = a.uppercase().replace("0X", "0x")

    private fun added(token: String, at: Long, w: String = wallet, feed: String = Chain.BSC.feed) = Imported(w, feed, token, "KILN", 18, at)

    @Test
    fun aCoinIsAddedOnceAndKeptOnThePhone() {
        val disk = MemoryDisk()
        val store = ImportStore(disk)
        assertTrue(store.put(added(coin(1), 1_000)))
        // The same coin again, spelled in capitals: replaced, not added twice.
        assertTrue(store.put(added(capitals(coin(1)), 2_000)))
        assertEquals(1, store.coins.size)
        assertEquals(2_000L, store.coins.single().at)
        // The same address on another chain is another coin.
        assertTrue(store.put(added(coin(1), 3_000, feed = Chain.BASE.feed)))
        // What was kept reads back as it was, newest first.
        val again = ImportStore(disk)
        assertEquals(store.coins, again.coins)
        assertEquals(listOf(3_000L, 2_000L), again.coins.map { it.at })
    }

    @Test
    fun eachWalletAddsThirtyCoinsAtMost() {
        val store = ImportStore(MemoryDisk())
        repeat(ImportStore.MAX_PER_WALLET) { assertTrue(store.put(added(coin(it + 1), it.toLong()))) }
        assertFalse(store.put(added(coin(99), 100)))
        // One it has already can be added again, and another wallet has thirty of its own.
        assertTrue(store.put(added(coin(1), 200)))
        assertTrue(store.put(added(coin(99), 300, w = "0x00000000000000000000000000000000000000bb")))
        assertEquals(ImportStore.MAX_PER_WALLET, store.of(wallet).size)
        assertEquals(ImportStore.MAX_PER_WALLET + 1, store.coins.size)
    }

    @Test
    fun evmAddressesMatchInAnyCaseAndSolanaOnesExactly() {
        val store = ImportStore(MemoryDisk())
        store.put(added(coin(1), 1_000))
        val solWallet = SolVectors.OTHER
        val mint = SolVectors.STRANGER
        store.put(Imported(solWallet, Chain.SOLANA.feed, mint, "SOLC", 6, 2_000))
        assertEquals(listOf(coin(1)), store.of(capitals(wallet)).map { it.token })
        assertNotNull(store.find(wallet, Chain.BSC, capitals(coin(1))))
        assertNull(store.find(wallet, Chain.BASE, coin(1)))
        assertNotNull(store.find(solWallet, Chain.SOLANA, mint))
        assertNull(store.find(solWallet, Chain.SOLANA, mint.lowercase()))
        assertTrue(store.of(null).isEmpty())
    }

    @Test
    fun removingTakesOutThatCoinAlone() {
        val disk = MemoryDisk()
        val store = ImportStore(disk)
        store.put(added(coin(1), 1_000))
        store.put(added(coin(2), 2_000))
        val writes = disk.writes
        // Not there: nothing changes and nothing is written.
        store.remove(wallet, Chain.BSC, coin(3))
        store.remove(wallet, Chain.BASE, coin(1))
        assertEquals(writes, disk.writes)
        store.remove(capitals(wallet), Chain.BSC, capitals(coin(1)))
        assertEquals(listOf(coin(2)), store.coins.map { it.token })
        assertEquals(listOf(coin(2)), ImportStore(disk).coins.map { it.token })
    }

    @Test
    fun aChainTheAppDoesNotKnowOrAnUnreadableFileAddsNothing() {
        val store = ImportStore(MemoryDisk())
        assertFalse(store.put(added(coin(1), 1_000, feed = "nowhere")))
        assertTrue(store.coins.isEmpty())
        assertTrue(ImportStore(MemoryDisk("not json")).coins.isEmpty())
    }
}
