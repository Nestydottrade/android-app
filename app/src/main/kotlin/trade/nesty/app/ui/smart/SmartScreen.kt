package trade.nesty.app.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.BoardRow
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Settings
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.Checks
import trade.nesty.app.ui.Chip
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LaneTabs
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalRoot
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TabHeader
import trade.nesty.app.ui.TagChip
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneChip
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.Toasts
import trade.nesty.app.ui.WalletTag
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.discover.noTrading
import trade.nesty.app.ui.rememberEntryModel
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signal.CoinTraders
import trade.nesty.app.ui.signal.SignalWallets
import trade.nesty.app.ui.signal.SignalWalletsSheet
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

/** The site's figure for a signal without a known supply: most launchpad coins have a billion. */
private const val SUPPLY = 1e9
private const val LIVE_MS = 60 * 60_000L

@Composable
fun SmartScreen(leaderboard: Boolean) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        TabHeader("Smart money", onMenu = { nav.push(Route.Menu) })
        LaneTabs(listOf("Signals", "Leaderboard"), if (leaderboard) 1 else 0, { nav.tab(Route.Smart(leaderboard = it == 1)) }, Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (leaderboard) LeadersTab() else SignalsTab()
        }
    }
}

/** A chip with a small chain dot, as the Smart money boards draw them. */
@Composable
private fun ChainChipRow(chains: List<Chain>, selected: Chain?, withAll: Boolean, onPick: (Chain?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (withAll) Chip("All", selected == null, { onPick(null) })
        for (c in chains) Chip(c.title, selected == c, { onPick(c) }, leading = { ChainDot(c, 8.dp) })
    }
}

@Composable
private fun SignalsTab() {
    val model = LocalRoot.current.signals
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    WhileShown { model.live() }
    val now = rememberNow()
    val all = model.signals()
    val live = all.filter { now - it.row.at < LIVE_MS }
    val past = all.filter { now - it.row.at >= LIVE_MS }
    val chain = model.chain
    // The signal wallets sheet of the signal tapped; kept open across a visit to one of its wallets.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openKey?.let { key -> all.firstOrNull { signalKey(it) == key } }
    if (open != null) SignalSheet(open) { openKey = null }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A signal is when proven wallets buy the same coin within minutes. Proven wallets are picked by their past launchpad trades.",
                    style = text(14, color = Ink.text2, lineHeight = 20.sp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("At least", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.width(64.dp))
                    for (n in listOf(2, 3, 5)) {
                        val on = model.need == n
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) Ink.violetBg else Ink.raised)
                                .border(1.dp, if (on) Ink.violet else Ink.line2, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { model.need = n }
                                .semantics { selected = on },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$n wallets", style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.text2))
                        }
                    }
                }
                ChainChipRow(SMART_CHAINS, chain, withAll = true) { model.chain = it }
            }
        }
        item { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) { RecordCard(model) } }
        if (chain != null && model.proven(chain) == 0) {
            item {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No proven wallets on ${chain.title} yet", style = text(16, FontWeight.Bold))
                            Text(
                                "Signals start here once wallets on ${chain.title} build a record of launchpad trades.",
                                style = text(13, color = Ink.muted, lineHeight = 18.sp),
                            )
                        }
                    }
                }
            }
        }
        if (live.isNotEmpty()) {
            item { SectionTitle("Live now", null) }
            items(live, key = { "live:" + it.chain.feed + it.row.token + it.row.at }) { s ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                    LiveSignalCard(
                        s, model, now,
                        onOpen = { nav.push(Route.Token(s.chain, s.row.token)) },
                        onWallets = { openKey = signalKey(s) },
                        onBuy = { toasts.noTrading() },
                    )
                }
            }
        }
        if (past.isNotEmpty()) {
            item { SectionTitle("Earlier today", "How each one went, good or bad") }
            items(past, key = { "past:" + it.chain.feed + it.row.token + it.row.at }) { s ->
                PastSignalRow(s, now, onWallets = { openKey = signalKey(s) }) { nav.push(Route.Token(s.chain, s.row.token)) }
            }
        }
        if (all.isEmpty() && (chain == null || model.proven(chain) != 0)) {
            item {
                EmptyNote(
                    when {
                        !model.loaded && model.failed -> "Could not reach Nesty. Trying again."
                        !model.loaded -> "Loading…"
                        else -> "No signal of ${model.need} or more wallets in the last day."
                    },
                )
            }
        }
        item {
            Text(
                "Signals cover ${SMART_CHAINS.joinToString(", ") { it.title }}. Solana signals are not ready yet.",
                style = text(13, color = Ink.muted, lineHeight = 18.sp),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun signalKey(s: ChainSignal) = "${s.chain.feed}:${s.row.token}:${s.row.at}"

/** The sheet of one signal: it reads the coin's trader tables itself, as no token page is open under it. */
@Composable
private fun SignalSheet(s: ChainSignal, onClose: () -> Unit) {
    val r = s.row
    val traders = rememberEntryModel("signal-sheet:" + signalKey(s)) { CoinTraders(AppGraph.api, s.chain, r.token, r.supply) }
    WhileShown(traders) { traders.live() }
    SignalWalletsSheet(
        chain = s.chain,
        symbol = r.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(r.token),
        head = SignalWallets.head(r),
        answer = traders.answer,
        failed = traders.failed,
        supply = traders.supply,
        onClose = onClose,
    )
}

@Composable
private fun SectionTitle(title: String, sub: String?) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = text(16, FontWeight.Bold), modifier = Modifier.semantics { heading() })
        if (sub != null) Text(sub, style = text(13, color = Ink.muted))
    }
}

