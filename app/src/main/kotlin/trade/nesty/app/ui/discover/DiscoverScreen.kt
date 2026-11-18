package trade.nesty.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.ui.ChainChips
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.rememberCoinImage
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.menu.oneBalance
import trade.nesty.app.ui.StickToTop
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LaneTabs
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalRoot
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TabHeader
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.uniqueItems
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.QuickTrade

@Composable
fun DiscoverScreen() {
    val model = LocalRoot.current.discover
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val settings = AppGraph.settings
    WhileShown { model.live() }

    val rows by remember { derivedStateOf { model.rows() } }
    val loaded by remember { derivedStateOf { model.isLoaded() } }
    val now = rememberNow()
    val list = rememberLazyListState()
    StickToTop(list, rows.firstOrNull()?.let { shareKey(it.chain, it.address) })
    LaunchedEffect(model.lane, model.chain) { list.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TabHeader("Discover", onMenu = { nav.push(Route.Menu()) }) {
            IconButton48(NIcons.search, "Search coins or paste an address") { nav.push(Route.Search) }
        }
        LaneTabs(Lane.entries.map { it.label }, model.lane.ordinal, { model.lane = Lane.entries[it] })
        ChainChips(Chain.entries, model.chain, { model.chain = it })
        Divider()
        Row(
            Modifier.fillMaxWidth().height(44.dp).background(Ink.card).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Quick buy", style = text(13, color = Ink.muted))
                Row(
                    Modifier
                        .height(44.dp)
                        .clickable(onClickLabel = "Change the quick buy amount", role = Role.Button) { settings.nextQuickBuy() }
                        .semantics { contentDescription = "Quick buy amount $${settings.quickBuy}, tap to change it" }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("$${settings.quickBuy}", style = text(15, FontWeight.Bold, Ink.green))
                    Icon(NIcons.chevronDown, contentDescription = null, tint = Ink.green, modifier = Modifier.size(14.dp))
                }
            }
            Row(
                Modifier.height(44.dp).clickable(role = Role.Button) { nav.tab(Route.Portfolio) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("One Balance", style = text(13, color = Ink.muted))
                Text(oneBalance(), style = text(15, FontWeight.SemiBold))
            }
        }
        Divider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                rows.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                    uniqueItems(rows, { shareKey(it.chain, it.address) }) { r ->
                        DiscoverRowView(
                            r,
                            lane = model.lane,
                            now = now,
                            quick = settings.quickBuy,
                            onOpen = { nav.push(Route.Token(r.chain, r.address)) },
                            onBuy = { QuickTrade.buy(toasts, r.chain, r.address, settings.quickBuy.toDouble(), r.symbol) },
                        )
                    }
                }
                !loaded && model.failed.isEmpty() -> EmptyNote("Loading…")
                !loaded -> EmptyNote("Could not reach Nesty. Trying again.")
                else -> EmptyNote("Nothing on ${model.chain?.title ?: "any chain"} in this list right now.")
            }
        }
    }
}

@Composable
private fun DiscoverRowView(r: DiscoverRow, lane: Lane, now: Long, quick: Int, onOpen: () -> Unit, onBuy: () -> Unit) {
    val line2 = buildList {
        if (lane == Lane.NEAR && r.curve != null) add("Curve ${(r.curve * 100).toInt()}%")
        r.volume?.let { add("V ${Fmt.usd(it)}") }
        if (lane != Lane.NEAR || r.curve == null) r.holders?.let { add(Fmt.counted(it.toLong(), "holder")) }
    }.joinToString(" · ")

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 80.dp)
                    .clickable(onClickLabel = "Open ${r.symbol}", role = Role.Button, onClick = onOpen)
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinAvatar(rememberCoinImage(r.chain, r.address, r.image, r.served), r.symbol, r.address, r.chain, 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            r.symbol,
                            style = text(16, FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "${r.symbol} on ${r.chain.title}" },
                        )
                        if (r.since != null && r.since > 0) Text(Fmt.age(now - r.since), style = text(13, color = Ink.muted), maxLines = 1)
                    }
                    if (line2.isNotEmpty()) {
                        Text(line2, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    r.flag?.let { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToneMark(f.tone, 18.dp)
                            Text(f.text, style = text(13, FontWeight.SemiBold, Ink.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Fmt.usd(r.mcap), style = figure(19, FontWeight.SemiBold, Ink.green), maxLines = 1)
                    if (r.change != null) {
                        Text(Fmt.pctChange(r.change), style = text(13, FontWeight.Bold, pctColor(r.change)), maxLines = 1)
                    }
                }
            }
            Box(
                Modifier
                    .widthIn(min = 60.dp)
                    .height(44.dp)
                    .background(Ink.greenDeep, RoundedCornerShape(12.dp))
                    .border(1.dp, Ink.greenLine, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Buy $$quick of ${r.symbol}", role = Role.Button, onClick = onBuy),
                contentAlignment = Alignment.Center,
            ) {
                Text("$$quick", style = text(15, FontWeight.Bold, Ink.green))
            }
        }
        Divider(Modifier.align(Alignment.BottomStart))
        if (r.curve != null && r.curve > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(r.curve.coerceIn(0.0, 1.0).toFloat())
                    .height(2.dp)
                    .background(Ink.green.copy(alpha = 0.55f)),
            )
        }
    }
}
