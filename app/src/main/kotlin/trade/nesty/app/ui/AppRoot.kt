package trade.nesty.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import trade.nesty.app.data.AppGraph
import trade.nesty.app.ui.discover.DiscoverModel
import trade.nesty.app.ui.discover.DiscoverScreen
import trade.nesty.app.ui.discover.SearchScreen
import trade.nesty.app.ui.holders.TradesHoldersScreen
import trade.nesty.app.ui.menu.MenuScreen
import trade.nesty.app.ui.menu.PortfolioScreen
import trade.nesty.app.ui.menu.StatsScreen
import trade.nesty.app.ui.menu.UpdatesScreen
import trade.nesty.app.ui.smart.LeadersModel
import trade.nesty.app.ui.smart.SignalsModel
import trade.nesty.app.ui.smart.SmartScreen
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.token.TokenScreen
import trade.nesty.app.ui.tracker.TrackerModel
import trade.nesty.app.ui.tracker.TrackerScreen
import trade.nesty.app.ui.wallet.WalletScreen

/** The models of the four tabs, kept while the app runs so a tab comes back as it was left. */
class RootModels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val discover = DiscoverModel(AppGraph.api, AppGraph.settings)
    val signals = SignalsModel(AppGraph.api)
    val leaders = LeadersModel(AppGraph.api)
    val tracker = TrackerModel(AppGraph.api, AppGraph.settings)
}

val LocalRoot = staticCompositionLocalOf<RootModels> { error("No root") }

/** Runs [block] while the screen is drawn and the app is in front; stops it otherwise. */
@Composable
fun WhileShown(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() }
    }
}

@Composable
fun AppRoot() {
    val nav = remember { Navigator(Route.Discover) }
    val toasts = remember { Toasts() }
    val root = remember { RootModels() }
    val saved = rememberSaveableStateHolder()

    val top = nav.top
    val tab = Tab.of(top.route)

    BackHandler(enabled = nav.stack.size > 1 || top.route != Route.Discover) {
        if (!nav.pop()) nav.tab(Route.Discover)
    }

    SideEffect {
        for (id in nav.dropped) saved.removeState(id)
        nav.dropped.clear()
    }

    CompositionLocalProvider(LocalNavigator provides nav, LocalToasts provides toasts, LocalRoot provides root) {
        Box(Modifier.fillMaxSize().background(Ink.ground)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The token page's Buy bar keeps itself above the system bar.
                        .then(if (tab == null && top.route !is Route.Token) Modifier.navigationBarsPadding() else Modifier),
                ) {
                    saved.SaveableStateProvider(top.id) {
                        CompositionLocalProvider(LocalEntry provides top) {
                            Screen(top.route)
                        }
                    }
                }
                if (tab != null) {
                    Column(Modifier.background(Ink.card).windowInsetsPadding(WindowInsets.navigationBars)) {
                        BottomNav(tab) { nav.tab(it.route) }
                    }
                }
            }

            val t = toasts.current
            if (t != null) {
                LaunchedEffect(t.id) {
                    delay(2_800)
                    toasts.clear(t.id)
                }
                val bottom = when {
                    tab != null -> 84.dp
                    top.route is Route.Token -> 140.dp
                    else -> 24.dp
                }
                // An open sheet shows the note itself (NestySheet): here it would sit under it.
                if (toasts.sheets == 0) {
                    ToastCard(
                        t,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = bottom),
                    )
                }
            }
        }
    }
}

@Composable
private fun Screen(route: Route) {
    when (route) {
        Route.Discover -> DiscoverScreen()
        is Route.Smart -> SmartScreen(route.leaderboard)
        Route.Tracker -> TrackerScreen()
        Route.Portfolio -> PortfolioScreen()
        is Route.Token -> TokenScreen(route.chain, route.address)
        is Route.TradesHolders -> TradesHoldersScreen(route.chain, route.address)
        is Route.Wallet -> WalletScreen(route.chain, route.address)
        Route.Search -> SearchScreen()
        Route.Menu -> MenuScreen()
        Route.Stats -> StatsScreen()
        Route.Updates -> UpdatesScreen()
    }
}
