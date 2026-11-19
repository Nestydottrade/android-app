package trade.nesty.app.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToLong
import trade.nesty.app.i18n.tr
import trade.nesty.app.i18n.trCount
import trade.nesty.app.i18n.trKey

/** Figures as the boards write them. No clock times anywhere: ages are relative. */
object Fmt {
    /** The minus sign the boards use. */
    const val MINUS = "−"
    const val DASH = "–"

    private fun fixed(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

    /** 820, 9.8K, 41.3K, 412K, 2.74M, 27.4M, 412M, 1.20B: about three figures. */
    fun compact(v: Double): String {
        val a = abs(v)
        val sign = if (v < 0) MINUS else ""
        val body = when {
            a < 1_000 -> plain(a)
            a < 100_000 -> fixed(a / 1_000, 1) + "K"
            a < 1_000_000 -> fixed(a / 1_000, 0) + "K"
            a < 10_000_000 -> fixed(a / 1_000_000, 2) + "M"
            a < 100_000_000 -> fixed(a / 1_000_000, 1) + "M"
            a < 1_000_000_000 -> fixed(a / 1_000_000, 0) + "M"
            a < 10_000_000_000 -> fixed(a / 1_000_000_000, 2) + "B"
            else -> fixed(a / 1_000_000_000, 1) + "B"
        }
        return sign + fixKiloEdge(body)
    }

    // 999.95K rounds up to "1000K": write it as the next unit.
    private fun fixKiloEdge(s: String): String = when (s) {
        "1000" -> "1.0K"
        "100.0K" -> "100K"
        "1000K" -> "1.00M"
        "10.00M" -> "10.0M"
        "100.0M" -> "100M"
        "1000M" -> "1.00B"
        "10.00B" -> "10.0B"
        else -> s
    }

    private fun plain(a: Double): String = when {
        a == 0.0 -> "0"
        a < 1 -> fixed(a, 2)
        a < 10 -> fixed(a, 2).removeSuffix(".00")
        else -> a.roundToLong().toString()
    }

    /** An amount of a coin, with four figures below one: 0.0034, 0.42, 4.20, 1.2K. */
    fun amount(v: Double): String {
        val a = abs(v)
        if (a == 0.0 || a.isNaN()) return "0"
        if (a >= 1) return compact(v)
        val digits = (ceil(-log10(a)).toInt() + 3).coerceIn(2, 12)
        val body = fixed(a, digits).trimEnd('0').trimEnd('.')
        return (if (v < 0) MINUS else "") + body
    }

    /** $14.9K; a dash when not known. */
    fun usd(v: Double?): String = if (v == null || v.isNaN()) DASH else (if (v < 0) MINUS else "") + "$" + compact(abs(v))

    /** $1,059 or $4.20 in full, for amounts a person paid. */
    fun usdFull(v: Double): String {
        val a = abs(v)
        val s = if (a < 1_000) fixed(a, 2) else String.format(Locale.US, "%,d", a.roundToLong())
        return (if (v < 0) MINUS else "") + "$" + s
    }

    /** +$734, −$34. */
    fun usdSigned(v: Double): String {
        val a = abs(v)
        val body = if (a < 10_000) {
            if (a < 10) fixed(a, 2) else String.format(Locale.US, "%,d", a.roundToLong())
        } else compact(a)
        return (if (v < 0) MINUS else "+") + "$" + body
    }

    /** A percent as a number of percent: +86%, −8%, +0.3%. */
    fun pctChange(p: Double?): String {
        if (p == null || p.isNaN()) return DASH
        val a = abs(p)
        if (a < 0.05) return "0%"
        val body = if (a >= 10) a.roundToLong().toString() else fixed(a, 1).removeSuffix(".0")
        return (if (p < 0) MINUS else "+") + body + "%"
    }

    /** A share of 0 to 1 as the Pulse writes it: 0%, 6.2%, 34%. */
    fun share(v: Double?): String {
        if (v == null || v.isNaN()) return DASH
        return when {
            v < 0.0005 -> "0%"
            v < 0.1 -> fixed(v * 100, 1).removeSuffix(".0") + "%"
            else -> (v * 100).roundToLong().toString() + "%"
        }
    }

    /** A whole percent of a share, for tax: 3%, 0.5%. */
    fun taxPct(v: Double): String {
        val p = v * 100
        return if (abs(p - p.roundToLong()) < 0.05) p.roundToLong().toString() + "%" else fixed(p, 1) + "%"
    }

    /** 0.71×, 3.2×, 12×. */
    fun times(x: Double): String = when {
        x < 10 -> fixed(x, if (x < 1) 2 else 1).removeSuffix(".0") + "×"
        else -> x.roundToLong().toString() + "×"
    }

    /** An age from milliseconds: 41s, 3m, 2h, 1d, 3w. */
    fun age(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h"
            s < 86_400 * 14 -> "${s / 86_400}d"
            s < 86_400 * 365 -> "${s / (86_400 * 7)}w"
            else -> "${s / (86_400 * 365)}y"
        }
    }

    fun ageSince(at: Long?, now: Long): String = if (at == null || at <= 0) DASH else age(now - at)

    /** 6 minutes, 2 hours: a length of time in words. */
    fun duration(ms: Double): String {
        val s = (ms / 1000).roundToLong().coerceAtLeast(0)
        return when {
            s < 60 -> trCount(s, "{1} second", "{1} seconds", s)
            s < 3_600 -> trCount(s / 60, "{1} minute", "{1} minutes", s / 60)
            s < 86_400 -> trCount(s / 3_600, "{1} hour", "{1} hours", s / 3_600)
            else -> trCount(s / 86_400, "{1} day", "{1} days", s / 86_400)
        }
    }

    /** How long a move takes, from the bridge's estimate: a few seconds, about 20 s, about 2 minutes. */
    fun eta(seconds: Double?): String = when {
        seconds == null || seconds.isNaN() || seconds <= 10 -> tr("a few seconds")
        seconds < 60 -> tr("about {1} s", seconds.roundToLong())
        else -> tr("about {1}", duration(seconds * 1000))
    }

    fun count(n: Long): String = if (n >= 10_000) compact(n.toDouble()) else String.format(Locale.US, "%,d", n)

    /** 1 holder, 2 holders, 12.4K trades: [one] and [other] put the count at `{1}`. */
    fun counted(n: Long, one: String, other: String): String = trCount(n, one, other, count(n))

    /** "BNB Chain and Base": a list joined as the app's language joins one. */
    fun joinAnd(items: List<String>): String = items.joinToString(tr(" and "))

    /** 0x9a…1f07 on EVM chains, 9xQe…Fh7k on Solana. */
    fun address(a: String): String =
        if (a.startsWith("0x") && a.length > 12) a.substring(0, 4) + "…" + a.takeLast(4)
        else if (a.length > 10) a.take(4) + "…" + a.takeLast(4)
        else a

    /** "13 November" from "2026-11-13": a day, never a clock time. */
    fun day(iso: String): String {
        val parts = iso.split("-")
        if (parts.size != 3) return iso
        val month = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return iso
        val d = parts[2].toIntOrNull() ?: return iso
        return tr(month, d)
    }

    /** Each month with its day at `{1}`, as each language writes a date. */
    private val MONTHS = listOf(
        trKey("{1} January"), trKey("{1} February"), trKey("{1} March"), trKey("{1} April"), trKey("{1} May"), trKey("{1} June"),
        trKey("{1} July"), trKey("{1} August"), trKey("{1} September"), trKey("{1} October"), trKey("{1} November"), trKey("{1} December"),
    )
}
