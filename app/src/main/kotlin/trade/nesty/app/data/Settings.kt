package trade.nesty.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A wallet the user follows in the Tracker: an address and the name they gave it. No key. */
@Serializable
data class Followed(val address: String, val name: String = "")

@Serializable
data class Preset(val slippage: Int, val speed: String)

/**
 * What the user picked, kept on the phone only: the quick buy amount, followed wallets, the
 * watchlist and the trade presets. Nothing here is sent anywhere.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nesty", Context.MODE_PRIVATE)

    var quickBuy by mutableStateOf(prefs.getInt(KEY_QUICK, 25))
        private set

    var followed by mutableStateOf(readList<Followed>(KEY_FOLLOWED))
        private set

    var watchlist by mutableStateOf(prefs.getStringSet(KEY_WATCH, emptySet())!!.toSet())
        private set

    var presets by mutableStateOf(readList<Preset>(KEY_PRESETS).takeIf { it.size == 3 } ?: DEFAULT_PRESETS)
        private set

    fun pickQuickBuy(usd: Int) {
        quickBuy = usd
        prefs.edit().putInt(KEY_QUICK, usd).apply()
    }

    fun nextQuickBuy() {
        val i = QUICK_STEPS.indexOf(quickBuy)
        pickQuickBuy(QUICK_STEPS[(i + 1).mod(QUICK_STEPS.size)])
    }

    fun isFollowed(address: String) = followed.any { it.address.equals(address, ignoreCase = true) }

    fun nameOf(address: String?): String? =
        address?.let { a -> followed.firstOrNull { it.address.equals(a, ignoreCase = true) }?.name?.takeIf { it.isNotBlank() } }

    /** Follows a wallet; false when 20 are followed already or it is followed. */
    fun follow(address: String, name: String = ""): Boolean {
        if (isFollowed(address) || followed.size >= MAX_FOLLOWED) return false
        followed = followed + Followed(address, name.trim().take(16))
        writeList(KEY_FOLLOWED, followed)
        return true
    }

    fun unfollow(address: String) {
        followed = followed.filterNot { it.address.equals(address, ignoreCase = true) }
        writeList(KEY_FOLLOWED, followed)
    }

    fun rename(address: String, name: String) {
        followed = followed.map { if (it.address.equals(address, ignoreCase = true)) it.copy(name = name.trim().take(16)) else it }
        writeList(KEY_FOLLOWED, followed)
    }

    fun isWatched(chain: Chain, address: String) = "${chain.feed}:${address.lowercase()}" in watchlist

    fun toggleWatch(chain: Chain, address: String) {
        val key = "${chain.feed}:${address.lowercase()}"
        watchlist = if (key in watchlist) watchlist - key else watchlist + key
        prefs.edit().putStringSet(KEY_WATCH, watchlist).apply()
    }

    fun setPreset(index: Int, preset: Preset) {
        presets = presets.toMutableList().also { it[index] = preset }
        writeList(KEY_PRESETS, presets)
    }

    private inline fun <reified T> readList(key: String): List<T> =
        prefs.getString(key, null)?.let { runCatching { NestyJson.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()

    private inline fun <reified T> writeList(key: String, list: List<T>) {
        prefs.edit().putString(key, NestyJson.encodeToString(list)).apply()
    }

    companion object {
        const val MAX_FOLLOWED = 20
        val QUICK_STEPS = listOf(10, 25, 50, 100)
        val DEFAULT_PRESETS = listOf(Preset(15, "Fast"), Preset(25, "Turbo"), Preset(5, "Normal"))
        private const val KEY_QUICK = "quickBuy"
        private const val KEY_FOLLOWED = "followed"
        private const val KEY_WATCH = "watchlist"
        private const val KEY_PRESETS = "presets"
    }
}
