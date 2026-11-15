package trade.nesty.app.alerts

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import trade.nesty.app.data.AlertSwitch
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ChainSignals
import trade.nesty.app.data.Followed
import trade.nesty.app.data.Holding
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.PriceRow
import trade.nesty.app.data.AlertPrefs
import trade.nesty.app.data.SignalFigures
import trade.nesty.app.data.SignalRecord
import trade.nesty.app.data.SignalRow
import trade.nesty.app.data.TradeNote
import trade.nesty.app.data.WalletReport
import trade.nesty.app.data.WalletTrade
import trade.nesty.app.exit.ExitEvent
import trade.nesty.app.exit.ExitKind
import trade.nesty.app.exit.ExitMath
import trade.nesty.app.exit.ExitPlan
import trade.nesty.app.move.MoveNote
import trade.nesty.app.ui.Fmt
import kotlin.math.abs
import kotlin.math.roundToInt

/** The newest trade or signal already looked at, per wallet and chain: nothing is said twice. */
interface AlertMarks {
    fun since(key: String): Long?
    fun set(key: String, at: Long)
}

class PrefsAlertMarks(context: Context) : AlertMarks {
    private val prefs = context.getSharedPreferences("alertmarks", Context.MODE_PRIVATE)
    override fun since(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
    override fun set(key: String, at: Long) {
        prefs.edit().putLong(key, at).apply()
    }
}

/** What the alerts say, kept apart from where they come from so tests can read them. */
object AlertTexts {
    /** "all", "half" or "25%" of a holding. */
    private fun pctOf(bps: Int) = when {
        bps >= 10_000 -> "all"
        bps == 5_000 -> "half"
        else -> "${bps / 100}%"
    }

    fun plan(e: ExitEvent, mcap: Double?, now: Long): Alert {
        val p = e.plan
        val sym = p.label
        val link = AlertLink.position(p.feed, p.token)
        return when (e) {
            is ExitEvent.Sold -> {
                val rest = e.rule.sellBps >= 10_000 && p.rules.any { it.id != e.rule.id && it.firedAt != null }
                val title = when {
                    rest -> "Exit plan sold the rest of $sym"
                    e.rule.sellBps >= 10_000 -> "Exit plan sold all your $sym"
                    else -> "Exit plan sold ${e.rule.sellBps / 100}% of your $sym"
                }
                val at = "At ${ExitMath.times(e.atBps)}" + (mcap?.let { ", MC ${Fmt.usd(it)}" } ?: "") + "."
                val got = e.usd?.let { usd -> " You got about ${Fmt.usdFull(usd)}" + (e.gainUsd?.let { ", ${Fmt.usdSigned(it)}" } ?: "") + "." } ?: ""
                Alert("plan:${p.key}:${e.rule.id}:$now", AlertKind.PLAN, AlertTone.PLAN, title, at + got, now, link)
            }
            is ExitEvent.Failed -> Alert(
                "plan:${p.key}:${e.rule.id}:fail:$now",
                AlertKind.PLAN,
                AlertTone.BAD,
                "Exit plan could not sell your $sym",
                e.error.trimEnd('.') + ". " + if (e.givenUp) "It stopped trying: sell it yourself if you still want to." else "It tries again.",
                now,
                link,
            )
            is ExitEvent.Waiting -> Alert(
                "plan:${p.key}:${e.rule.id}:wait:$now",
                AlertKind.PLAN,
                AlertTone.WARN,
                when (e.rule.kind) {
                    ExitKind.TP -> "$sym reached your take profit"
                    ExitKind.SL -> "$sym hit your stop loss"
                    ExitKind.TRAIL -> "$sym fell to your trailing stop"
                },
                "Unlock Nesty to sell ${pctOf(e.rule.sellBps)}. The exit plan waits until then.",
                now,
                link,
            )
        }
    }

    /** Android ended Nesty with exit plans on: they wait for the password or a finger. */
    fun paused(now: Long): Alert = Alert(
        "paused:$now",
        AlertKind.PLAN,
        AlertTone.WARN,
        "Exit plans paused",
        "Android closed Nesty, which locks your wallets. Unlock Nesty so your exit plans can sell.",
        now,
        AlertLink(AlertLink.PORTFOLIO),
    )

    fun trade(n: TradeNote, now: Long): Alert = Alert(
        "trade:${n.id}",
        AlertKind.YOURS,
        if (n.ok) AlertTone.OK else AlertTone.BAD,
        n.title,
        n.detail,
        now,
        AlertLink.position(n.chain.feed, n.token),
    )

    fun move(n: MoveNote, now: Long): Alert = Alert(
        "move:${n.moveId}:${n.title.hashCode()}",
        AlertKind.YOURS,
        if (n.ok) AlertTone.INFO else AlertTone.BAD,
        n.title,
        n.detail,
        now,
        AlertLink(AlertLink.PORTFOLIO),
    )

