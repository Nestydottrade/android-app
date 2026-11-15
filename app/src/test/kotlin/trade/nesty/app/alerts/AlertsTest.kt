package trade.nesty.app.alerts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.AlertPrefs
import trade.nesty.app.data.AlertSwitch
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Followed
import trade.nesty.app.data.Holding
import trade.nesty.app.data.Position
import trade.nesty.app.data.WalletTrade
import trade.nesty.app.exit.ExitEvent
import trade.nesty.app.exit.ExitKind
import trade.nesty.app.exit.ExitPlan
import trade.nesty.app.exit.ExitRule
import trade.nesty.app.fakeApi
import java.math.BigInteger

private class MemoryAlertDisk : AlertDisk {
    var json: String? = null
    override fun read(): String? = json
    override fun write(json: String) {
        this.json = json
    }
}

private class MemoryMarks : AlertMarks {
    val map = HashMap<String, Long>()
    override fun since(key: String): Long? = map[key]
    override fun set(key: String, at: Long) {
        map[key] = at
    }
}

private class FakePrefs(
    var followed: List<Followed> = emptyList(),
    val off: MutableSet<AlertSwitch> = mutableSetOf(AlertSwitch.CLOSED),
    val muted: MutableSet<String> = mutableSetOf(),
) : AlertPrefs {
    override fun alert(which: AlertSwitch): Boolean = which !in off
    override fun isMuted(address: String?): Boolean = address != null && address.lowercase() in muted
    override fun followedWallets(): List<Followed> = followed
}

// Made-up addresses for tests only.
private const val KIKI = "0x1111111111111111111111111111111111111111"
private const val COIN = "0x2222222222222222222222222222222222222222"
private const val NOW = 1_800_000_000_000L

private fun alert(id: String, kind: AlertKind = AlertKind.TRACKER, at: Long = NOW, who: String? = null) =
    Alert(id, kind, AlertTone.WALLETS, "t$id", "b$id", at, who = who)

private fun holding(paid: Double, priceUsd: Double, held: Long = 1_000) = Holding(
    Position(Chain.BSC, COIN, "PEPE", 0, BigInteger.valueOf(held), BigInteger.ZERO, paid, 0.0, 10_000.0, NOW - 3_600_000, NOW - 3_600_000),
    BigInteger.valueOf(held),
    priceUsd,
)

class AlertStoreTest {
    @Test
    fun keepsEachAlertOnceNewestFirstAndAtMost150() {
        val disk = MemoryAlertDisk()
        val store = AlertStore(disk)
        assertTrue(store.add(alert("a", at = NOW - 10)))
        assertTrue(store.add(alert("b", at = NOW)))
        assertFalse(store.add(alert("a")))
        assertEquals(listOf("b", "a"), store.list.map { it.id })
        for (i in 0 until 200) store.add(alert("n$i", at = NOW + i))
        assertEquals(AlertStore.MAX, store.list.size)
        assertEquals("n199", store.list.first().id)
        // Kept on the phone and read back.
        assertEquals(store.list, AlertStore(disk).list)
    }

