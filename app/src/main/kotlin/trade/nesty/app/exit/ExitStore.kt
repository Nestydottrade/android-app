package trade.nesty.app.exit

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.encodeToString
import trade.nesty.app.data.NestyJson

/** Where the plans are kept: the phone's preferences, or memory in tests. */
interface ExitDisk {
    fun read(): String?
    fun write(json: String)
}

/** The app's private preferences. Written at once (commit), so a sell marked done stays marked. */
class PrefsExitDisk(context: Context) : ExitDisk {
    private val prefs = context.getSharedPreferences("exits", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(json: String) {
        prefs.edit().putString(KEY, json).commit()
    }

    private companion object {
        const val KEY = "plans"
    }
}

/** The exit plans of every wallet on this phone. Nothing here is sent anywhere. */
class ExitStore(private val disk: ExitDisk) {
    var plans by mutableStateOf(read())
        private set

    private fun read(): List<ExitPlan> =
        disk.read()?.let { runCatching { NestyJson.decodeFromString<List<ExitPlan>>(it) }.getOrNull() } ?: emptyList()

    fun get(key: String): ExitPlan? = plans.firstOrNull { it.key == key }

    fun of(walletId: String?, feed: String, token: String): ExitPlan? =
        walletId?.let { get(ExitMath.keyOf(it, feed, token)) }

    /** Serialised read-modify-write. */
    @Synchronized
    fun update(change: (List<ExitPlan>) -> List<ExitPlan>) {
        val next = change(plans)
        if (next == plans) return
        disk.write(NestyJson.encodeToString(next))
        plans = next
    }

    fun put(plan: ExitPlan) = update { list -> list.filterNot { it.key == plan.key } + plan }

    fun remove(key: String) = update { list -> list.filterNot { it.key == key } }

    fun patch(key: String, change: (ExitPlan) -> ExitPlan) = update { list -> list.map { if (it.key == key) change(it) else it } }

    fun patchRule(key: String, id: String, change: (ExitRule) -> ExitRule) =
        patch(key) { p -> p.copy(rules = p.rules.map { if (it.id == id) change(it) else it }) }
}