    /**
     * A followed wallet's trade. `held`: the user's own position in the coin; a sell of it is a
     * Coins you hold alert. `plan`: its exit plan, said when it has a take profit waiting.
     * `left`: what the wallet still holds of the coin, for how much of it a sell was.
     */
    fun tracked(
        chain: Chain,
        f: Followed,
        t: WalletTrade,
        symbol: String,
        left: Double?,
        price: PriceRow?,
        held: Holding?,
        plan: ExitPlan?,
    ): Alert {
        val name = f.name.takeIf { it.isNotBlank() } ?: Fmt.address(f.address)
        val usd = t.dollars
        val share = left?.let { l -> if (t.tokens > 0) t.tokens / (t.tokens + l.coerceAtLeast(0.0)) else null }
        val title = when {
            t.isBuy -> "$name bought $symbol" + (usd?.let { " for ${Fmt.usd(it)}" } ?: "")
            share != null && share >= 0.99 -> "$name sold all their $symbol"
            share != null && share in 0.45..0.55 -> "$name sold half their $symbol"
            else -> "$name sold $symbol" + (usd?.let { " for ${Fmt.usd(it)}" } ?: "")
        }
        val supply = price?.let { p -> if (p.priceUsd != null && p.mcapUsd != null && p.priceUsd > 0) p.mcapUsd / p.priceUsd else null }
        val mcThen = if (usd != null && t.tokens > 0 && supply != null) usd / t.tokens * supply else null
        val body = if (held != null) {
            val value = held.value
            val pnl = held.pnl
            val paid = held.position.paid
            val move = if (pnl != null && paid > 0) {
                val pct = (pnl / paid * 100).roundToInt()
                if (pct >= 0) "up ${abs(pct)}%" else "down ${abs(pct)}%"
            } else {
                null
            }
            val hold = "You hold " + (value?.let { Fmt.usdFull(it) + " of it" } ?: "some of it") + (move?.let { ", $it" } ?: "") + "."
            val tp = plan?.rules?.firstOrNull { it.kind == ExitKind.TP && !it.done }
            hold + (tp?.let { " Your exit plan sells ${pctOf(it.sellBps)} at ${ExitMath.times(it.triggerBps)}." } ?: "")
        } else {
            listOfNotNull(mcThen?.let { "At MC ${Fmt.usd(it)}." }, price?.mcapUsd?.let { "It is ${Fmt.usd(it)} now." }).joinToString(" ")
        }
        val sellsYours = held != null && !t.isBuy
        val tone = when {
            sellsYours -> if ((held.pnl ?: 0.0) < 0) AlertTone.BAD else AlertTone.WARN
            else -> AlertTone.WALLETS
        }
        return Alert(
            id = "trk:${chain.feed}:${f.address.lowercase()}:${t.tx.lowercase()}",
            kind = if (sellsYours) AlertKind.HELD else AlertKind.TRACKER,
            tone = tone,
            title = title,
            body = body,
            at = t.at,
            link = if (held != null) AlertLink.position(chain.feed, t.token) else AlertLink.token(chain.feed, t.token),
            who = f.address.lowercase(),
        )
    }

    fun signal(chain: Chain, r: SignalRow, figures: SignalFigures?, now: Long): Alert {
        val sym = r.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(r.token)
        val minutes = ((r.at - r.first).coerceAtLeast(0) / 60_000.0).roundToInt()
        val within = if (minutes < 1) "Within a minute" else "Within $minutes min"
        val mc = r.usdThen?.let { u -> r.supply?.let { u * it } }
        val record = figures?.takeIf { it.n >= MIN_RECORD }?.let { f ->
            " Of signals like this, ${(f.x2 * 100.0 / f.n).roundToInt()}% reached 2× and ${(f.half * 100.0 / f.n).roundToInt()}% fell by half."
        } ?: ""
        return Alert(
            id = "sig:${chain.feed}:${r.token.lowercase()}:${r.size}",
            kind = AlertKind.SIGNAL,
            tone = AlertTone.SIGNAL,
            title = "Signal: ${r.size} proven wallets bought $sym",
            body = within + (mc?.let { ", at MC ${Fmt.usd(it)}" } ?: "") + "." + record,
            at = r.at.takeIf { it > 0 } ?: now,
            link = AlertLink.token(chain.feed, r.token),
        )
    }

