package trade.nesty.app.ui.token

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Trade
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Check
import trade.nesty.app.ui.Checks
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LiveMark
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.Meter
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NavRow
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.Segmented
import trade.nesty.app.ui.Stat
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneChip
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberEntryModel
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signal.SignalHead
import trade.nesty.app.ui.signal.SignalWalletsSheet
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.TradeBar
import trade.nesty.app.ui.venueName

const val TOKEN_MODEL = "token"

@Composable
fun rememberTokenModel(chain: Chain, address: String): TokenModel {
    val nav = LocalNavigator.current
    // The Trades and holders page shares the model of the token page under it.
    val under = remember(chain, address) {
        nav.stack.lastOrNull { it.route == Route.Token(chain, address) }?.models?.get(TOKEN_MODEL) as? TokenModel
    }
    return under ?: rememberEntryModel(TOKEN_MODEL) { TokenModel(AppGraph.api, chain, address) }
}

fun copyText(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
fun TokenScreen(chain: Chain, address: String, openBuy: Boolean = false) {
    val model = rememberTokenModel(chain, address)
    val nav = LocalNavigator.current
    val settings = AppGraph.settings
    WhileShown { model.live(withChart = true) }
    val coin = model.coin
    val now = rememberNow()

    Column(Modifier.fillMaxSize()) {
        TokenHeader(coin, chain, address, now, onBack = { nav.pop() })
        when {
            coin != null -> {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                var checksY by remember { mutableFloatStateOf(0f) }
                val holderList = model.holders?.list?.takeIf { model.holdersServed == true }
                val checks = Checks.of(coin, model.share, holderList, chain == Chain.SOLANA, followed = model.holdersServed != false)
                val followedTrades = followedTrades(model.trades, settings::nameOf)
                // The signal wallets sheet; kept open across a visit to one of its wallets.
                val head = model.signalHead
                var signalOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(head == null) { if (head == null) signalOpen = false }
                // The all-time high, in dollars per token, once it is worth showing.
                val ath = Ath.shown(model.ath, model.supply ?: coin.supply, model.firstCandleAt, now / 1000)

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Market(coin, model, checks, ath, onChecks = { scope.launch { scroll.animateScrollTo(checksY.toInt()) } }) {
                        signalOpen = true
                    }
                    ChartSection(model, coin, ath)
                    Box(Modifier.onGloballyPositioned { checksY = it.positionInParent().y }) { ChecksCard(checks) }
                    if (head != null || followedTrades.isNotEmpty()) {
                        WalletsCard(model, head, followedTrades, now) { signalOpen = true }
                    }
                    Card {
                        val note = listOfNotNull(
                            model.traders?.trades?.let { Fmt.counted(it.toLong(), "trade") },
                            model.holderCount?.let { Fmt.counted(it.toLong(), "holder") },
                        ).joinToString(" · ")
                        NavRow("Trades and holders", note.ifEmpty { null }, onClick = { nav.push(Route.TradesHolders(chain, address)) })
                    }
                }
                TradeBar(chain, address, coin, checks, onChecks = { scope.launch { scroll.animateScrollTo(checksY.toInt()) } }, openBuy = openBuy)
                if (signalOpen && head != null) {
                    SignalWalletsSheet(
                        chain = chain,
                        symbol = coin.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(address),
                        head = head,
                        // A coin the server sums no trades of still lists the signal's wallets.
                        answer = model.traders ?: TradersAnswer().takeIf { model.tradersPhase == trade.nesty.app.ui.token.Phase.EMPTY },
                        failed = model.tradersPhase == trade.nesty.app.ui.token.Phase.FAILED,
                        supply = model.supply ?: coin.supply,
                        onClose = { signalOpen = false },
                    )
                }
            }
            model.coinPhase == trade.nesty.app.ui.token.Phase.EMPTY ->
                Box(Modifier.weight(1f).navigationBarsPadding()) { EmptyNote("Nesty does not know this coin on ${chain.title}.") }
            model.coinPhase == trade.nesty.app.ui.token.Phase.FAILED ->
                Box(Modifier.weight(1f).navigationBarsPadding()) { EmptyNote("Could not reach Nesty. Trying again.") }
            else -> Box(Modifier.weight(1f).navigationBarsPadding()) { EmptyNote("Loading…") }
        }
    }
}

data class FollowedTrade(val name: String, val trade: Trade)

/** The newest trade of each followed wallet in this coin. */
fun followedTrades(trades: List<Trade>, nameOf: (String?) -> String?): List<FollowedTrade> {
    val seen = HashSet<String>()
    val out = ArrayList<FollowedTrade>()
    for (t in trades) {
        val w = t.wallet ?: continue
        val name = nameOf(w) ?: continue
        if (seen.add(w.lowercase())) out += FollowedTrade(name, t)
        if (out.size >= 3) break
    }
    return out
}

