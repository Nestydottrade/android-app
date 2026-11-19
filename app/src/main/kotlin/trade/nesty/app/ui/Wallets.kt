package trade.nesty.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.i18n.tr
import trade.nesty.app.i18n.trKey
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/** A wallet tag as a chip: the book's tags, and Tracked for a wallet the user follows. */
enum class WalletTag(private val key: String, val bg: Color, val line: Color, val ink: Color) {
    CREATOR(trKey("Creator"), Color.Transparent, Ink.violet.copy(alpha = 0.6f), Ink.violetSoft),
    TEAM(trKey("Team"), Color.Transparent, Ink.teal.copy(alpha = 0.55f), Ink.teal),
    SNIPER(trKey("Sniper"), Color.Transparent, Color(0x995AA9F5), Ink.sniper),
    BUNDLER(trKey("Bundler"), Color.Transparent, Ink.amber.copy(alpha = 0.55f), Ink.amber),
    CLUSTER(trKey("Cluster"), Ink.pink, Ink.pink, Ink.pinkInk),
    DEV(trKey("Dev"), Color.Transparent, Ink.violet.copy(alpha = 0.6f), Ink.violetSoft),
    FLIPPER(trKey("Flipper"), Color.Transparent, Ink.line3, Ink.text2),
    HOLDER(trKey("Holder"), Color.Transparent, Ink.greenLine, Ink.greenSoft),
    TRACKED(trKey("Tracked"), Ink.greenDeep, Ink.greenLine, Ink.green),

    // The Key wallets list's own parts, never read from a tag.
    INSIDER(trKey("Insider"), Color.Transparent, Ink.pink.copy(alpha = 0.55f), Ink.pink),
    TOP(trKey("Top 10"), Color.Transparent, Ink.line3, Ink.text2),
    BIG(trKey("Big buy"), Color.Transparent, Ink.greenLine, Ink.greenSoft);

    /** Its name in the language the app shows. */
    val label: String get() = tr(key)

    companion object {
        fun of(tag: String?): WalletTag? = when (tag?.lowercase()) {
            "creator" -> CREATOR
            "team" -> TEAM
            "sniper" -> SNIPER
            "bundler" -> BUNDLER
            "cluster", "farmer" -> CLUSTER
            "dev" -> DEV
            "flipper" -> FLIPPER
            "holder" -> HOLDER
            else -> null
        }

        /** The one role tag first, then Cluster beside it. */
        fun list(tags: List<String>): List<WalletTag> = tags.mapNotNull { of(it) }.distinct().sortedBy { if (it == CLUSTER) 1 else 0 }
    }
}

@Composable
fun TagChip(tag: WalletTag, label: String = tag.label) {
    Box(
        Modifier
            .height(20.dp)
            .background(tag.bg, RoundedCornerShape(6.dp))
            .border(1.dp, tag.line, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(11, FontWeight.Bold, tag.ink), maxLines = 1)
    }
}

/** A wallet's name as the user gave it, else its short address, with its tags. */
@Composable
fun WalletName(address: String, tags: List<String> = emptyList(), size: Int = 14, modifier: Modifier = Modifier) {
    val name = AppGraph.settings.nameOf(address)
    val chips = WalletTag.list(tags).let { if (AppGraph.settings.isFollowed(address)) it + WalletTag.TRACKED else it }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            name ?: Fmt.address(address),
            style = text(size, if (name != null) FontWeight.Bold else FontWeight.Medium, if (name != null) Ink.text else Ink.text2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        for (c in chips.take(2)) TagChip(c)
    }
}
