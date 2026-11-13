package trade.nesty.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Holder
import trade.nesty.app.data.Share
import trade.nesty.app.data.Tax
import trade.nesty.app.ui.theme.Ink

/** How a figure reads: the round mark beside it. */
enum class Tone(val bg: Color, val ink: Color, val value: Color) {
    BAD(Ink.pinkBg, Ink.pink, Ink.pink),
    WARN(Ink.amberBg, Ink.amber, Ink.amber),
    WALLETS(Ink.violetDeep, Ink.violet, Ink.text),
    INFO(Ink.track, Ink.muted, Ink.text),
    OK(Ink.greenOk, Ink.green, Ink.text),
    NONE(Ink.track, Ink.muted, Ink.muted);

    val icon: ImageVector
        get() = when (this) {
            BAD -> NIcons.toneBad
            WARN -> NIcons.toneWarn
            WALLETS -> NIcons.toneWallets
            INFO -> NIcons.toneInfo
            OK -> NIcons.toneOk
            NONE -> NIcons.toneNone
        }

    /** For a screen reader, after the label. */
    val word: String
        get() = when (this) {
            BAD -> "red flag"
            WARN -> "worth watching"
            OK -> "fine"
            NONE -> "not read yet"
            else -> ""
        }
}

data class Check(val tone: Tone, val label: String, val sub: String, val value: String)

/** One line under a coin in a list: the thing most worth knowing first. */
data class Flag(val tone: Tone, val text: String)

/** The site's thresholds (website/src/components/info.tsx WARN_AT). */
object Warn {
    const val TOP10 = 0.3
    const val SHARE = 0.1
    const val TAX = 0.1
}

object Checks {
    private fun shareTone(v: Double, at: Double) = if (v >= at) Tone.WARN else Tone.OK

    private fun taxText(t: Tax): String? {
        val b = t.buy ?: 0.0
        val s = t.sell ?: 0.0
        if (b == 0.0 && s == 0.0) return "0%"
        if (b == s) return Fmt.taxPct(b)
        return "${Fmt.taxPct(b)} buy · ${Fmt.taxPct(s)} sell"
    }

    private fun taxTone(t: Tax): Tone = if (maxOf(t.buy ?: 0.0, t.sell ?: 0.0) >= Warn.TAX) Tone.WARN else Tone.OK

    /** What the wallets the book marks as one operator's hold, from the holder list. */
    fun clusterShare(holders: List<Holder>): Double =
        holders.filter { it.role == null && "cluster" in it.tags }.sumOf { it.share }

    /**
     * A coin's checks, worst first. [holders] is null where its holder list is not served
     * (Solana, or a coin the holder book does not follow).
     */
    fun of(coin: Coin, shareIn: Share?, holders: List<Holder>?, solana: Boolean, followed: Boolean = true): List<Check> {
        val out = mutableListOf<Check>()
        val share = shareIn?.takeIf {
            it.top10 != null || it.bundlers != null || it.creator != null || it.snipers != null || it.team != null
        }
        if (solana) {
            out += Check(Tone.NONE, "Holder shares", "", "Not counted on Solana")
        } else if (share != null) {
            share.bundlers?.let { out += Check(shareTone(it, Warn.SHARE), "Bundlers hold", "Bought in the launch block", Fmt.share(it)) }
            share.top10?.let { out += Check(shareTone(it, Warn.TOP10), "Top 10 hold", "Pools and burns left out", Fmt.share(it)) }
            share.creator?.let { out += Check(shareTone(it, Warn.SHARE), "Creator holds", "The wallet that launched it", Fmt.share(it)) }
            share.snipers?.let { out += Check(shareTone(it, Warn.SHARE), "Snipers hold", "Bought in the first seconds", Fmt.share(it)) }
            share.team?.takeIf { it.count > 0 }?.let {
                out += Check(shareTone(it.share, Warn.SHARE), "Team holds", "Listed by the creator at launch", Fmt.share(it.share))
            }
        }
        coin.tax?.let { t -> taxText(t)?.let { out += Check(taxTone(t), "Tax", "", it) } }
        if (holders != null) {
            val c = clusterShare(holders)
            out += when {
                c >= Warn.SHARE -> Check(Tone.BAD, "Cluster", "Wallets one operator runs", Fmt.share(c))
                c > 0 -> Check(Tone.WARN, "Cluster", "Wallets one operator runs", Fmt.share(c))
                else -> Check(Tone.OK, "Cluster", "", "None holding")
            }
        }
        if (!solana && share == null && holders == null) {
            out += Check(Tone.NONE, "Holder shares", "", if (followed) "Not read yet" else "Not counted for this coin")
        }
        return out.sortedBy { order(it.tone) }
    }

    private fun order(t: Tone) = when (t) {
        Tone.BAD -> 0
        Tone.WARN -> 1
        Tone.WALLETS -> 2
        Tone.OK -> 3
        Tone.INFO -> 4
        Tone.NONE -> 5
    }

    /**
     * The line under a coin in a Discover list: a signal first, then the largest warning, then
     * a tax, then the top 10, then where it trades.
     */
    fun flag(signalSize: Int?, share: Share?, tax: Tax?, venue: String?): Flag? {
        if (signalSize != null && signalSize > 0) return Flag(Tone.WALLETS, "$signalSize proven wallets bought")
        if (share != null) {
            val warns = listOfNotNull(
                share.bundlers?.takeIf { it >= Warn.SHARE }?.let { it to "Bundlers hold" },
                share.snipers?.takeIf { it >= Warn.SHARE }?.let { it to "Snipers hold" },
                share.creator?.takeIf { it >= Warn.SHARE }?.let { it to "Creator holds" },
                share.team?.takeIf { it.count > 0 && it.share >= Warn.SHARE }?.let { it.share to "Team holds" },
                share.top10?.takeIf { it >= Warn.TOP10 }?.let { it to "Top 10 hold" },
            )
            warns.maxByOrNull { it.first }?.let { return Flag(Tone.WARN, "${it.second} ${Fmt.share(it.first)}") }
        }
        if (tax != null) {
            val most = maxOf(tax.buy ?: 0.0, tax.sell ?: 0.0)
            if (most >= Warn.TAX) return Flag(Tone.WARN, "Tax ${Fmt.taxPct(most)}")
        }
        share?.top10?.takeIf { it >= 0.0005 }?.let { return Flag(Tone.INFO, "Top 10 hold ${Fmt.share(it)}") }
        if (tax != null) {
            val most = maxOf(tax.buy ?: 0.0, tax.sell ?: 0.0)
            if (most > 0) return Flag(Tone.INFO, "Tax ${Fmt.taxPct(most)}")
        }
        venue?.takeIf { it.isNotBlank() }?.let { return Flag(Tone.INFO, "On $it") }
        return null
    }
}
