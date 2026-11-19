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
import trade.nesty.app.i18n.tr
import trade.nesty.app.i18n.trKey
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
    /** One of three whole sentences for selling all, half or a percent of a holding: each language words them itself. */
    private fun byShare(bps: Int, all: String, half: String, pct: String, vararg args: Any?): String = when {
        bps >= 10_000 -> tr(all, *args)
        bps == 5_000 -> tr(half, *args)
        else -> tr(pct, bps / 100, *args)
    }

    fun plan(e: ExitEvent, mcap: Double?, now: Long): Alert {
        val p = e.plan
        val sym = p.label
        val link = AlertLink.position(p.feed, p.token)
        return when (e) {
            is ExitEvent.Sold -> {
                val rest = e.rule.sellBps >= 10_000 && p.rules.any { it.id != e.rule.id && it.firedAt != null }
                val title = when {
                    rest -> tr("Exit plan sold the rest of {1}", sym)
                    e.rule.sellBps >= 10_000 -> tr("Exit plan sold all your {1}", sym)
                    else -> tr("Exit plan sold {1}% of your {2}", e.rule.sellBps / 100, sym)
                }
                val times = ExitMath.times(e.atBps)
                val at = if (mcap != null) tr("At {1}, MC {2}.", times, Fmt.usd(mcap)) else tr("At {1}.", times)
                val got = e.usd?.let { usd ->
                    val gain = e.gainUsd
                    if (gain != null) tr(" You got about {1}, {2}.", Fmt.usdFull(usd), Fmt.usdSigned(gain)) else tr(" You got about {1}.", Fmt.usdFull(usd))
                } ?: ""
                Alert("plan:${p.key}:${e.rule.id}:$now", AlertKind.PLAN, AlertTone.PLAN, title, at + got, now, link)
            }
            is ExitEvent.Failed -> Alert(
                "plan:${p.key}:${e.rule.id}:fail:$now",
                AlertKind.PLAN,
                AlertTone.BAD,
                tr("Exit plan could not sell your {1}", sym),
                if (e.givenUp) {
                    tr("{1}. It stopped trying: sell it yourself if you still want to.", e.error.trimEnd('.'))
                } else {
                    tr("{1}. It tries again.", e.error.trimEnd('.'))
                },
                now,
                link,
            )
            is ExitEvent.Waiting -> Alert(
                "plan:${p.key}:${e.rule.id}:wait:$now",
                AlertKind.PLAN,
                AlertTone.WARN,
                when (e.rule.kind) {
                    ExitKind.TP -> tr("{1} reached your take profit", sym)
                    ExitKind.SL -> tr("{1} hit your stop loss", sym)
                    ExitKind.TRAIL -> tr("{1} fell to your trailing stop", sym)
                },
                byShare(
                    e.rule.sellBps,
                    trKey("Unlock Nesty to sell all. The exit plan waits until then."),
                    trKey("Unlock Nesty to sell half. The exit plan waits until then."),
                    trKey("Unlock Nesty to sell {1}%. The exit plan waits until then."),
                ),
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
        tr("Exit plans paused"),
        tr("Android closed Nesty, which locks your wallets. Unlock Nesty so your exit plans can sell."),
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
            t.isBuy -> if (usd != null) tr("{1} bought {2} for {3}", name, symbol, Fmt.usd(usd)) else tr("{1} bought {2}", name, symbol)
            share != null && share >= 0.99 -> tr("{1} sold all their {2}", name, symbol)
            share != null && share in 0.45..0.55 -> tr("{1} sold half their {2}", name, symbol)
            else -> if (usd != null) tr("{1} sold {2} for {3}", name, symbol, Fmt.usd(usd)) else tr("{1} sold {2}", name, symbol)
        }
        val supply = price?.let { p -> if (p.priceUsd != null && p.mcapUsd != null && p.priceUsd > 0) p.mcapUsd / p.priceUsd else null }
        val mcThen = if (usd != null && t.tokens > 0 && supply != null) usd / t.tokens * supply else null
        val body = if (held != null) {
            val value = held.value
            val pnl = held.pnl
            val paid = held.position.paid
            val pct = if (pnl != null && paid > 0) (pnl / paid * 100).roundToInt() else null
            // Whole sentences, so each language words them itself.
            val hold = when {
                value != null && pct != null && pct >= 0 -> tr("You hold {1} of it, up {2}%.", Fmt.usdFull(value), abs(pct))
                value != null && pct != null -> tr("You hold {1} of it, down {2}%.", Fmt.usdFull(value), abs(pct))
                value != null -> tr("You hold {1} of it.", Fmt.usdFull(value))
                pct != null && pct >= 0 -> tr("You hold some of it, up {1}%.", abs(pct))
                pct != null -> tr("You hold some of it, down {1}%.", abs(pct))
                else -> tr("You hold some of it.")
            }
            val tp = plan?.rules?.firstOrNull { it.kind == ExitKind.TP && !it.done }
            hold + (
                tp?.let {
                    byShare(
                        it.sellBps,
                        trKey(" Your exit plan sells all at {1}."),
                        trKey(" Your exit plan sells half at {1}."),
                        trKey(" Your exit plan sells {1}% at {2}."),
                        ExitMath.times(it.triggerBps),
                    )
                } ?: ""
            )
        } else {
            listOfNotNull(mcThen?.let { tr("At MC {1}.", Fmt.usd(it)) }, price?.mcapUsd?.let { tr("It is {1} now.", Fmt.usd(it)) }).joinToString(" ")
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
        val mc = r.usdThen?.let { u -> r.supply?.let { u * it } }
        val within = when {
            minutes < 1 && mc != null -> tr("Within a minute, at MC {1}.", Fmt.usd(mc))
            minutes < 1 -> tr("Within a minute.")
            mc != null -> tr("Within {1} min, at MC {2}.", minutes, Fmt.usd(mc))
            else -> tr("Within {1} min.", minutes)
        }
        val record = figures?.takeIf { it.n >= MIN_RECORD }?.let { f ->
            tr(" Of signals like this, {1}% reached 2× and {2}% fell by half.", (f.x2 * 100.0 / f.n).roundToInt(), (f.half * 100.0 / f.n).roundToInt())
        } ?: ""
        return Alert(
            id = "sig:${chain.feed}:${r.token.lowercase()}:${r.size}",
            kind = AlertKind.SIGNAL,
            tone = AlertTone.SIGNAL,
            title = tr("Signal: {1} proven wallets bought {2}", r.size, sym),
            body = within + record,
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
