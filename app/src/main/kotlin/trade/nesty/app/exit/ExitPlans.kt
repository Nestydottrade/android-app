package trade.nesty.app.exit

import kotlinx.serialization.Serializable
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Fill
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.math.floor
import kotlin.math.max

/** What a step watches for. */
@Serializable
enum class ExitKind { TP, SL, TRAIL }

/**
 * One step of an exit plan, as the extension's shared/exits.ts has it. Take profit fires at a
 * multiple of the average cost (`triggerBps` 20 000 = 2×). A stop loss fires at a drop from the
 * average cost, a trailing stop at a drop from the highest multiple seen since the plan was set
 * (`triggerBps` 4 000 = 40% down). Each sells `sellBps` of what the wallet holds at that moment.
 */
@Serializable
data class ExitRule(
    val id: String,
    val kind: ExitKind,
    val triggerBps: Int,
    val sellBps: Int,
    /** Sold, or given up after [ExitMath.TRIES] failed tries. */
    val done: Boolean = false,
    /** Failed tries so far. */
    val tries: Int = 0,
    /** Why the last try failed. */
    val error: String? = null,
    /** When it fired and sold. */
    val firedAt: Long? = null,
    /** The multiple it sold at. */
    val firedBps: Int? = null,
) {
    /** A take profit above 1×, a stop between 1% and 99%, a sale between 0.01% and all. */
    val valid: Boolean
        get() = sellBps in 1..10_000 && triggerBps in 1..1_000_000 &&
            (if (kind == ExitKind.TP) triggerBps > 10_000 else triggerBps < 10_000)

    fun sameAs(other: ExitRule): Boolean = kind == other.kind && triggerBps == other.triggerBps && sellBps == other.sellBps

    /** Tried and given up. */
    val failed: Boolean get() = done && firedAt == null
}

/**
 * The exit plan of one wallet's position in one coin. Kept on the phone only; nothing of it goes
 * to Nesty's server.
 */
@Serializable
data class ExitPlan(
    val walletId: String,
    /** The wallet's address on the coin's chain: lowercase on the EVM chains, as it is on Solana. */
    val wallet: String,
    val feed: String,
    /** The coin, the same way. */
    val token: String,
    val symbol: String = "",
    val decimals: Int = 18,
    val rules: List<ExitRule>,
    val createdAt: Long,
    /** Highest multiple of the average cost seen since it was set, for trailing stops. */
    val peakBps: Int? = null,
) {
    val chain: Chain? get() = Chain.of(feed)
    val key: String get() = ExitMath.keyOf(walletId, feed, token)
    val label: String get() = symbol.ifBlank { "this coin" }

    /** Steps still waiting. */
    val open: List<ExitRule> get() = rules.filter { !it.done }
}

/** Tokens bought through Nesty and still held, and what they cost in dollars, fees included. */
data class CostBasis(val tokens: BigInteger, val costUsd: Double) {
    companion object {
        val NONE = CostBasis(BigInteger.ZERO, 0.0)
    }
}

/** The step to fire now, if any, and the highest multiple seen. */
data class Due(val rule: ExitRule?, val peakBps: Int)

object ExitMath {
    /** Plans watched at once, across wallets and chains. */
    const val MAX_PLANS = 30

    /** Times a step's sell is tried before it is given up and shown as failed. */
    const val TRIES = 3

    fun keyOf(walletId: String, feed: String, token: String): String =
        "$walletId:$feed:" + if (feed == Chain.SOLANA.feed) token else token.lowercase()

    private fun ratio(part: BigInteger, whole: BigInteger): Double =
        if (whole.signum() <= 0) 0.0 else BigDecimal(part).divide(BigDecimal(whole), MathContext.DECIMAL64).toDouble()

    /**
     * The average-cost basis of one wallet's coin from its fills, in any order: buys add their
     * tokens and the dollars paid with their network fee (and the move that funded them), sells
     * take tokens out at the average cost. A buy without a dollar figure has no cost on record, so
     * its tokens are left out, as tokens that came from outside Nesty are.
     */
    fun costBasis(fills: List<Fill>): CostBasis {
        var tokens = BigInteger.ZERO
        var cost = 0.0
        for (f in fills.sortedBy { it.at }) {
            val amount = f.amount
            if (amount.signum() <= 0) continue
            if (f.isBuy) {
                val usd = f.usd ?: continue
                tokens += amount
                cost += usd + (f.feeUsd ?: 0.0)
            } else {
                if (tokens.signum() == 0) continue
                val sold = amount.min(tokens)
                cost -= cost * ratio(sold, tokens)
                tokens -= sold
            }
        }
        if (tokens.signum() == 0) return CostBasis.NONE
        return CostBasis(tokens, cost.coerceAtLeast(0.0))
    }

    /** The part of `held` that has a cost on record, and what that part cost. */
    fun covered(held: BigInteger, basis: CostBasis): CostBasis {
        if (basis.tokens.signum() <= 0 || held.signum() <= 0) return CostBasis.NONE
        val tokens = held.min(basis.tokens)
        return CostBasis(tokens, basis.costUsd * ratio(tokens, basis.tokens))
    }

