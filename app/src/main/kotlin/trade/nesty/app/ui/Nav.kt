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

    data class Token(val chain: Chain, val address: String) : Route
    data class TradesHolders(val chain: Chain, val address: String) : Route
    data class Wallet(val chain: Chain, val address: String) : Route
    data object Search : Route
    data object Menu : Route
    data object Stats : Route
    data object Updates : Route

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

    fun show(title: String, detail: String = "") {
        serial += 1
        current = ToastMessage(serial, title, detail)
    }

    fun clear(id: Int) {
        if (current?.id == id) current = null
    }
}

data class ToastMessage(val id: Int, val title: String, val detail: String)

val LocalToasts = staticCompositionLocalOf<Toasts> { error("No toasts") }