private fun pct(part: Int, n: Int): Double = if (n <= 0) 0.0 else part * 100.0 / n

private fun pctText(v: Double): String = if (v >= 10 || v == Math.floor(v)) "${Math.round(v)}%" else String.format(java.util.Locale.US, "%.1f%%", v)

@Composable
private fun RecordCard(model: SignalsModel) {
    val view = model.recordView()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Track record", style = text(16, FontWeight.Bold), modifier = Modifier.semantics { heading() })
                if (view != null) {
                    Text(
                        "${Fmt.counted(view.signals.n.toLong(), "signal")} · ${view.days} days · ${model.chain?.title ?: "all chains"}",
                        style = text(12, color = Ink.muted),
                    )
                }
            }
            if (view == null || view.signals.n == 0) {
                Text(if (model.record == null) "Loading…" else "No signal of this size yet.", style = text(13, color = Ink.muted))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(Ink.barSignals, "Signals")
                Legend(Ink.barAny, "Any 3 wallets")
            }
            val s = view.signals
            val a = view.any
            val metrics = listOf(
                Triple("Reached 2×", pct(s.x2, s.n), pct(a.x2, a.n)),
                Triple("Fell by half", pct(s.half, s.n), pct(a.half, a.n)),
            )
            val top = maxOf(30.0, metrics.maxOf { maxOf(it.second, it.third) })
            for ((label, sv, av) in metrics) {
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "$label: signals ${pctText(sv)}, any 3 wallets ${pctText(av)}"
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(label, style = text(14, FontWeight.SemiBold))
                    RecordBar(sv / top, Ink.barSignals, pctText(sv), Ink.text)
                    RecordBar(av / top, Ink.barAny, pctText(av), Ink.text2)
                }
            }
            Divider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Typical signal a day later", style = text(12, color = Ink.muted))
                    val m = s.median
                    Text(m?.let { Fmt.times(it) } ?: Fmt.DASH, style = figure(20, FontWeight.SemiBold, if (m != null && m < 1) Ink.pinkSoft else Ink.green))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Went 5× or more", style = text(12, color = Ink.muted))
                    Text(Fmt.count(s.x5.toLong()), style = figure(20, FontWeight.SemiBold))
                }
            }
            val bothWays = pct(s.x2, s.n) > pct(a.x2, a.n) && pct(s.half, s.n) > pct(a.half, a.n)
            val lower = (s.median ?: 1.0) < 1
            val sentence = listOfNotNull(
                if (bothWays) "More big moves both ways than other coins." else null,
                if (lower) "Most signals are lower a day later, so size each buy for a loss." else null,
            ).joinToString(" ")
            if (sentence.isNotEmpty()) Text(sentence, style = text(13, color = Ink.text2, lineHeight = 18.sp))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = text(12, color = Ink.text2))
    }
}