    @Test
    fun markAllReadCanBeUndone() {
        val store = AlertStore(MemoryAlertDisk())
        store.add(alert("a"))
        store.add(alert("b"))
        store.markRead("a")
        assertEquals(1, store.unread)
        val changed = store.markAllRead()
        assertEquals(setOf("b"), changed)
        assertEquals(0, store.unread)
        store.markUnread(changed)
        assertEquals(1, store.unread)
        assertFalse(store.list.first { it.id == "b" }.read)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsPushTest {
    @Test
    fun inFrontItShowsOnScreenAwayItPostsANotification() = runTest {
        val posted = mutableListOf<Alert>()
        val alerts = Alerts(AlertStore(MemoryAlertDisk()), FakePrefs()) { posted += it }
        val shown = mutableListOf<Alert>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { alerts.shown.collect { shown += it } }

        alerts.visible = true
        assertTrue(alerts.push(alert("1")))
        // The user's own trade: the screen said it already.
        assertTrue(alerts.push(alert("2", AlertKind.YOURS), toast = false))
        alerts.visible = false
        assertTrue(alerts.push(alert("3")))
        // Said once.
        assertFalse(alerts.push(alert("3")))
        job.cancel()

        assertEquals(listOf("1"), shown.map { it.id })
        assertEquals(listOf("3"), posted.map { it.id })
    }

    @Test
    fun switchesAndMutedWalletsKeepAlertsOut() {
        val prefs = FakePrefs(muted = mutableSetOf(KIKI))
        val posted = mutableListOf<Alert>()
        val store = AlertStore(MemoryAlertDisk())
        val alerts = Alerts(store, prefs) { posted += it }
        assertFalse(alerts.push(alert("muted", who = KIKI.uppercase().replace("0X", "0x"))))
        prefs.off += AlertSwitch.SIGNALS
        assertFalse(alerts.push(alert("sig", AlertKind.SIGNAL)))
        prefs.off += AlertSwitch.PLANS
        assertFalse(alerts.push(alert("plan", AlertKind.PLAN)))
        // Your own trades have no switch.
        assertTrue(alerts.push(alert("mine", AlertKind.YOURS)))
        assertEquals(listOf("mine"), store.list.map { it.id })
        assertEquals(listOf("mine"), posted.map { it.id })
    }

    @Test
    fun filtersGroupTheAlerts() {
        val held = alert("h", AlertKind.HELD, who = KIKI)
        assertTrue(Alerts.Filter.YOURS.has(held))
        assertTrue(Alerts.Filter.TRACKER.has(held))
        assertFalse(Alerts.Filter.SIGNALS.has(held))
        assertTrue(Alerts.Filter.YOURS.has(alert("p", AlertKind.PLAN)))
        assertFalse(Alerts.Filter.TRACKER.has(alert("p", AlertKind.PLAN)))
        assertTrue(Alerts.Filter.SIGNALS.has(alert("s", AlertKind.SIGNAL)))
    }

    @Test
    fun aNotificationLinkReadsBack() {
        val link = AlertLink.position("56", COIN)
        assertEquals(link, Notifier.linkOf(Notifier.linkText(link)))
        assertEquals(AlertLink(AlertLink.PORTFOLIO), Notifier.linkOf(Notifier.linkText(AlertLink(AlertLink.PORTFOLIO))))
        assertEquals(null, Notifier.linkOf(null))
        assertEquals(null, Notifier.linkOf(""))
    }
}

class AlertTextsTest {
    private val plan = ExitPlan(
        walletId = "w1",
        wallet = KIKI,
        feed = "56",
        token = COIN,
        symbol = "WREN",
        rules = listOf(
            ExitRule("tp", ExitKind.TP, 20_000, 5_000, done = true, firedAt = NOW - 60_000, firedBps = 20_100),
            ExitRule("sl", ExitKind.SL, 5_000, 10_000),
        ),
        createdAt = NOW - 86_400_000,
    )

    @Test
    fun anExitPlanSaleSaysWhatItGot() {
        val first = AlertTexts.plan(ExitEvent.Sold(plan.copy(rules = plan.rules.map { it.copy(done = false, firedAt = null) }), plan.rules[0], 20_100, 101.0, 61.0), 20_200.0, NOW)
        assertEquals("Exit plan sold 50% of your WREN", first.title)
        assertTrue(first.body, first.body.startsWith("At 2.01×, MC $20"))
        assertTrue(first.body, first.body.contains("You got about $101.00, +$61."))
        assertEquals(AlertLink.position("56", COIN), first.link)
        assertEquals(AlertKind.PLAN, first.kind)

        // A stop after a take profit sold: the rest.
        val rest = AlertTexts.plan(ExitEvent.Sold(plan, plan.rules[1], 4_900, 20.0, -5.0), null, NOW)
        assertEquals("Exit plan sold the rest of WREN", rest.title)
        assertTrue(rest.body, rest.body.startsWith("At 0.49×."))
    }

    @Test
    fun aFailedOrWaitingStepSaysWhatToDo() {
        val failed = AlertTexts.plan(ExitEvent.Failed(plan, plan.rules[1], "No route to sell right now.", givenUp = true), null, NOW)
        assertEquals(AlertTone.BAD, failed.tone)
        assertTrue(failed.body, failed.body.startsWith("No route to sell right now. It stopped trying"))
        val again = AlertTexts.plan(ExitEvent.Failed(plan, plan.rules[1], "Gas too low", givenUp = false), null, NOW)
        assertTrue(again.body, again.body.endsWith("It tries again."))
        val waiting = AlertTexts.plan(ExitEvent.Waiting(plan, plan.rules[1], 4_900), null, NOW)
        assertEquals("WREN hit your stop loss", waiting.title)
        assertEquals("Unlock Nesty to sell all. The exit plan waits until then.", waiting.body)
    }

    @Test
    fun aFollowedWalletsTradeSaysWhoWhatAndHowMuch() {
        val kiki = Followed(KIKI, "Kiki")
        val buy = WalletTrade(at = NOW, tx = "0xAB", token = COIN, isBuy = true, tokens = 1_000.0, quote = 0.5, usd = 600.0)
        val price = trade.nesty.app.data.PriceRow("56", COIN, priceUsd = 0.4, mcapUsd = 400_000.0)
        val a = AlertTexts.tracked(Chain.BSC, kiki, buy, "PEPE", null, price, null, null)
        assertEquals("Kiki bought PEPE for $300", a.title)
        // $300 for 1,000 tokens = $0.30 each; a supply of 1M makes it $300K then.
        assertEquals("At MC $300K. It is $400K now.", a.body)
        assertEquals(AlertKind.TRACKER, a.kind)
        assertEquals("trk:56:$KIKI:0xab", a.id)
        assertEquals(KIKI, a.who)
        assertEquals(AlertLink.token("56", COIN), a.link)

        val sell = buy.copy(tx = "0xCD", isBuy = false)
        assertEquals("Kiki sold all their PEPE", AlertTexts.tracked(Chain.BSC, kiki, sell, "PEPE", 0.0, price, null, null).title)
        assertEquals("Kiki sold half their PEPE", AlertTexts.tracked(Chain.BSC, kiki, sell, "PEPE", 1_000.0, price, null, null).title)
        assertEquals("Kiki sold PEPE for $300", AlertTexts.tracked(Chain.BSC, kiki, sell, "PEPE", 9_000.0, price, null, null).title)

        // A coin the user holds: down, with its exit plan's next take profit.
        val mine = holding(paid = 200.0, priceUsd = 0.1)
        val tpPlan = plan.copy(rules = listOf(ExitRule("tp", ExitKind.TP, 50_000, 5_000)))
        val held = AlertTexts.tracked(Chain.BSC, Followed(KIKI), sell, "PEPE", 0.0, price, mine, tpPlan)
        assertEquals(AlertKind.HELD, held.kind)
        assertEquals(AlertTone.BAD, held.tone)
        assertTrue(held.title, held.title.endsWith("sold all their PEPE"))
        assertEquals("You hold $100.00 of it, down 50%. Your exit plan sells half at 5×.", held.body)
        assertEquals(AlertLink.position("56", COIN), held.link)
        // Buying a coin the user holds is a Tracker alert.
        assertEquals(AlertKind.TRACKER, AlertTexts.tracked(Chain.BSC, kiki, buy, "PEPE", null, price, mine, null).kind)
    }

    @Test
    fun aSignalSaysHowFastAndHowSignalsLikeItDid() {
        val row = trade.nesty.app.data.SignalRow(token = COIN, symbol = "KILN", size = 3, at = NOW, first = NOW - 6 * 60_000, usdThen = 0.0112, supply = 1_000_000.0)
        val figures = trade.nesty.app.data.SignalFigures(n = 50, x2 = 9, half = 11)
        val a = AlertTexts.signal(Chain.BSC, row, figures, NOW)
        assertEquals("Signal: 3 proven wallets bought KILN", a.title)
        assertEquals("Within 6 min, at MC $11.2K. Of signals like this, 18% reached 2× and 22% fell by half.", a.body)
        // Too few followed signals to say how they did.
        assertEquals("Within 6 min, at MC $11.2K.", AlertTexts.signal(Chain.BSC, row, figures.copy(n = 3), NOW).body)
    }
}

class AlertWatchTest {
    private var now = NOW
    private var trades = """[{"at":${NOW - 60_000},"tx":"0x01","token":"$COIN","isBuy":true,"tokens":1000,"quote":0.5,"usd":600}]"""
    private var signals = """[{"token":"$COIN","symbol":"KILN","size":3,"at":${NOW - 60_000},"first":${NOW - 120_000}}]"""
    private val asked = mutableListOf<String>()
    private val api = fakeApi(asked) { url ->
        when {
            url.contains("/wallet/56/") -> 200 to """{"feed":"56","wallet":"$KIKI","report":{"chain":56,"wallet":"$KIKI","trades":$trades,"book":{"$COIN":{"symbol":"PEPE"}},"holdings":[{"token":"$COIN","held":0}]}}"""
            url.contains("/wallet/") -> 200 to """{"feed":"x","wallet":"$KIKI","report":null}"""
            url.contains("/prices") -> 200 to """{"prices":[{"feed":"56","address":"$COIN","priceUsd":0.4,"mcapUsd":400000}]}"""
            url.contains("/signals/record") -> 200 to """{"record":{"chains":[{"chain":56,"sizes":{"3":{"n":20,"x2":4,"half":5}}}]}}"""
            url.contains("/signals/56") -> 200 to """{"feed":"56","signals":{"chain":56,"list":$signals}}"""
            url.contains("/signals/") -> 200 to """{"signals":{"list":[]}}"""
            else -> 404 to "{}"
        }
    }
    private val prefs = FakePrefs(followed = listOf(Followed(KIKI, "Kiki")))
    private val posted = mutableListOf<Alert>()
    private val store = AlertStore(MemoryAlertDisk())
    private val alerts = Alerts(store, prefs) { posted += it }
    private var held: Holding? = null
    private val watch = AlertWatch(api, prefs, alerts, MemoryMarks(), heldOf = { _, _ -> held }, planOf = { _, _ -> null }, wanted = { true }, now = { now })

    @Test
    fun theFirstReadOnlyMarksThenNewTradesAlertOnce() = runTest {
        watch.tracker()
        assertTrue(store.list.isEmpty())
        // Only the followed wallet, on the chains with a book; no list of wallets goes anywhere.
        assertEquals(Chain.booked.size, asked.count { it.contains("/wallet/") })
        assertTrue(asked.all { !it.contains("/wallet/") || it.contains(KIKI) })

        trades = """[{"at":${NOW - 60_000},"tx":"0x01","token":"$COIN","isBuy":true,"tokens":1000,"quote":0.5,"usd":600},{"at":${NOW - 5_000},"tx":"0x02","token":"$COIN","isBuy":false,"tokens":1000,"quote":0.6,"usd":600}]"""
        watch.tracker()
        assertEquals(listOf("Kiki sold all their PEPE"), store.list.map { it.title })
        assertEquals(AlertKind.TRACKER, store.list.single().kind)
        assertEquals(1, posted.size)

        // Read again: nothing new.
        watch.tracker()
        assertEquals(1, store.list.size)
    }

    @Test
    fun aSellOfACoinYouHoldIsACoinsYouHoldAlert() = runTest {
        watch.tracker()
        held = holding(paid = 50.0, priceUsd = 0.1)
        trades = """[{"at":${NOW - 5_000},"tx":"0x03","token":"$COIN","isBuy":false,"tokens":500,"quote":0.3,"usd":600}]"""
        watch.tracker()
        val a = store.list.single()
        assertEquals(AlertKind.HELD, a.kind)
        assertTrue(a.body, a.body.startsWith("You hold $100.00 of it, up 100%"))

        // Its switch off: nothing.
        prefs.off += AlertSwitch.HELD
        trades = """[{"at":${NOW - 1_000},"tx":"0x04","token":"$COIN","isBuy":false,"tokens":10,"quote":0.01,"usd":600}]"""
        watch.tracker()
        assertEquals(1, store.list.size)
    }

    @Test
    fun aWalletWithNoTradesYetStillAlertsOnItsFirstTrade() = runTest {
        trades = "[]"
        watch.tracker()
        trades = """[{"at":${NOW - 5_000},"tx":"0x05","token":"$COIN","isBuy":true,"tokens":1000,"quote":0.5,"usd":600}]"""
        watch.tracker()
        assertEquals(listOf("Kiki bought PEPE for $300"), store.list.map { it.title })
    }

    @Test
    fun oldTradesAndMutedWalletsSayNothing() = runTest {
        trades = "[]"
        watch.tracker()
        // Newer than the mark, but seen only 40 minutes after it happened: not news any more.
        now = NOW + 40 * 60_000
        trades = """[{"at":${NOW - 60_000},"tx":"0x06","token":"$COIN","isBuy":true,"tokens":1,"quote":1,"usd":1}]"""
        watch.tracker()
        assertTrue(store.list.isEmpty())

        prefs.muted += KIKI
        asked.clear()
        watch.tracker()
        assertTrue(asked.none { it.contains("/wallet/") })
    }

    @Test
    fun signalsOfThreeWalletsAlertOnceAfterTheFirstRead() = runTest {
        watch.signals()
        assertTrue(store.list.isEmpty())
        signals = """[{"token":"$COIN","symbol":"KILN","size":3,"at":${NOW - 60_000},"first":${NOW - 120_000}},
            {"token":"0x3333333333333333333333333333333333333333","symbol":"TWO","size":2,"at":${NOW - 1_000},"first":${NOW - 2_000}},
            {"token":"0x4444444444444444444444444444444444444444","symbol":"NEW","size":3,"at":${NOW - 1_000},"first":${NOW - 3 * 60_000},"usdThen":0.01,"supply":1000000}]"""
        watch.signals()
        val a = store.list.single()
        assertEquals("Signal: 3 proven wallets bought NEW", a.title)
        assertEquals("Within 3 min, at MC $10.0K. Of signals like this, 20% reached 2× and 25% fell by half.", a.body)
        assertEquals(AlertKind.SIGNAL, a.kind)
        watch.signals()
        assertEquals(1, store.list.size)
    }
}
