package trade.nesty.app.exit

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import trade.nesty.app.data.Fill
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import trade.nesty.app.i18n.tr

/** What the watcher reads and does through the engines, so tests can stand in for them. */
interface ExitMarket {
    /** Whether a sell can be signed for the plan's wallet now: the vault open, or kept for the watch. */
    fun canSign(plan: ExitPlan): Boolean

    /** What the wallet holds of the coin, read from its chain; null when it could not be read. */
    suspend fun held(plan: ExitPlan): BigInteger?

    /** What selling `amount` pays now, in dollars, price impact and tax included; or why it cannot be sold. */
    suspend fun sellValue(plan: ExitPlan, amount: BigInteger): SellValue

    /** Sells `bps` of what the wallet holds, signed for the plan's wallet, and waits for how it ends. */
    suspend fun sell(plan: ExitPlan, bps: Int): SellEnd

    /** The fills of the plan's wallet in its coin: its cost basis. */
    fun fills(plan: ExitPlan): List<Fill>
}

sealed interface SellValue {
    data class Usd(val usd: Double) : SellValue
    data class None(val why: String) : SellValue
}

/**
 * How a sell ended: landed with what it paid in dollars, or why not. `retry` false: it was sent and
 * may still land, so it is not sent again.
 */
data class SellEnd(val ok: Boolean, val usd: Double? = null, val error: String? = null, val retry: Boolean = true)

/** What the watcher last saw of a plan. In memory only. */
data class ExitStatus(
    /** The price now as a multiple of the average cost, in bps; null before the first check. */
    val nowBps: Int? = null,
    val checkedAt: Long? = null,
    /** Why it is not checking or cannot sell now; null when it can. */
    val paused: String? = null,
    /** The step being sold now. */
    val selling: String? = null,
)

/** What a plan did, for the notes and notifications. */
sealed interface ExitEvent {
    val plan: ExitPlan
    val rule: ExitRule

    /** `gainUsd`: what the sale got over what the sold part cost. */
    data class Sold(override val plan: ExitPlan, override val rule: ExitRule, val atBps: Int, val usd: Double?, val gainUsd: Double?) : ExitEvent

    data class Failed(override val plan: ExitPlan, override val rule: ExitRule, val error: String, val givenUp: Boolean) : ExitEvent

    /** A step is due but nothing can sign: Nesty was locked and Android ended the app since. */
    data class Waiting(override val plan: ExitPlan, override val rule: ExitRule, val atBps: Int) : ExitEvent
}

/**
 * Take profit, trailing stop and stop loss: per position, a few steps that each sell part of
 * the holding when its price reaches a multiple of the average cost. Ported from the extension's
 * exits.ts. Each plan is checked every few seconds while the app runs (the watch service keeps it
 * running with the app closed); each sell goes through the usual engine, validator and
 * simulation included.
 *
 * The price is what selling the whole holding pays now, against what the part of it bought
 * through Nesty cost with its fees: tokens that came from elsewhere have no cost and are left out.
 */
