package trade.nesty.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.alerts.AlertLink
import trade.nesty.app.alerts.AlertStore
import trade.nesty.app.alerts.AlertTexts
import trade.nesty.app.alerts.AlertWatch
import trade.nesty.app.alerts.Alerts
import trade.nesty.app.alerts.Notifier
import trade.nesty.app.alerts.PrefsAlertDisk
import trade.nesty.app.alerts.PrefsAlertMarks
import trade.nesty.app.alerts.WatchService
import trade.nesty.app.exit.EngineExitMarket
import trade.nesty.app.exit.ExitPlan
import trade.nesty.app.exit.ExitStore
import trade.nesty.app.exit.ExitWatcher
import trade.nesty.app.exit.PrefsExitDisk
import trade.nesty.app.move.HttpRelay
import trade.nesty.app.move.MoveStore
import trade.nesty.app.move.Mover
import trade.nesty.app.move.PrefsMoveDisk
import trade.nesty.app.update.AndroidUpdatePhone
import trade.nesty.app.update.ApkFetch
import trade.nesty.app.update.HttpApkSource
import trade.nesty.app.update.Installer
import trade.nesty.app.update.PrefsUpdateDisk
import trade.nesty.app.update.Releases
import trade.nesty.app.update.UpdateHost
import trade.nesty.app.update.Updater
import trade.nesty.app.vault.AndroidKeyWrap
import trade.nesty.app.vault.FileVaultStore
import trade.nesty.app.vault.FingerprintKey
import trade.nesty.app.vault.Vault
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's few long-lived pieces. */
object AppGraph {
    lateinit var api: NestyApi
        private set
    lateinit var settings: Settings
        private set
    lateinit var imageHttp: OkHttpClient
        private set
    lateinit var session: WalletSession
        private set
    lateinit var fingerprint: FingerprintKey
        private set
    lateinit var balances: BalanceModel
        private set
    lateinit var positions: PositionStore
        private set
    lateinit var imports: ImportStore
        private set
    lateinit var trading: Trading
        private set
    lateinit var mover: Mover
        private set
    lateinit var exitStore: ExitStore
        private set
    lateinit var exitMarket: EngineExitMarket
        private set
    lateinit var exits: ExitWatcher
        private set
    lateinit var alertStore: AlertStore
        private set
    lateinit var alerts: Alerts
        private set
    lateinit var updater: Updater
        private set
    /** Coin pictures for the screens that come without them, kept while the app runs. */
    lateinit var images: CoinImages
        private set
    private lateinit var app: Context

    /** What a tapped notification opens, applied once the screens can show it. */
    var pendingLink by mutableStateOf<AlertLink?>(null)