    /** Signals followed fewer times than this say nothing of how signals like them did. */
    private const val MIN_RECORD = 10
}

/**
 * Tracker and signal alerts: the followed wallets' trades and the proven wallets' signals, read
 * from Nesty's server as the Tracker and Smart money screens read them, while the app is in front,
 * or with it closed when the menu says so. The server sees which wallets are asked about, as for
 * the Tracker; the list of who is followed stays on the phone. Each trade and signal is said once.
 */
class AlertWatch(
    private val api: NestyApi,
    private val settings: AlertPrefs,
    private val alerts: Alerts,
    private val marks: AlertMarks,
    /** The user's position in a coin, when they hold some. */
    private val heldOf: (Chain, String) -> Holding?,
    private val planOf: (Chain, String) -> ExitPlan?,
    /** Whether to read now: the app in front, or alerts wanted with it closed. */
    private val wanted: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var record: SignalRecord? = null
    private var recordAt = 0L

    suspend fun run() {
        var turn = 0
        while (true) {
            if (wanted()) {
                if (settings.alert(AlertSwitch.SIGNALS)) safely { signals() }
                if (turn % 2 == 0 && (settings.alert(AlertSwitch.TRACKER) || settings.alert(AlertSwitch.HELD))) safely { tracker() }
                turn += 1
            }
            delay(TURN_MS)
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Read again next turn.
        }
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    internal suspend fun tracker() = coroutineScope {
        val followed = settings.followedWallets().filter { EVM.matches(it.address) && !settings.isMuted(it.address) }
        if (followed.isEmpty()) return@coroutineScope
        val gate = Semaphore(4)
        val reads = followed.flatMap { f -> Chain.booked.map { c -> c to f } }.map { (c, f) ->
            async { gate.withPermit { orNull { api.wallet(c, f.address.lowercase(), "1d").report }?.let { Triple(c, f, it) } } }
        }.awaitAll().filterNotNull()
        val fresh = ArrayList<Triple<Chain, Followed, Pair<WalletReport, WalletTrade>>>()
        for ((c, f, r) in reads) {
            val key = "trk:${c.feed}:${f.address.lowercase()}"
            val since = mark(key, r.trades.maxOfOrNull { it.at }) ?: continue
            r.trades.filter { it.at > since && now() - it.at < FRESH_MS }.sortedBy { it.at }.takeLast(MAX_EACH).forEach { fresh += Triple(c, f, r to it) }
        }
        if (fresh.isEmpty()) return@coroutineScope
        val coins = fresh.map { (c, _, rt) -> c to rt.second.token }.distinct().take(30)
        val prices = orNull { api.prices(coins).prices }.orEmpty().associateBy { "${it.feed}:${it.address.lowercase()}" }
        for ((c, f, rt) in fresh) {
            val (r, t) = rt
            val held = heldOf(c, t.token)?.takeIf { it.open }
            val sellsYours = held != null && !t.isBuy
            val want = if (sellsYours) settings.alert(AlertSwitch.HELD) else settings.alert(AlertSwitch.TRACKER)
            if (!want) continue
            val symbol = (r.book[t.token] ?: r.book[t.token.lowercase()])?.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(t.token)
            // What it holds now: after this trade, unless it traded the coin again since.
            val later = r.trades.any { it.token.equals(t.token, ignoreCase = true) && it.at > t.at }
            val left = if (t.isBuy || later) null else r.holdings.firstOrNull { it.token.equals(t.token, ignoreCase = true) }?.held ?: 0.0
            alerts.push(
                AlertTexts.tracked(c, f, t, symbol, left, prices["${c.feed}:${t.token.lowercase()}"], held, planOf(c, t.token)),
                toast = sellsYours,
            )
        }
    }

    internal suspend fun signals() = coroutineScope {
        val lists = Chain.booked.map { c -> async { orNull { api.signals(c).signals }?.let { c to it } } }.awaitAll().filterNotNull()
        if (now() - recordAt > RECORD_MS) {
            orNull { api.signalRecord().record }?.let {
                record = it
                recordAt = now()
            }
        }
        for ((c, cs) in lists) say(c, cs)
    }

    private fun say(c: Chain, cs: ChainSignals) {
        val rows = cs.list.filter { it.size == SIZE }
        val since = mark("sig:${c.feed}", rows.maxOfOrNull { it.at }) ?: return
        val figures = record?.chains?.firstOrNull { it.chain.toString() == c.feed }?.sizes?.get(SIZE.toString())
        rows.filter { it.at > since && now() - it.at < FRESH_MS }.sortedBy { it.at }.takeLast(MAX_EACH).forEach { r ->
            alerts.push(AlertTexts.signal(c, r, figures, now()), toast = false)
        }
    }

    /**
     * Moves a mark to the newest trade or signal read, and returns where it stood: what is newer
     * than that is news. The first read only marks where things stand (a little back when there
     * is nothing yet, for a trade the server books late) and returns null.
     */
    private fun mark(key: String, newest: Long?): Long? {
        val since = marks.since(key)
        if (since == null) {
            marks.set(key, newest ?: (now() - LATE_MS))
            return null
        }
        if (newest != null && newest > since) marks.set(key, newest)
        return since
    }

    private companion object {
        const val TURN_MS = 30_000L

        /** How late a trade may reach the server's book after it happened. */
        const val LATE_MS = 2 * 60_000L
        const val RECORD_MS = 10 * 60_000L

        /** A trade or signal older than this when first seen is not news any more. */
        const val FRESH_MS = 30 * 60_000L

        /** At most this many alerts from one wallet or chain per read. */
        const val MAX_EACH = 5

        /** Signals of three proven wallets, as the Smart money screen shows first. */
        const val SIZE = 3
        val EVM = Regex("^0x[0-9a-fA-F]{40}$")
    }
}