class ExitWatcher(
    private val store: ExitStore,
    private val market: ExitMarket,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val status = mutableStateMapOf<String, ExitStatus>()

    private val eventsFlow = MutableSharedFlow<ExitEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ExitEvent> = eventsFlow.asSharedFlow()

    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val zeros = ConcurrentHashMap<String, Int>()
    private val waiting = ConcurrentHashMap<String, String>()
    private val sellLocks = ConcurrentHashMap<String, Mutex>()

    init {
        // A step marked done whose sell never reported back: the app was closed while it sold.
        store.update { list ->
            list.map { p -> p.copy(rules = p.rules.map { r -> if (r.done && r.firedAt == null && r.error == null) r.copy(error = CLOSED) else r }) }
        }
    }

    fun statusOf(key: String): ExitStatus? = status[key]

    /** Starts a check of each plan that is not being checked now. A check that sells lasts as long as its sell. */
    fun tick(scope: CoroutineScope) {
        for (p in store.plans) {
            val key = p.key
            if (!busy.add(key)) continue
            scope.launch {
                try {
                    checkSafely(key)
                } finally {
                    busy.remove(key)
                }
            }
        }
    }

    /** Checks every [CHECK_MS] while a plan is open, for as long as the calling coroutine lives. */
    suspend fun run(scope: CoroutineScope) {
        while (coroutineContext.isActive) {
            if (store.plans.isEmpty()) {
                delay(IDLE_MS)
                continue
            }
            tick(scope)
            delay(CHECK_MS)
        }
    }

    private suspend fun checkSafely(key: String) {
        val plan = store.get(key) ?: return
        try {
            check(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pause(key, tr("Price check failed; trying again."))
        }
    }

    private fun pause(key: String, why: String) {
        status[key] = (status[key] ?: ExitStatus()).copy(paused = why)
    }

    /** One check of one plan: measure, then sell the step that is due, if any. */
    internal suspend fun check(plan: ExitPlan) {
        val key = plan.key
        val held = market.held(plan) ?: return pause(key, tr("Price check failed; trying again."))
        if (held.signum() == 0) {
            val n = (zeros[key] ?: 0) + 1
            zeros[key] = n
            // Sold out, by the plan or by hand: nothing left to watch. One empty read is read again first.
            if (plan.rules.any { it.firedAt != null } || n >= 2) {
                remove(key)
                return
            }
            return pause(key, tr("You hold none of it now."))
        }
        zeros.remove(key)
        val value = market.sellValue(plan, held)
        if (value is SellValue.None) return pause(key, value.why)
        val usd = (value as SellValue.Usd).usd
        val covered = ExitMath.covered(held, ExitMath.costBasis(market.fills(plan)))
        if (covered.tokens.signum() == 0 || covered.costUsd <= 0) return pause(key, tr("No cost on record for what you hold."))

        val nowBps = ExitMath.multipleBps(usd, held, covered)
        val due = ExitMath.dueRule(plan.rules, plan.peakBps, nowBps)
        val signs = market.canSign(plan)
        status[key] = ExitStatus(nowBps, now(), if (signs) null else LOCKED, status[key]?.selling)
        if (due.peakBps != plan.peakBps) store.patch(key) { it.copy(peakBps = maxOf(it.peakBps ?: 0, due.peakBps)) }
        val rule = due.rule
        if (rule == null) {
            waiting.remove(key)
            return
        }
        if (!signs) {
            // Said once per step, not every few seconds.
            if (waiting.put(key, rule.id) != rule.id) eventsFlow.emit(ExitEvent.Waiting(plan, rule, nowBps))
            return
        }
        waiting.remove(key)
        sell(plan, rule, nowBps, covered)
    }

    private suspend fun sell(plan: ExitPlan, due: ExitRule, atBps: Int, covered: CostBasis) {
        val key = plan.key
        // One sell at a time from a wallet on a chain: each takes the next nonce.
        sellLocks.getOrPut("${plan.walletId}:${plan.feed}") { Mutex() }.withLock {
            val fresh = store.get(key) ?: return
            val rule = fresh.rules.firstOrNull { it.id == due.id && !it.done && it.sameAs(due) } ?: return
            // Marked first, so a slow sell is never sent twice by the next check.
            store.patchRule(key, rule.id) { it.copy(done = true) }
            status[key] = (status[key] ?: ExitStatus()).copy(selling = rule.id)
            val end = try {
                market.sell(fresh, rule.sellBps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SellEnd(false, error = e.message)
            } finally {
                status[key] = (status[key] ?: ExitStatus()).copy(selling = null)
            }
            if (end.ok) {
                val at = now()
                store.patchRule(key, rule.id) { it.copy(done = true, firedAt = at, firedBps = atBps, error = null) }
                val gain = end.usd?.let { it - covered.costUsd * rule.sellBps / 10_000.0 }
                eventsFlow.emit(ExitEvent.Sold(fresh, rule, atBps, end.usd, gain))
            } else {
                val tries = rule.tries + 1
                val error = (end.error?.takeIf { it.isNotBlank() } ?: tr("Could not sell.")).take(140)
                val givenUp = !end.retry || tries >= ExitMath.TRIES
                store.patchRule(key, rule.id) { it.copy(done = givenUp, tries = tries, error = error) }
                eventsFlow.emit(ExitEvent.Failed(fresh, rule, error, givenUp))
            }
        }
    }

    /** Saves a plan from the Position screen; steps that did not change keep their progress. */
    fun set(plan: ExitPlan) {
        val prev = store.get(plan.key)
        val merged = plan.copy(
            rules = ExitMath.mergeRules(prev?.rules.orEmpty(), plan.rules),
            createdAt = prev?.createdAt ?: plan.createdAt,
            peakBps = prev?.peakBps,
        )
        store.put(merged)
        waiting.remove(plan.key)
    }

    fun remove(key: String) {
        store.remove(key)
        status.remove(key)
        zeros.remove(key)
        waiting.remove(key)
    }

    /** Whether another plan can be set: at most [ExitMath.MAX_PLANS] are watched at once. */
    fun roomFor(key: String): Boolean = store.get(key) != null || store.plans.size < ExitMath.MAX_PLANS

    companion object {
        /** How often each plan is checked. The extension checks about every second in a desktop browser. */
        const val CHECK_MS = 3_000L
        private const val IDLE_MS = 1_000L
        val LOCKED: String get() = tr("Paused until you unlock Nesty.")
        val CLOSED: String get() = tr("Nesty closed while this step sold. Check your activity.")
    }
}
