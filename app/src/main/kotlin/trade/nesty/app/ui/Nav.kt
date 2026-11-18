package trade.nesty.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import trade.nesty.app.data.Chain
import java.util.UUID

/** Where the app can go. Tabs are the four of the bottom bar. */
sealed interface Route {
    data object Discover : Route
    data class Smart(val leaderboard: Boolean = false) : Route
    data object Tracker : Route
    data object Portfolio : Route

    /**
     * `buy`: open its buy sheet at once (a buy whose moved funds wait for the user, a failed buy
     * tried again), at `buyUsd` when given.
     */
    data class Token(val chain: Chain, val address: String, val buy: Boolean = false, val buyUsd: Double? = null) : Route
    data class TradesHolders(val chain: Chain, val address: String) : Route

    /** The wallet's position in a coin: what it holds, its result, selling. */
    data class Position(val chain: Chain, val address: String) : Route

    /** One trade of Activity: its costs, route, settings and transactions. `wallet` as its entry keeps it. */
    data class Trade(val wallet: String, val tx: String) : Route
    data class Wallet(val chain: Chain, val address: String) : Route
    data object Search : Route
    /** `alerts`: open at the Alerts section. */
    data class Menu(val alerts: Boolean = false) : Route
    data object Stats : Route
    data object Updates : Route

    /** The app's own update: the newest version, fetched and checked, to install. */
    data object AppUpdate : Route

    /** Sending One Balance to an address. */
    data object Withdraw : Route

    /** Sending a coin the wallet holds to an address, on the coin's own chain. */
    data class SendCoin(val chain: Chain, val address: String) : Route

    /** The alerts kept on the phone: New and Seen. */
    data object Alerts : Route

    /** Making the password, before the first wallet. */
    data object Setup : Route
    data class Wallets(val adding: Boolean = false) : Route

    val isTab: Boolean get() = this is Discover || this is Smart || this is Tracker || this is Portfolio
}

/** One place in the back stack. Its models live in its scope, which ends when it is left for good. */
class Entry(val route: Route) {
    val id: String = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val models = HashMap<String, Any>()
    fun close() = scope.cancel()
}

@Stable
class Navigator(start: Route) {
    val stack = mutableStateListOf(Entry(start))
    /** Entries dropped since the last frame, so their saved state can be let go. */
    val dropped = mutableListOf<String>()

    val top: Entry get() = stack.last()

    fun push(route: Route) {
        stack.add(Entry(route))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        drop(stack.removeAt(stack.lastIndex))
        return true
    }

    /** Goes to a tab, leaving whatever was open over the current one. */
    fun tab(route: Route) {
        while (stack.size > 1) drop(stack.removeAt(stack.lastIndex))
        if (stack[0].route != route) {
            drop(stack[0])
            stack[0] = Entry(route)
        }
    }

    /** The nearest entry under the top showing this route, for a screen that shares its model. */
    fun find(predicate: (Route) -> Boolean): Entry? = stack.asReversed().firstOrNull { predicate(it.route) }

    private fun drop(e: Entry) {
        e.close()
        dropped.add(e.id)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
val LocalEntry = staticCompositionLocalOf<Entry> { error("No entry") }

/** A model kept for as long as this screen is in the back stack. */
@Composable
inline fun <reified T : Any> rememberEntryModel(key: String = T::class.java.name, crossinline make: (CoroutineScope) -> T): T {
    val entry = LocalEntry.current
    @Suppress("UNCHECKED_CAST")
    return remember(entry.id, key) { entry.models.getOrPut(key) { make(entry.scope) } as T }
}

/** A short note at the bottom of the screen. */
@Stable
class Toasts {
    var current by mutableStateOf<ToastMessage?>(null)
        private set
    private var serial = 0

    /** Sheets open now. A sheet covers the screen's own note, so it shows the note itself. */
    var sheets by mutableIntStateOf(0)

    /** The height of a bar at the bottom of the page, system bar included: notes sit above it. */
    var lift by mutableStateOf(0.dp)

    /** `bad`: something did not happen. `action` and `onAction`: one link in the note, such as View. */
    fun show(title: String, detail: String = "", bad: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
        serial += 1
        val id = serial
        current = ToastMessage(id, title, detail, bad, action, onAction?.let { act -> { clear(id); act() } })
    }

    /** How long a note stays: one with a link or a reason to read stays longer. */
    fun lifeOf(t: ToastMessage): Long = when {
        t.action != null -> 6_000
        t.bad -> 5_000
        else -> 2_800
    }

    fun clear(id: Int) {
        if (current?.id == id) current = null
    }
}

data class ToastMessage(
    val id: Int,
    val title: String,
    val detail: String,
    val bad: Boolean = false,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
)

val LocalToasts = staticCompositionLocalOf<Toasts> { error("No toasts") }