@Composable
private fun TokenHeader(coin: Coin?, chain: Chain, address: String, now: Long, onBack: () -> Unit) {
    val settings = AppGraph.settings
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    val symbol = coin?.symbol?.takeIf { it.isNotBlank() } ?: coin?.name ?: Fmt.address(address)
    val watched = settings.isWatched(chain, address)

    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton48(NIcons.arrowBack, "Back", Ink.text, onClick = onBack)
        CoinAvatar(coin?.image, symbol, address, chain, 38.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(symbol, style = text(20, FontWeight.Bold, lineHeight = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            val sub = listOfNotNull(
                coin?.launchpad ?: venueName(coin?.pool?.dex),
                chain.title,
                coin?.createdAt?.let { Fmt.ageSince(it, now) },
            ).joinToString(" · ")
            Text(sub, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable(onClickLabel = "Watchlist", role = Role.Switch) { settings.toggleWatch(chain, address) }
                .semantics {
                    contentDescription = "Watchlist"
                    stateDescription = if (watched) "On the watchlist" else "Not on the watchlist"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (watched) NIcons.starFilled else NIcons.star, contentDescription = null, tint = if (watched) Ink.violet else Ink.text2, modifier = Modifier.size(21.dp))
        }
        Box {
            IconButton48(NIcons.more, "Links and address", size = 20.dp) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Ink.raised) {
                DropdownMenuItem(
                    text = { MenuText("Copy address", Fmt.address(address)) },
                    onClick = {
                        menu = false
                        copyText(context, "Address", address)
                        toasts.show("Address copied", Fmt.address(address))
                    },
                )
                DropdownMenuItem(text = { MenuText("Open in explorer") }, onClick = {
                    menu = false
                    runCatching { uri.openUri(chain.tokenUrl(address)) }
                })
                coin?.links?.x?.takeIf { it.startsWith("http") }?.let { x ->
                    DropdownMenuItem(text = { MenuText("X") }, onClick = { menu = false; runCatching { uri.openUri(x) } })
                }
                coin?.links?.telegram?.takeIf { it.startsWith("http") }?.let { t ->
                    DropdownMenuItem(text = { MenuText("Telegram") }, onClick = { menu = false; runCatching { uri.openUri(t) } })
                }
                coin?.links?.website?.takeIf { it.startsWith("http") }?.let { w ->
                    DropdownMenuItem(text = { MenuText("Website") }, onClick = { menu = false; runCatching { uri.openUri(w) } })
                }
            }
        }
    }
}

@Composable
private fun MenuText(title: String, note: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = text(15, FontWeight.SemiBold))
        if (note != null) Text(note, style = text(13, color = Ink.muted))
    }
}

@Composable
private fun Market(coin: Coin, model: TokenModel, checks: List<Check>, ath: Double?, onChecks: () -> Unit, onWallets: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Fmt.usd(coin.mcapUsd), style = figure(44, FontWeight.SemiBold, Ink.green).copy(lineHeight = 44.sp))
            val m5 = coin.change?.m5
            if (m5 != null) Text(Fmt.pctChange(m5), style = text(17, FontWeight.Bold, pctColor(m5)), modifier = Modifier.padding(bottom = 4.dp))
            Text(if (m5 != null) "market cap, 5m" else "market cap", style = text(13, color = Ink.muted), modifier = Modifier.padding(bottom = 5.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Its all-time high right after its market cap: a market cap where the supply is known, else a price.
            if (ath != null) {
                val supply = model.supply ?: coin.supply
                Stat("ATH", if (supply != null && supply > 0) Fmt.usd(ath * supply) else "$" + Fmt.amount(ath), Modifier.weight(1f))
            }
            Stat("Liquidity", Fmt.usd(coin.liquidityUsd), Modifier.weight(1f))
            Stat("Volume 5m", Fmt.usd(coin.volume5), Modifier.weight(1f))
            Stat("Holders", model.holderCount?.let { Fmt.count(it.toLong()) } ?: Fmt.DASH, Modifier.weight(1f))
        }
        val progress = coin.progress
        if (coin.onCurve && progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Meter(progress, Modifier.weight(1f))
                Text("Bonding curve ${(progress * 100).toInt()}%", style = text(13, FontWeight.SemiBold, Ink.text2))
            }
        }
        val bad = checks.count { it.tone == Tone.BAD }
        val warn = checks.count { it.tone == Tone.WARN }
        val ok = checks.count { it.tone == Tone.OK }
        val signal = model.signalHead
        if (bad + warn + ok > 0 || signal != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bad > 0) ToneChip(Tone.BAD, if (bad == 1) "1 red flag" else "$bad red flags", onClick = onChecks)
                if (warn > 0) ToneChip(Tone.WARN, "$warn to watch", onClick = onChecks)
                if (signal != null) ToneChip(Tone.WALLETS, "${signal.size} proven wallets", does = "See what these wallets did", onClick = onWallets)
                if (ok > 0) ToneChip(Tone.OK, "$ok fine", onClick = onChecks)
            }
        }
    }
}

