package trade.nesty.app.alerts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import trade.nesty.app.data.AlertPrefs
import trade.nesty.app.data.AlertSwitch
import trade.nesty.app.data.NestyJson

/** What an alert is about; the Notifications screen's filters group them. */
@Serializable
enum class AlertKind {
    /** A step of an exit plan sold, failed, or waits for the vault. */
    PLAN,

    /** The user's own trades and moves. */
    YOURS,

    /** A coin the wallet holds: a followed wallet sold it. */
    HELD,

    /** A followed wallet traded. */
    TRACKER,

    /** Proven wallets bought the same coin. */
    SIGNAL,
}

/** The mark beside an alert, as the board draws it. */
@Serializable
enum class AlertTone { BAD, WARN, WALLETS, SIGNAL, PLAN, OK, INFO }

/** Where an alert's button goes. */
@Serializable
data class AlertLink(val to: String, val feed: String? = null, val token: String? = null) {
    companion object {
        const val POSITION = "position"
        const val TOKEN = "token"
        const val PORTFOLIO = "portfolio"
        const val ALERTS = "alerts"
        /** The app's Update screen. */
        const val UPDATE = "update"
        /** The Wallets screen: where a wallet made from one key gets the side it lacks. */
        const val WALLETS = "wallets"

        fun position(feed: String, token: String) = AlertLink(POSITION, feed, token)
        fun token(feed: String, token: String) = AlertLink(TOKEN, feed, token)
    }
}

@Serializable
data class Alert(
    val id: String,
    val kind: AlertKind,
    val tone: AlertTone,
    val title: String,
    val body: String,
    val at: Long,
    val link: AlertLink? = null,
    /** The followed wallet a Tracker alert is about, so its alerts can be muted. */
    val who: String? = null,
    val read: Boolean = false,
)

/** Where the alerts are kept: the phone's preferences, or memory in tests. */
interface AlertDisk {
    fun read(): String?
    fun write(json: String)
}

class PrefsAlertDisk(context: Context) : AlertDisk {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(json: String) {
        prefs.edit().putString(KEY, json).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}

/** The Notifications screen's list, newest first, on the phone only. */
class AlertStore(private val disk: AlertDisk) {
    var list by mutableStateOf(read())
        private set

    val unread: Int get() = list.count { !it.read }

    private fun read(): List<Alert> =
        disk.read()?.let { runCatching { NestyJson.decodeFromString<List<Alert>>(it) }.getOrNull() } ?: emptyList()

    @Synchronized
    private fun set(next: List<Alert>) {
        list = next
        disk.write(NestyJson.encodeToString(next))
    }

    /** Adds an alert once: false when one with its id is there already. */
    @Synchronized
    fun add(a: Alert): Boolean {
        if (list.any { it.id == a.id }) return false
        set((listOf(a) + list).sortedByDescending { it.at }.take(MAX))
        return true
    }

    /** Marks every alert read; returns the ones it changed, for Undo. */
    @Synchronized
    fun markAllRead(): Set<String> {
        val changed = list.filter { !it.read }.map { it.id }.toSet()
        if (changed.isNotEmpty()) set(list.map { if (it.id in changed) it.copy(read = true) else it })
        return changed
    }

    @Synchronized
    fun markRead(id: String) {
        if (list.any { it.id == id && !it.read }) set(list.map { if (it.id == id) it.copy(read = true) else it })
    }

    @Synchronized
    fun markUnread(ids: Set<String>) {
        if (ids.isNotEmpty()) set(list.map { if (it.id in ids) it.copy(read = false) else it })
    }

    companion object {
        const val MAX = 150
    }
}

/**
 * Every alert passes here: it is kept for the Notifications screen, then shown at the bottom of
 * the screen while the app is in front, or as an Android notification while it is not. The menu's
 * switches and muted wallets decide which are kept at all.
 */
class Alerts(private val store: AlertStore, private val settings: AlertPrefs, private val post: (Alert) -> Unit) {
    /** True while one of the app's screens is in front. */
    @Volatile
    var visible = false

    private val shownFlow = MutableSharedFlow<Alert>(extraBufferCapacity = 16)

    /** New alerts to show at the bottom of the screen while it is in front. */
    val shown: SharedFlow<Alert> = shownFlow.asSharedFlow()

    fun wants(kind: AlertKind, who: String? = null): Boolean {
        if (who != null && settings.isMuted(who)) return false
        return when (kind) {
            AlertKind.PLAN -> settings.alert(AlertSwitch.PLANS)
            AlertKind.HELD -> settings.alert(AlertSwitch.HELD)
            AlertKind.TRACKER -> settings.alert(AlertSwitch.TRACKER)
            AlertKind.SIGNAL -> settings.alert(AlertSwitch.SIGNALS)
            AlertKind.YOURS -> true
        }
    }

    /**
     * Keeps `a` and tells the user. `toast` false: the screen already said it (the user's own
     * trades), so only a notification goes out, and only while the app is not in front.
     */
    fun push(a: Alert, toast: Boolean = true): Boolean {
        if (!wants(a.kind, a.who)) return false
        if (!store.add(a)) return false
        if (visible) {
            if (toast) shownFlow.tryEmit(a)
        } else {
            post(a)
        }
        return true
    }

    /** The Notifications screen's filters. */
    enum class Filter(val label: String) {
        ALL("All"),
        YOURS("Your coins"),
        TRACKER("Tracker"),
        SIGNALS("Signals");

        fun has(a: Alert): Boolean = when (this) {
            ALL -> true
            YOURS -> a.kind == AlertKind.PLAN || a.kind == AlertKind.YOURS || a.kind == AlertKind.HELD
            TRACKER -> a.kind == AlertKind.TRACKER || (a.kind == AlertKind.HELD && a.who != null)
            SIGNALS -> a.kind == AlertKind.SIGNAL
        }
    }
}