    /**
     * The price now as a multiple of the average cost, in bps: what selling all of `held` pays
     * (`outUsd`, price impact and tax included) for its covered part, against what that part cost.
     */
    fun multipleBps(outUsd: Double, held: BigInteger, covered: CostBasis): Int {
        if (held.signum() <= 0 || covered.tokens.signum() <= 0 || covered.costUsd <= 0 || outUsd.isNaN() || outUsd < 0) return 0
        val bps = outUsd * ratio(covered.tokens, held) / covered.costUsd * 10_000
        return floor(bps.coerceAtMost(1e9)).toInt()
    }

    /**
     * The step to fire now, if any, and the new peak. One step per check: the sell changes the
     * holding, and the next check measures again. Stops win over take profits; among equals, the
     * one that sells more.
     */
    fun dueRule(rules: List<ExitRule>, peakBps: Int?, nowBps: Int): Due {
        val peak = max(peakBps ?: 0, nowBps)
        fun hit(r: ExitRule): Boolean = when (r.kind) {
            ExitKind.TP -> nowBps >= r.triggerBps
            ExitKind.SL -> nowBps <= 10_000 - r.triggerBps
            ExitKind.TRAIL -> nowBps.toLong() * 10_000 <= peak.toLong() * (10_000 - r.triggerBps)
        }
        val due = rules.filter { !it.done && hit(it) }
            .sortedWith(compareBy<ExitRule> { if (it.kind == ExitKind.TP) 1 else 0 }.thenByDescending { it.sellBps })
        return Due(due.firstOrNull(), peak)
    }

    /**
     * Saving a plan again keeps the progress of the steps that did not change (a fired step stays
     * fired), so changing one step never re-arms the others. A changed step starts over.
     */
    fun mergeRules(prev: List<ExitRule>, next: List<ExitRule>): List<ExitRule> = next.map { r ->
        val old = prev.firstOrNull { it.id == r.id }
        if (old != null && old.sameAs(r)) old else r.copy(done = false, tries = 0, error = null, firedAt = null, firedBps = null)
    }

    /** Short label for a step: "2.5× · sell 50%". */
    fun ruleText(r: ExitRule): String {
        val sell = if (r.sellBps == 10_000) "sell all" else "sell ${trimPct(r.sellBps)}"
        return when (r.kind) {
            ExitKind.TP -> "${trim(r.triggerBps / 10_000.0)}× · $sell"
            ExitKind.SL -> "−${trimPct(r.triggerBps)} · $sell"
            ExitKind.TRAIL -> "−${trimPct(r.triggerBps)} from peak · $sell"
        }
    }

    /** A multiple in bps as people say it: "2.1×". */
    fun times(bps: Int): String = "${trim(bps / 10_000.0)}×"

    /** The multiple a step fires at, given the peak for a trailing stop. */
    fun triggerAt(r: ExitRule, peakBps: Int?): Int? = when (r.kind) {
        ExitKind.TP -> r.triggerBps
        ExitKind.SL -> 10_000 - r.triggerBps
        ExitKind.TRAIL -> peakBps?.takeIf { it > 0 }?.let { (it.toLong() * (10_000 - r.triggerBps) / 10_000).toInt() }
    }

    fun trim(n: Double): String {
        val r = Math.round(n * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    private fun trimPct(bps: Int) = "${trim(bps / 100.0)}%"
}

/**
 * The plan as the Position screen sets it, from the board: one take profit, a trailing stop that
 * can be off, and a stop loss. The stops sell all.
 */
data class ExitDraft(
    val tpX: Int = 2,
    val tpPct: Int = 50,
    val trailOn: Boolean = false,
    val trailPct: Int = 30,
    val slPct: Int = 50,
) {
    fun rules(): List<ExitRule> = listOfNotNull(
        ExitRule(TP, ExitKind.TP, tpX * 10_000, tpPct * 100),
        if (trailOn) ExitRule(TRAIL, ExitKind.TRAIL, trailPct * 100, 10_000) else null,
        ExitRule(SL, ExitKind.SL, slPct * 100, 10_000),
    )

    companion object {
        const val TP = "tp"
        const val TRAIL = "trail"
        const val SL = "sl"
        val TP_X = listOf(2, 3, 5, 10)
        val TP_PCT = listOf(25, 50, 100)
        val TRAIL_PCT = listOf(20, 30, 40)
        val SL_PCT = listOf(30, 50, 70)

        /** A saved plan's steps as the screen's choices; what is not there keeps its default. */
        fun of(rules: List<ExitRule>): ExitDraft {
            val d = ExitDraft()
            val tp = rules.firstOrNull { it.kind == ExitKind.TP }
            val trail = rules.firstOrNull { it.kind == ExitKind.TRAIL }
            val sl = rules.firstOrNull { it.kind == ExitKind.SL }
            return ExitDraft(
                tpX = tp?.let { it.triggerBps / 10_000 } ?: d.tpX,
                tpPct = tp?.let { it.sellBps / 100 } ?: d.tpPct,
                trailOn = trail != null,
                trailPct = trail?.let { it.triggerBps / 100 } ?: d.trailPct,
                slPct = sl?.let { it.triggerBps / 100 } ?: d.slPct,
            )
        }
    }
}