@Composable
private fun RecordBar(fill: Double, color: Color, value: String, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(12.dp)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.coerceIn(0.01, 1.0).toFloat())
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(color),
            )
        }
        Text(value, style = figure(16, FontWeight.SemiBold, ink), modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun LiveSignalCard(s: ChainSignal, model: SignalsModel, now: Long, onOpen: () -> Unit, onWallets: () -> Unit, onBuy: () -> Unit) {
    val r = s.row
    val symbol = r.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(r.token)
    val supply = r.supply ?: SUPPLY
    val then = r.usdThen?.let { it * supply }
    val nowMc = r.usdNow?.let { it * supply }
    val change = if (then != null && nowMc != null && then > 0) (nowMc / then - 1) * 100 else null
    val within = (r.at - r.first).coerceAtLeast(0)
    val risk = Checks.flag(null, model.shareOf(s.chain, r.token), null, null)?.takeIf { it.tone == Tone.WARN || it.tone == Tone.BAD }
    val quick = AppGraph.settings.quickBuy
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.card)
            .border(1.dp, Ink.line, RoundedCornerShape(16.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open $symbol", role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The buy button sits over the end of this row; the rows under it take the whole width.
            Row(Modifier.padding(end = 70.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoinAvatar(null, symbol, r.token, s.chain, 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(symbol, style = text(16, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(listOfNotNull(s.chain.title, r.launched?.let { Fmt.age(now - it) }).joinToString(" · "), style = text(12, color = Ink.muted), maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MC at signal", style = text(13, color = Ink.muted), maxLines = 1)
                        Text("${Fmt.usd(then)} → ${Fmt.usd(nowMc)}", style = figure(15, FontWeight.SemiBold), maxLines = 1)
                        if (change != null) Text(Fmt.pctChange(change), style = text(13, FontWeight.Bold, pctColor(change)), maxLines = 1)
                    }
                }
            }
            // The chip opens what each of these wallets did in the coin; the rest of the card opens the coin.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToneChip(Tone.WALLETS, "${r.size} proven wallets", compact = true, does = "See what these wallets did in $symbol", onClick = onWallets)
                Text(
                    (if (within >= 1_000) "in ${Fmt.duration(within.toDouble())} · " else "") + "${Fmt.age(now - r.at)} ago",
                    style = text(13, color = Ink.text2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (risk != null) {
                Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToneMark(risk.tone, 16.dp)
                    Text(risk.text, style = text(13, FontWeight.SemiBold, Ink.text2))
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 12.dp)
                .width(64.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink.greenDeep)
                .border(1.dp, Ink.greenLine, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Buy $$quick of $symbol", role = Role.Button, onClick = onBuy),
            contentAlignment = Alignment.Center,
        ) {
            Text("$$quick", style = text(15, FontWeight.Bold, Ink.green))
        }
    }
}

@Composable
private fun PastSignalRow(s: ChainSignal, now: Long, onWallets: () -> Unit, onOpen: () -> Unit) {
    val r = s.row
    val symbol = r.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(r.token)
    val supply = r.supply ?: SUPPLY
    val then = r.usdThen?.let { it * supply }
    val nowMc = r.usdNow?.let { it * supply }
    val x = if (r.usdThen != null && r.usdNow != null && r.usdThen > 0) r.usdNow / r.usdThen else null
    val (tone, outcome) = when {
        x == null -> Tone.NONE to "Not priced"
        x >= 2 -> Tone.OK to "Reached 2×"
        x <= 0.5 -> Tone.BAD to "Fell by half"
        else -> Tone.INFO to "Neither yet"
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .clickable(onClickLabel = "Open $symbol", role = Role.Button, onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoinAvatar(null, symbol, r.token, s.chain, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(symbol, style = text(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("${s.chain.title} · ${Fmt.age(now - r.at)}", style = text(12, color = Ink.muted), maxLines = 1)
                }
                // The chip opens what each of these wallets did in the coin; the rest of the row opens the coin.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneChip(Tone.WALLETS, "${r.size} wallets", compact = true, does = "See what these wallets did in $symbol", onClick = onWallets)
                    Text("MC ${Fmt.usd(then)} → ${Fmt.usd(nowMc)}", style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToneMark(tone, 16.dp)
                    Text(outcome, style = text(13, FontWeight.SemiBold))
                }
                if (x != null) Text("Now ${Fmt.times(x)}", style = text(12, color = Ink.muted))
            }
        }
        Divider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun LeadersTab() {
    val model = LocalRoot.current.leaders
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val settings = AppGraph.settings
    WhileShown { model.live() }
    val rows = model.rows()
    val board = model.board()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChainChipRow(SMART_CHAINS, model.chain, withAll = false) { it?.let { c -> model.chain = c } }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.raised).padding(2.dp)) {
                        for (p in Period.entries) {
                            val on = model.period == p
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (on) Ink.violetBg else Color.Transparent)
                                    .clickable(role = Role.Button) { model.period = p }
                                    .semantics { selected = on },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(p.label, style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.muted))
                            }
                        }
                    }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (o in Sort.entries) Chip(o.label, model.sort == o, { model.sort = o })
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Top wallets on ${model.chain.title}", style = text(16, FontWeight.Bold), modifier = Modifier.semantics { heading() })
                Text("By ${model.sort.label.lowercase()}, ${model.period.words}", style = text(12, color = Ink.muted))
            }
        }
        when {
            rows.isNotEmpty() -> items(rows, key = { it.wallet }) { r ->
                val i = rows.indexOf(r)
                LeaderRow(i + 1, r, model.sort, settings) { nav.push(Route.Wallet(model.chain, r.wallet)) }
            }
            board != null -> item { EmptyNote("No wallet made a profit on ${model.chain.title} in the ${model.period.words}.") }
            model.failed -> item { EmptyNote("Could not reach Nesty. Trying again.") }
            else -> item { EmptyNote("Loading…") }
        }
        item {
            Box(Modifier.padding(16.dp)) {
                Card {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Only trades on launchpad curves count. Cluster wallets are left out.", style = text(13, color = Ink.text2, lineHeight = 18.sp))
                        Text(
                            "Sniper buys in the first seconds. Flipper sells within minutes. Holder keeps coins for hours. A dash means nothing was sold yet.",
                            style = text(13, color = Ink.muted, lineHeight = 18.sp),
                        )
                    }
                }
            }
        }
    }
}