@Composable
private fun ChartSection(model: TokenModel, coin: Coin, ath: Double?) {
    val candles = model.candles()
    val supply = model.supply ?: coin.supply
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Segmented(Frame.entries.map { it.label }, model.frame.ordinal, { model.frame = Frame.entries[it] })
            if (model.live) LiveMark(Modifier.padding(end = 4.dp))
        }
        val symbol = coin.symbol ?: "This coin"
        when {
            candles.isNotEmpty() && supply != null && supply > 0 -> {
                val from = candles.takeLast(36).first().open * supply
                val to = candles.last().close * supply
                // Each length starts at its newest candles.
                val view = remember(model.frame) { ChartView() }
                CandleChart(
                    candles,
                    supply,
                    "$symbol market cap in ${model.frame.words} candles, from about ${Fmt.usd(from)} to ${Fmt.usd(to)}" +
                        (ath?.let { ", all-time high ${Fmt.usd(it * supply)}" } ?: ""),
                    view,
                    ath = ath,
                )
            }
            model.chartPhase == Phase.LOADING -> ChartNote("Loading the chart…")
            model.chartPhase == Phase.FAILED -> ChartNote("The chart could not be read. Trying again.")
            else -> ChartNote("No trades in this length yet.")
        }
    }
}

@Composable
private fun ChartNote(message: String) {
    Box(Modifier.fillMaxWidth().height(176.dp), contentAlignment = Alignment.Center) {
        Text(message, style = text(14, color = Ink.muted), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChecksCard(checks: List<Check>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val bad = checks.count { it.tone == Tone.BAD }
    val warn = checks.count { it.tone == Tone.WARN }
    val summary = listOf(
        if (bad == 0) "No red flags" else if (bad == 1) "1 red flag" else "$bad red flags",
    ).plus(if (warn > 0) listOf("$warn to watch") else emptyList()).joinToString(" · ")
    val shown = if (open) checks else checks.take(3)
    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Checks", style = text(16, FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Text(summary, style = text(13, color = Ink.muted))
        }
        for (c in shown) {
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToneMark(c.tone, 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        c.label,
                        style = text(14, FontWeight.SemiBold),
                        modifier = Modifier.semantics { if (c.tone.word.isNotEmpty()) stateDescription = c.tone.word },
                    )
                    if (c.sub.isNotEmpty()) Text(c.sub, style = text(12, color = Ink.muted))
                }
                Text(c.value, style = figure(16, FontWeight.SemiBold, c.tone.value), textAlign = TextAlign.End, maxLines = 1)
            }
        }
        if (checks.size > 3) {
            val rest = checks.drop(3)
            val restOk = rest.count { it.tone == Tone.OK }
            val restNone = rest.count { it.tone == Tone.NONE }
            val restOther = rest.size - restOk - restNone
            val moreText = if (open) "All ${checks.size} checks" else listOfNotNull(
                if (restOther > 0) "$restOther more" else null,
                if (restOk > 0) "$restOk more fine" else null,
                if (restNone > 0) "$restNone not read yet" else null,
            ).joinToString(", ")
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(moreText, style = text(14, color = Ink.text2))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (open) "Show less" else "Show all", style = text(14, FontWeight.Bold, Ink.green))
                    Icon(if (open) NIcons.chevronUp else NIcons.chevronDown, contentDescription = null, tint = Ink.green, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** The share of a signal rule's signals that did something, as a whole percent. */
private fun pctOf(part: Int, n: Int) = if (n <= 0) "0%" else "${Math.round(part * 100.0 / n)}%"

@Composable
private fun WalletsCard(model: TokenModel, signal: SignalHead?, followed: List<FollowedTrade>, now: Long, onSignal: () -> Unit) {
    val nav = LocalNavigator.current
    Card {
        Text("Wallets in it", style = text(16, FontWeight.Bold), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
        if (signal != null) {
            val record = model.record
            val key = listOf(5, 3, 2).firstOrNull { signal.size >= it }?.toString()
            val figures = key?.let { record?.sizes?.get(it) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "See what these wallets did", role = Role.Button, onClick = onSignal)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToneMark(Tone.WALLETS, 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val within = (signal.at - signal.first).coerceAtLeast(0)
                    Text(
                        "${signal.size} proven wallets bought" + if (within >= 1_000) " within ${Fmt.duration(within.toDouble())}" else "",
                        style = text(14, FontWeight.Bold),
                    )
                    if (figures != null && figures.n > 0 && model.recordDays > 0) {
                        Text(
                            "Of signals like this in the last ${model.recordDays} days, ${pctOf(figures.x2, figures.n)} reached 2× and ${pctOf(figures.half, figures.n)} fell by half.",
                            style = text(13, color = Ink.text2, lineHeight = 18.sp),
                        )
                    }
                }
                Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            }
        }
        for (f in followed) {
            val t = f.trade
            val wallet = t.wallet ?: continue
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { nav.push(Route.Wallet(model.chain, wallet)) }
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(NIcons.eye, contentDescription = null, tint = Ink.violet, modifier = Modifier.size(22.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(f.name) }
                        withStyle(SpanStyle(color = Ink.text2)) {
                            append(if (t.isBuy) " bought " else " sold ")
                            append(t.usd?.let { Fmt.usdFull(it) } ?: "some")
                            append(", ${Fmt.age(now - t.at)} ago")
                        }
                    },
                    style = text(14),
                    modifier = Modifier.weight(1f),
                )
                Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}
