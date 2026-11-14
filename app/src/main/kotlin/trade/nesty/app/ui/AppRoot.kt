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
import trade.nesty.app.data.VaultState
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.vault.SetupScreen
import trade.nesty.app.ui.vault.UnlockScreen
import trade.nesty.app.ui.vault.WalletsScreen
import trade.nesty.app.ui.token.TokenScreen
import trade.nesty.app.ui.tracker.TrackerModel
import trade.nesty.app.ui.tracker.TrackerScreen
import trade.nesty.app.ui.trade.PositionScreen
import trade.nesty.app.ui.wallet.WalletScreen
import trade.nesty.app.ui.withdraw.WithdrawScreen
import trade.nesty.app.move.MoveKind
import trade.nesty.app.move.MoveState

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

    // While the lock is up Back leaves the app, as on any first screen.
    BackHandler(enabled = AppGraph.session.state != VaultState.LOCKED && (nav.stack.size > 1 || top.route != Route.Discover)) {
        if (!nav.pop()) nav.tab(Route.Discover)
    }

    SideEffect {
        for (id in nav.dropped) saved.removeState(id)
        nav.dropped.clear()
    }

    // The wallet that trades has its balance read while the app is open and on screen.
    val session = AppGraph.session
    WhileShown(session.state) { if (session.state == VaultState.OPEN) AppGraph.balances.live() }

    // Each trade that ends says so at the bottom of the screen, with a link to its position.
    LaunchedEffect(Unit) {
        val trading = AppGraph.trading
        trading.notes.collect { n ->
            if (n.id in trading.quiet) return@collect
            val here = (nav.top.route as? Route.Position)?.let { it.chain == n.chain && it.address.equals(n.token, ignoreCase = true) } == true
            if (n.ok && !here) {
                toasts.show(n.title, n.detail, action = "View") { nav.push(Route.Position(n.chain, n.token)) }
            } else {
                toasts.show(n.title, n.detail, bad = !n.ok)
            }
        }
    }

    // Each move that ended, or that waits on the user, says so too, unless a screen shows it.
    LaunchedEffect(Unit) {
        val mover = AppGraph.mover
        mover.notes.collect { n ->
            if (n.moveId in mover.quiet) return@collect
            val m = mover.get(n.moveId)
            if (m?.kind == MoveKind.BUY && m.state == MoveState.ASK) {
                toasts.show(n.title, n.detail, action = "View") { nav.tab(Route.Portfolio) }
            } else {
                toasts.show(n.title, n.detail, bad = !n.ok)
            }
        }
    }

    CompositionLocalProvider(LocalNavigator provides nav, LocalToasts provides toasts, LocalRoot provides root) {
        Box(Modifier.fillMaxSize().background(Ink.ground)) {
            // Closed wallets close the whole app: the lock is all there is until the password opens it.
            if (session.state == VaultState.LOCKED) {
                UnlockScreen()
                return@Box
            }
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The trade bars of the token and position pages keep themselves above the system bar.
                        .then(if (tab == null && top.route !is Route.Token && top.route !is Route.Position) Modifier.navigationBarsPadding() else Modifier),
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
                    delay(toasts.lifeOf(t))
                    toasts.clear(t.id)
                }
                // Above the tabs, above a page's trade bar (its height includes the system bar), or low.
                val lift = toasts.lift
                val place = when {
                    tab != null -> Modifier.navigationBarsPadding().padding(bottom = 84.dp)
                    lift > 0.dp -> Modifier.padding(bottom = lift + 12.dp)
                    else -> Modifier.navigationBarsPadding().padding(bottom = 24.dp)
                }
                // An open sheet shows the note itself (NestySheet): here it would sit under it.
                if (toasts.sheets == 0) {
                    ToastCard(
                        t,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(place)
                            .padding(horizontal = 16.dp),
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
        is Route.Token -> TokenScreen(route.chain, route.address, route.buy)
        is Route.TradesHolders -> TradesHoldersScreen(route.chain, route.address)
        is Route.Position -> PositionScreen(route.chain, route.address)
        is Route.Wallet -> WalletScreen(route.chain, route.address)
        Route.Search -> SearchScreen()
        Route.Menu -> MenuScreen()
        Route.Stats -> StatsScreen()
        Route.Updates -> UpdatesScreen()
        Route.Withdraw -> WithdrawScreen()
        Route.Setup -> SetupScreen()
        is Route.Wallets -> WalletsScreen(route.adding)
    }
}
