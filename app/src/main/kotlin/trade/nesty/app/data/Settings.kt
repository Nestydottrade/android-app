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

/** The menu's alert switches. */
enum class AlertSwitch(val key: String, val default: Boolean) {
    TRACKER("alertTracker", true),
    SIGNALS("alertSignals", true),
    PLANS("alertPlans", true),
    HELD("alertHeld", true),

    /** Alerts with Nesty closed: the watch service then runs for them too. */
    CLOSED("alertClosed", false),
}

/** What the alerts read of the settings; tests stand in for it. */
interface AlertPrefs {
    fun alert(which: AlertSwitch): Boolean
    fun isMuted(address: String?): Boolean
    fun followedWallets(): List<Followed>
}

/**
 * What the user picked, kept on the phone only: the quick buy amount, followed wallets, the
 * watchlist and the trade presets. Nothing here is sent anywhere.
 */
class Settings(context: Context) : AlertPrefs {
    private val prefs: SharedPreferences = context.getSharedPreferences("nesty", Context.MODE_PRIVATE)

    var quickBuy by mutableStateOf(prefs.getInt(KEY_QUICK, 25))
        private set

    /**
     * The quick buy amounts in dollars, smallest first: a coin page's one-tap buys, the buy sheet's
     * chips, and what the list amount is picked from.
     */
    var quickAmounts by mutableStateOf(readAmounts())
        private set

    var followed by mutableStateOf(readList<Followed>(KEY_FOLLOWED))
        private set

    var watchlist by mutableStateOf(prefs.getStringSet(KEY_WATCH, emptySet())!!.toSet())
        private set

    /** Minutes away from the app after which the wallets lock: 5, 15 or 60. */
    var lockAfterMinutes by mutableStateOf(prefs.getInt(KEY_LOCK, 15).takeIf { it in LOCK_STEPS } ?: 15)
        private set

    var presets by mutableStateOf(readList<Preset>(KEY_PRESETS).takeIf { it.size == 3 } ?: DEFAULT_PRESETS)
        private set

    private val alertOn = AlertSwitch.entries.associateWith { mutableStateOf(prefs.getBoolean(it.key, it.default)) }

    /** Followed wallets whose trades still show in the Tracker, with no alerts. */
    var muted by mutableStateOf(prefs.getStringSet(KEY_MUTED, emptySet())!!.toSet())
        private set

    override fun alert(which: AlertSwitch): Boolean = alertOn.getValue(which).value

    override fun followedWallets(): List<Followed> = followed

    fun setAlert(which: AlertSwitch, on: Boolean) {
        alertOn.getValue(which).value = on
        prefs.edit().putBoolean(which.key, on).apply()
    }

    override fun isMuted(address: String?): Boolean = address != null && address.lowercase() in muted

    fun mute(address: String, on: Boolean) {
        val a = address.lowercase()
        muted = if (on) muted + a else muted - a
        prefs.edit().putStringSet(KEY_MUTED, muted).apply()
    }

    fun pickQuickBuy(usd: Int) {
        quickBuy = usd
        prefs.edit().putInt(KEY_QUICK, usd).apply()
    }

    fun pickLockAfter(minutes: Int) {
        if (minutes !in LOCK_STEPS) return
        lockAfterMinutes = minutes
        prefs.edit().putInt(KEY_LOCK, minutes).apply()
    }

    fun nextQuickBuy() {
        val i = quickAmounts.indexOf(quickBuy)
        pickQuickBuy(quickAmounts[(i + 1).mod(quickAmounts.size)])
    }

    /**
     * New quick buy amounts, saved smallest first. The list amount keeps its place among them.
     * False, and nothing saved, unless they are [QUICK_COUNT] different amounts of $1 to [QUICK_MAX].
     */
    fun setQuickAmounts(amounts: List<Int>): Boolean {
        if (!okAmounts(amounts)) return false
        val sorted = amounts.sorted()
        val at = quickAmounts.indexOf(quickBuy)
        quickAmounts = sorted
        prefs.edit().putString(KEY_AMOUNTS, sorted.joinToString(",")).apply()
        if (quickBuy !in sorted) pickQuickBuy(sorted.getOrNull(at) ?: sorted.minBy { kotlin.math.abs(it - quickBuy) })
        return true
    }

    private fun readAmounts(): List<Int> =
        prefs.getString(KEY_AMOUNTS, null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { okAmounts(it) }?.sorted() ?: QUICK_STEPS

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
        /** The quick buy amounts until the user sets their own. */
        val QUICK_STEPS = listOf(10, 25, 50, 100)
        const val QUICK_COUNT = 4
        const val QUICK_MAX = 100_000

        fun okAmounts(list: List<Int>): Boolean = list.size == QUICK_COUNT && list.all { it in 1..QUICK_MAX } && list.distinct().size == list.size
        private const val KEY_AMOUNTS = "quickAmounts"
        val LOCK_STEPS = listOf(5, 15, 60)
        private const val KEY_LOCK = "lockAfter"
        val DEFAULT_PRESETS = listOf(Preset(15, "Fast"), Preset(25, "Turbo"), Preset(5, "Normal"))
        private const val KEY_QUICK = "quickBuy"
        private const val KEY_FOLLOWED = "followed"
        private const val KEY_WATCH = "watchlist"
        private const val KEY_PRESETS = "presets"
        private const val KEY_MUTED = "muted"
    }
}
