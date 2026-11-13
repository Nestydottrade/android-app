package trade.nesty.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ChainStats
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.StatsReport
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberEntryModel
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

enum class StatsWindow(val window: String, val label: String, val words: String, val ago: String) {
    H1("1h", "1H", "the last hour", "1h ago"),
    H6("6h", "6H", "the last 6 hours", "6h ago"),
    H24("24h", "24H", "the last 24 hours", "24h ago"),
    D7("7d", "7D", "the last 7 days", "7d ago"),
}

class StatsModel(private val api: NestyApi) {
    var window by mutableStateOf(StatsWindow.H24)
    private val reports = mutableStateMapOf<StatsWindow, StatsReport>()
    var failed by mutableStateOf(false)
        private set

    fun report(): StatsReport? = reports[window]

    suspend fun live() {
        snapshotFlow { window }.collectLatest { w ->
            while (true) {
                runCatching { reports[w] = api.stats(w.window).stats }.onSuccess { failed = false }.onFailure { failed = reports[w] == null }
                delay(60_000)
            }
        }
    }
}

/** The chains' bars summed, newest last; chains with fewer bars line up at the newest. */
fun sumBars(chains: List<ChainStats>): List<Double> {
    val n = chains.maxOfOrNull { it.bars.size } ?: 0
    val out = DoubleArray(n)
    for (c in chains) {
        val off = n - c.bars.size
        c.bars.forEachIndexed { i, b -> out[off + i] += b.getOrNull(0) ?: 0.0 }
    }
    return out.toList()
}

private fun stepWords(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 3_600_000 -> "each bar ${ms / 60_000} min"
    ms == 3_600_000L -> "each bar 1 hour"
    else -> "each bar ${ms / 3_600_000} hours"
}

private fun pctOf(part: Double, whole: Double) = if (whole > 0) Math.round(part / whole * 100) else 0

@Composable
fun StatsScreen() {
    val model = rememberEntryModel("stats") { StatsModel(AppGraph.api) }
    val nav = LocalNavigator.current
    WhileShown { model.live() }
    val r = model.report()

    Column(Modifier.fillMaxSize()) {
        BackHeader("Stats", onBack = { nav.pop() }) {
            Text("Launchpad coins", style = text(13, color = Ink.muted), modifier = Modifier.padding(end = 12.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.raised).padding(2.dp)) {
                for (w in StatsWindow.entries) {
                    val on = model.window == w
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (on) Ink.violetBg else Color.Transparent)
                            .clickable(role = Role.Button) { model.window = w }
                            .semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Text(w.label, style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.muted)) }
                }
            }
            if (r == null) {
                EmptyNote(if (model.failed) "Could not reach Nesty. Trying again." else "Loading…")
                return@Column
            }
            val cs = r.chains
            val trades = cs.sumOf { it.trades }.toDouble()
            val buys = cs.sumOf { it.buys }.toDouble()
            val volume = cs.sumOf { it.volume }
            val bought = cs.sumOf { it.bought }
            val traders = cs.sumOf { it.traders }.toDouble()
            val launched = cs.sumOf { it.launched }.toDouble()
            val devSold = cs.sumOf { it.devSold }.toDouble()
            val tiles = listOf(
                Triple("Trades", Fmt.count(trades.toLong()), "${pctOf(buys, trades)}% were buys"),
                Triple("Volume", Fmt.usd(volume), "${pctOf(bought, volume)}% bought"),
                Triple("Traders", Fmt.count(traders.toLong()), if (traders > 0) String.format(java.util.Locale.US, "%.1f trades each", trades / traders) else Fmt.DASH),
                Triple("New coins", Fmt.count(launched.toLong()), "Dev sold on ${pctOf(devSold, launched)}%"),
            )
            for (pair in tiles.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((label, value, sub) in pair) {
                        Card(Modifier.weight(1f)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = text(13, color = Ink.muted))
                                Text(value, style = figure(24, FontWeight.SemiBold))
                                Text(sub, style = text(13, color = Ink.text2))
                            }
                        }
                    }
                }
            }
            val bars = sumBars(cs)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text("Trades over ${model.window.words}", style = text(15, FontWeight.Bold), modifier = Modifier.semantics { heading() })
                        Text(stepWords(r.step), style = text(13, color = Ink.muted))
                    }
                    val top = bars.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .semantics { contentDescription = "Trades over ${model.window.words}, ${bars.size} bars, ${Fmt.count(trades.toLong())} in all" },
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        bars.forEachIndexed { i, b ->
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight((b / top).coerceIn(0.02, 1.0).toFloat())
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(if (i == bars.lastIndex) Ink.greenDim else Ink.green),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(model.window.ago, style = text(12, color = Ink.muted))
                        Text("now", style = text(12, color = Ink.muted))
                    }
                }
            }
            Card {
                Text("By chain", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp).semantics { heading() })
                for ((i, c) in cs.sortedByDescending { it.volume }.withIndex()) {
                    val chain = Chain.of(c.chain.toString()) ?: continue
                    if (i > 0) Divider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChainDot(chain, 10.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(chain.title, style = text(15, FontWeight.Bold))
                            Text(
                                "${Fmt.count(c.launched)} launched" + if (c.devSold > 0 && c.launched > 0) " · dev sold on ${pctOf(c.devSold.toDouble(), c.launched.toDouble())}%" else "",
                                style = text(12, color = Ink.muted),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(Fmt.usd(c.volume), style = figure(17, FontWeight.SemiBold))
                            Text(Fmt.counted(c.trades, "trade"), style = text(12, color = Ink.muted))
                        }
                    }
                }
            }
            Text(
                "Counted from trades on launchpad curves. Solana is not counted yet. A wallet that trades on two chains counts once on each.",
                style = text(13, color = Ink.muted, lineHeight = 18.sp),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