/** Follows a wallet or stops, saying so. */
fun toggleFollow(settings: Settings, toasts: Toasts, wallet: String) {
    val label = settings.nameOf(wallet) ?: Fmt.address(wallet)
    if (settings.isFollowed(wallet)) {
        settings.unfollow(wallet)
        toasts.show("Stopped following $label", "Removed from your Tracker")
    } else if (settings.follow(wallet)) {
        toasts.show("Following $label", "Their trades show in Tracker · ${settings.followed.size} of ${Settings.MAX_FOLLOWED}")
    } else {
        toasts.show("You follow ${Settings.MAX_FOLLOWED} wallets already", "Stop following one in Tracker first")
    }
}

/** The round switch that follows a wallet or stops: on a Leaderboard row and on a signal wallet's card. */
@Composable
fun FollowButton(wallet: String) {
    val settings = AppGraph.settings
    val toasts = LocalToasts.current
    val following = settings.isFollowed(wallet)
    val label = settings.nameOf(wallet) ?: Fmt.address(wallet)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (following) Ink.greenDeep else Ink.raised)
            .border(1.dp, if (following) Ink.greenLine else Ink.line3, CircleShape)
            .clickable(role = Role.Switch) { toggleFollow(settings, toasts, wallet) }
            .semantics {
                contentDescription = if (following) "Following $label, tap to stop" else "Follow $label"
                stateDescription = if (following) "Following" else "Not following"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (following) NIcons.check else NIcons.plus, contentDescription = null, tint = if (following) Ink.green else Ink.text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LeaderRow(rank: Int, r: BoardRow, sort: Sort, settings: Settings, onOpen: () -> Unit) {
    val name = settings.nameOf(r.wallet)
    val roi = r.roi()
    val win = r.winRate()
    val winText = win?.let { "Win ${Math.round(it * 100)}%" } ?: "Win ${Fmt.DASH}"
    val coinsText = if (r.coins == 1) "1 coin" else "${r.coins} coins"
    val profit = Fmt.usdSigned(r.profit)
    val roiText = roi?.let { Fmt.pctChange(it * 100) } ?: Fmt.DASH
    val (main, mainInk, second) = when (sort) {
        Sort.PROFIT -> Triple(profit, signColor(r.profit), "ROI $roiText")
        Sort.ROI -> Triple(roiText, signColor(roi), profit)
        Sort.WIN -> Triple(win?.let { "${Math.round(it * 100)}%" } ?: Fmt.DASH, Ink.text, coinsText)
        Sort.VOLUME -> Triple(Fmt.usd(r.volume), Ink.text, profit)
    }
    val sub = listOfNotNull(winText, coinsText, if (sort == Sort.VOLUME) null else "Vol ${Fmt.usd(r.volume)}").joinToString(" · ")
    Column {
        Divider(Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 68.dp).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$rank", style = figure(14, FontWeight.SemiBold, Ink.muted), modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (name != null) "$name · ${Fmt.address(r.wallet)}" else Fmt.address(r.wallet),
                            style = text(15, if (name != null) FontWeight.Bold else FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        WalletTag.of(r.tag)?.let { TagChip(it) }
                    }
                    Text(sub, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(main, style = figure(17, FontWeight.SemiBold, mainInk), maxLines = 1)
                    Text(second, style = text(12, color = Ink.muted), maxLines = 1)
                }
            }
            FollowButton(r.wallet)
        }
    }
}