    /** Lives as long as the app's process: the exit plans and alerts are watched here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        val agent = "Nesty-Android/${BuildConfigLite.VERSION}"
        val base = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
            }
            .build()
        // A stream sends a comment every 15 seconds; 45 seconds of silence means it is gone.
        val stream = base.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
        imageHttp = base.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        api = NestyApi(base, stream)
        // four.meme refuses servers: the pictures of its coins are read from its records by the phone.
        images = CoinImages(api, FourMemeRecords(base, PrefsRestDisk(context.applicationContext)))
        app = context.applicationContext
        settings = Settings(context.applicationContext)
        exitStore = ExitStore(PrefsExitDisk(context.applicationContext))
        // The keys: a private file, sealed by the password and by a key the phone never gives out.
        // Fingerprint unlock keeps the vault's key under a second phone key that only a finger opens.
        fingerprint = FingerprintKey(context.applicationContext)
        // While an exit plan is on, locking keeps the vault's key sealed by a third phone key, in
        // memory only, so the plan can still sell; it is gone when Android ends the app.
        val vault = Vault(FileVaultStore(context.applicationContext), AndroidKeyWrap(), watchWrap = AndroidKeyWrap(WATCH_KEY))
        session = WalletSession(vault, settings, fingerprint).also { s -> s.keepWatch = { exitStore.plans.isNotEmpty() } }
        positions = PositionStore(context.applicationContext)
        imports = ImportStore(PrefsImportDisk(context.applicationContext))
        // A wallet's balance names the coins added to Portfolio and those it has traded: EVM chains
        // cannot list what it holds. The added ones come first, so a long trading past never pushes them out.
        balances = BalanceModel(api, session) { wallet ->
            val added = imports.of(wallet.evm) + imports.of(wallet.sol)
            (added.mapNotNull { i -> i.chain?.let { it to i.token } } + positions.coinsOf(wallet.evm) + positions.coinsOf(wallet.sol)).distinct()
        }
        trading = Trading(api, session, positions, base).also {
            it.balances = balances
            it.imports = imports
        }
        // Moves of funds between chains and out of One Balance, kept on the phone; bridge quotes are
        // asked of the bridge from the phone. A move the app was closed in is followed again.
        mover = Mover(session, trading, balances, settings, MoveStore(PrefsMoveDisk(context.applicationContext)), HttpRelay(base), base)
        mover.resume()

        // Exit plans sell from their own wallet through the same engines; alerts are kept on the
        // phone and shown at the bottom of the screen, or as notifications while Nesty is not in front.
        exitMarket = EngineExitMarket(api, trading, session, positions, settings)
        exits = ExitWatcher(exitStore, exitMarket)
        alertStore = AlertStore(PrefsAlertDisk(app))
        alerts = Alerts(alertStore, settings) { a -> Notifier.post(app, a) }
        Notifier.channels(app)
        val watch = AlertWatch(
            api = api,
            settings = settings,
            alerts = alerts,
            marks = PrefsAlertMarks(app),
            heldOf = { c, t -> trading.holding(c, t) },
            planOf = { c, t -> planOf(c, t) },
            wanted = { alerts.visible || settings.alert(AlertSwitch.CLOSED) },
        )
        scope.launch { exits.run(this) }
        scope.launch { watch.run() }
        scope.launch {
            exits.events.collect { e ->
                alerts.push(AlertTexts.plan(e, exitMarket.mcapOf(e.plan), System.currentTimeMillis()))
            }
        }
        // The user's own trades and moves: the screen says them while Nesty is in front, so only
        // the list and, with Nesty away, a notification. An exit plan's sell says itself above.
        scope.launch {
            trading.notes.collect { n -> if (n.id !in trading.byPlan) alerts.push(AlertTexts.trade(n, System.currentTimeMillis()), toast = false) }
        }
        scope.launch {
            mover.notes.collect { n -> alerts.push(AlertTexts.move(n, System.currentTimeMillis()), toast = false) }
        }

        // The app's own updates, from nesty.trade only (a debug build may name a test server on this
        // computer). The APK is fetched and checked here; Android installs it when the user says so.
        val testBase = UpdateHost.testBase(app)
        val releases = if (testBase != null) NestyApi(base, stream, "${testBase}api/v1/".toHttpUrl()) else api
        updater = Updater(
            scope = scope,
            ask = { releases.app() },
            fetch = ApkFetch(HttpApkSource(base.newBuilder().readTimeout(30, TimeUnit.SECONDS).build())),
            phone = AndroidUpdatePhone(app),
            dir = File(app.cacheDir, "update"),
            disk = PrefsUpdateDisk(app),
            install = { file -> Installer.commit(app, file) },
            host = testBase?.toHttpUrl()?.host ?: Releases.HOST,
            onReady = { r -> if (!alerts.visible) Notifier.updateReady(app, r.version) },
        )
        // The first start of a new version says so once its screens are open.
        if (updater.justUpdated != null) pendingLink = AlertLink(AlertLink.UPDATE)
    }

    /** The active wallet's exit plan for a coin. */
    fun planOf(chain: Chain, token: String): ExitPlan? = exitStore.of(session.active?.id, chain.feed, token)

    /** Whether the watch service is needed: an exit plan is on, or alerts are wanted with Nesty closed. */
    fun watchWanted(): Boolean = exitStore.plans.isNotEmpty() || settings.alert(AlertSwitch.CLOSED)

    /** The watch service's line in the shade. */
    fun watchSummary(): Pair<String, String> {
        val n = exitStore.plans.size
        return when {
            n == 0 -> "Watching for alerts" to "Tracked wallets and signals, with Nesty closed"
            !session.canSignForWatch() -> "Exit plans paused" to "Unlock Nesty so they can sell"
            n == 1 -> "Watching 1 exit plan" to "It sells even with Nesty closed"
            else -> "Watching $n exit plans" to "They sell even with Nesty closed"
        }
    }

    /** Each look of the watch service: with no plan left, the sealed key of a locked vault goes. */
    fun watchLooked() {
        if (exitStore.plans.isEmpty()) session.dropWatch()
    }

    /** Android started the watch service again after it ended the app, which locked the wallets. */
    fun watchRestarted() {
        if (exitStore.plans.isEmpty() || session.canSignForWatch()) return
        alerts.push(AlertTexts.paused(System.currentTimeMillis()))
    }

    /** Starts the watch service if something needs it; called while a screen is in front. */
    fun syncWatch() = WatchService.sync(app)

    /** The phone key that seals the vault's key while it is locked with exit plans on. */
    private const val WATCH_KEY = "nesty.watch.v1"
}
