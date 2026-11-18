package trade.nesty.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * A coin the user added to Portfolio that the wallet got from outside Nesty. EVM chains cannot
 * list what a wallet holds, so its balance is read only once it is named here. Kept on the phone.
 */
@Serializable
data class Imported(
    /** The wallet's address on the coin's chain: lowercase on the EVM chains, as it is on Solana. */
    val wallet: String,
    val feed: String,
    /** The coin's address, the same way. */
    val token: String,
    val symbol: String? = null,
    val decimals: Int,
    /** When it was added, in milliseconds. */
    val at: Long,
) {
    val chain: Chain? get() = Chain.of(feed)
}

/** Where imported coins are kept: a private file of the app, never backed up (allowBackup is off). */
interface ImportDisk {
    fun read(): String?
    fun write(text: String)
}

class PrefsImportDisk(context: Context) : ImportDisk {
    private val prefs = context.getSharedPreferences("imports", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val KEY = "coins"
    }
}

/** The coins added to Portfolio, newest first. Nothing here is sent anywhere by itself. */
class ImportStore(private val disk: ImportDisk) {
    var coins: List<Imported> by mutableStateOf(read())
        private set

    private fun read(): List<Imported> = disk.read()?.let { runCatching { NestyJson.decodeFromString<List<Imported>>(it) }.getOrNull() } ?: emptyList()

    private fun same(c: Imported, wallet: String, chain: Chain, token: String) =
        c.wallet.equals(wallet, ignoreCase = true) && c.feed == chain.feed && Trading.sameToken(chain, c.token, token)

    /** One wallet address's coins. */
    fun of(wallet: String?): List<Imported> = if (wallet == null) emptyList() else coins.filter { it.wallet.equals(wallet, ignoreCase = true) }

    fun find(wallet: String, chain: Chain, token: String): Imported? = coins.firstOrNull { same(it, wallet, chain, token) }

    /** Adds a coin, or replaces the one of the same wallet, chain and address. False when the wallet has its most already. */
    @Synchronized
    fun put(c: Imported): Boolean {
        val chain = c.chain ?: return false
        val others = coins.filterNot { same(it, c.wallet, chain, c.token) }
        if (others.count { it.wallet.equals(c.wallet, ignoreCase = true) } >= MAX_PER_WALLET) return false
        coins = (others + c).sortedByDescending { it.at }
        disk.write(NestyJson.encodeToString(coins))
        return true
    }

    @Synchronized
    fun remove(wallet: String, chain: Chain, token: String) {
        val kept = coins.filterNot { same(it, wallet, chain, token) }
        if (kept.size == coins.size) return
        coins = kept
        disk.write(NestyJson.encodeToString(coins))
    }

    companion object {
        /** Coins one wallet address may add: with its positions they fit one balance read of 50. */
        const val MAX_PER_WALLET = 30
    }
}
