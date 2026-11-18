package trade.nesty.app.ui.holders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Holder
import trade.nesty.app.data.Trade
import trade.nesty.app.data.TraderRow
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.StickToTop
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LaneTabs
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.Meter
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.WalletName
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.keyAt
import trade.nesty.app.ui.uniqueItems
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.Older
import trade.nesty.app.ui.token.Phase
import trade.nesty.app.ui.token.TokenModel
import trade.nesty.app.ui.token.rememberTokenModel

private val TABS = listOf("Trades", "Holders", "Top traders", "Early buyers")

/** Rows from the end of the Trades list at which the page before it is read. */
private const val NEAR_END = 15

@Composable
fun TradesHoldersScreen(chain: Chain, address: String) {
    val model = rememberTokenModel(chain, address)
    val nav = LocalNavigator.current
    WhileShown { model.live(withChart = false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    val coin = model.coin
    val sub = listOfNotNull(coin?.symbol, chain.title, coin?.mcapUsd?.let { "MC ${Fmt.usd(it)}" }).joinToString(" · ")

    Column(Modifier.fillMaxSize()) {
        BackHeader("Trades and holders", sub, onBack = { nav.pop() })
        LaneTabs(TABS, tab, { tab = it })
        val open = { w: String -> nav.push(Route.Wallet(chain, w)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> TradesList(model, only, { only = it }, open)
                1 -> HoldersList(model, chain, open)
                2 -> TradersList(model, early = false, open)
                else -> TradersList(model, early = true, open)
            }
        }
    }
}

@Composable
private fun TradesList(model: TokenModel, only: String?, setOnly: (String?) -> Unit, open: (String) -> Unit) {
    val now = rememberNow()
    val shown by remember(only) {
        derivedStateOf { if (only == null) model.trades.toList() else model.walletTrades(only) }
    }
    // A filtered list holds every trade of its wallet, read whole by the server.
    LaunchedEffect(only) {
        if (only == null) model.clearWallet() else model.readWallet(only)
    }
    val read = model.walletRead?.takeIf { it.wallet == only }
    val tradeList = rememberLazyListState()
    StickToTop(tradeList, shown.firstOrNull()?.id)
    // The page before the oldest trade is read as the list's end comes into view, and again while it stays there.
    val nearEnd by remember(tradeList) {
        derivedStateOf {
            val info = tradeList.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            last != null && last >= info.totalItemsCount - NEAR_END
        }
    }
    LaunchedEffect(nearEnd, only, model.olderPages) {
        if (!nearEnd || only != null) return@LaunchedEffect
        while (true) {
            model.loadOlder()
            if (model.older != Older.FAILED) break
            delay(5_000)
        }
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        if (only != null) {
            Row(
                Modifier.fillMaxWidth().background(Ink.violetBg).padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val whole = read?.phase == Phase.READY && read.all
                Text(
                    "${if (whole) "All " else ""}${shown.size} trades from ${Fmt.address(only)}",
                    style = text(13, FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Show all trades",
                    style = text(13, FontWeight.Bold, Ink.green),
                    modifier = Modifier.clickable(role = Role.Button) { setOnly(null) }.padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        }
        when {
            shown.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), state = tradeList) {
                uniqueItems(shown, { it.id }) { t -> TradeRow(t, now, only, setOnly, open) }
                if (only == null) item(key = "older") {
                    // Between the pages of a past read on its own, the list's end still says it is read.
                    val end = if (model.readingPast && model.older == Older.IDLE) Older.LOADING else model.older
                    OlderEnd(end) { scope.launch { model.loadOlder() } }
                }
            }
            only != null && read?.phase == Phase.FAILED -> EmptyNote("Could not read its trades. Trying again.")
            only != null && read?.phase != Phase.READY -> EmptyNote("Reading its trades…")
            model.tradesPhase == Phase.LOADING || model.tradesReading -> EmptyNote("Reading its trades…")
            model.tradesPhase == Phase.FAILED -> EmptyNote("Could not read its trades. Trying again.")
            else -> EmptyNote("No trades yet.")
        }
    }
}

/** The end of the Trades list: the page before it being read, or a way to ask for it after empty ones. */
@Composable
private fun OlderEnd(state: Older, ask: () -> Unit) {
    val line = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
    when (state) {
        Older.LOADING -> Text("Reading older trades…", style = text(12, color = Ink.muted), textAlign = TextAlign.Center, modifier = line)
        Older.FAILED -> Text("Could not read older trades. Trying again.", style = text(12, color = Ink.muted), textAlign = TextAlign.Center, modifier = line)
        Older.STALLED -> Text(
            "Show older trades",
            style = text(13, FontWeight.Bold, Ink.green),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = ask).padding(horizontal = 24.dp, vertical = 16.dp),
        )
        Older.IDLE, Older.DONE -> Unit
    }
}

@Composable
private fun TradeRow(t: Trade, now: Long, only: String?, setOnly: (String?) -> Unit, open: (String) -> Unit) {
    val w = t.wallet
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(92.dp).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (t.isBuy) "Buy" else "Sell", style = text(13, FontWeight.Bold, if (t.isBuy) Ink.green else Ink.pink))
                Text(t.usd?.let { Fmt.usdFull(it) } ?: Fmt.DASH, style = figure(15, FontWeight.SemiBold), maxLines = 1)
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(if (w != null) Modifier.clickable(role = Role.Button) { open(w) } else Modifier)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (w != null) WalletName(w, t.tags) else Text("Pool trade", style = text(14, FontWeight.Medium, Ink.muted))
                Text("at MC ${Fmt.usd(t.mcapUsd)} · ${Fmt.age(now - t.at)}", style = text(12, color = Ink.muted), maxLines = 1)
            }
            if (w != null) {
                val on = only.equals(w, ignoreCase = true)
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (on) Ink.violetBg else Ink.ground)
                        .clickable(role = Role.Switch) { setOnly(if (on) null else w) }
                        .semantics {
                            contentDescription = if (on) "Show all trades" else "Only trades from ${Fmt.address(w)}"
                            stateDescription = if (on) "On" else "Off"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NIcons.funnel, contentDescription = null, tint = if (on) Ink.violetSoft else Ink.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        Divider()
    }
}

@Composable
private fun HoldersList(model: TokenModel, chain: Chain, open: (String) -> Unit) {
    val answer = model.holders
    val mcap = model.coin?.mcapUsd
    val traders = model.traders
    val quoteUsd = traders?.quote?.usd
    val pnlOf = traders?.let { t -> (t.top + t.early).associateBy { it.wallet.lowercase() } } ?: emptyMap()
    val list: List<Holder> = answer?.list?.filter { it.role == null }.orEmpty()
    when {
        chain == Chain.SOLANA -> EmptyNote("Holders are not counted on Solana yet.")
        model.holdersServed == false -> EmptyNote("Nesty's holder book does not follow this coin.")
        answer == null -> EmptyNote("Reading its holders…")
        answer.state == "reading" -> EmptyNote("Counting its holders. This takes a moment for a new coin.")
        list.isEmpty() -> EmptyNote("No wallet holds it yet.")
        else -> {
            val top = list.first().share.takeIf { it > 0 } ?: 1.0
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.size, key = keyAt(list) { it.wallet }) { i ->
                    val h = list[i]
                    val pnl = pnlOf[h.wallet.lowercase()]?.let { r -> quoteUsd?.let { r.pnl * it } }
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable(role = Role.Button) { open(h.wallet) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${i + 1}", style = figure(14, FontWeight.SemiBold, Ink.muted), modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                WalletName(h.wallet, h.tags)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Holds ${mcap?.let { Fmt.usd(it * h.share) } ?: Fmt.DASH}", style = text(12, color = Ink.muted))
                                    if (pnl != null) {
                                        Text("· PnL", style = text(12, color = Ink.muted))
                                        Text(Fmt.usdSigned(pnl), style = text(12, FontWeight.SemiBold, signColor(pnl)))
                                    }
                                }
                            }
                            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(Fmt.share(h.share), style = figure(16, FontWeight.SemiBold))
                                Meter(h.share / top, Modifier.fillMaxWidth(), color = Ink.violet, height = 4.dp)
                            }
                        }
                        Divider()
                    }
                }
                item {
                    val note = if (answer.state == "partial") " Some transfers could not be read, so shares may be a little off." else ""
                    Text(
                        "Wallets only: the curve, pools and burns are left out.$note",
                        style = text(12, color = Ink.muted),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TradersList(model: TokenModel, early: Boolean, open: (String) -> Unit) {
    val t = model.traders
    val rows = (if (early) t?.early else t?.top).orEmpty()
    val usd = t?.quote?.usd
    val supply = model.supply ?: model.coin?.supply
    when {
        t == null && model.tradersPhase == Phase.FAILED -> EmptyNote("Could not read its traders. Trying again.")
        t == null -> EmptyNote("Reading its traders…")
        rows.isEmpty() -> EmptyNote(if (early) "No buyers yet." else "No trader has made anything yet.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size, key = keyAt(rows) { it.wallet }) { i ->
                val r = rows[i]
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(role = Role.Button) { open(r.wallet) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (early) Text("${i + 1}", style = figure(14, FontWeight.SemiBold, Ink.muted), modifier = Modifier.width(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            WalletName(r.wallet, r.tags)
                            Text(
                                if (early) earlyLine(r, usd, supply) else topLine(r, usd),
                                style = text(12, color = Ink.muted),
                                maxLines = 1,
                            )
                        }
                        if (early) {
                            val state = holdState(r)
                            Text(state, style = text(13, FontWeight.SemiBold, if (state == "Holds all") Ink.text2 else Ink.amber))
                        } else {
                            val p = usd?.let { r.pnl * it }
                            Text(p?.let { Fmt.usdSigned(it) } ?: Fmt.DASH, style = figure(16, FontWeight.SemiBold, signColor(p)))
                        }
                    }
                    Divider()
                }
            }
            if (t.partial) {
                item {
                    Text(
                        "Some of its trades are not counted yet.",
                        style = text(12, color = Ink.muted),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

private fun topLine(r: TraderRow, usd: Double?): String {
    fun d(q: Double) = usd?.let { Fmt.usdFull(q * it) } ?: "${Fmt.compact(q)}"
    return when {
        r.buys == 0 && r.sells > 0 -> "Sold ${d(r.sold)} · never bought"
        r.sells == 0 -> "Bought ${d(r.bought)} · still holds all"
        else -> "Bought ${d(r.bought)} · sold ${d(r.sold)}"
    }
}

private fun earlyLine(r: TraderRow, usd: Double?, supply: Double?): String {
    if (r.boughtTokens <= 0 || usd == null || supply == null) return "Bought ${usd?.let { Fmt.usdFull(r.bought * it) } ?: Fmt.DASH}"
    val mc = r.bought / r.boughtTokens * usd * supply
    return "Bought at MC ${Fmt.usd(mc)}"
}

/** What an early buyer did with what it bought. */
fun holdState(r: TraderRow): String {
    if (r.boughtTokens <= 0) return if (r.soldTokens > 0) "Sold all" else "Holds all"
    val left = r.remaining ?: (r.boughtTokens - r.soldTokens)
    val kept = (left / r.boughtTokens).coerceIn(0.0, 1.0)
    return when {
        kept >= 0.995 -> "Holds all"
        kept <= 0.005 -> "Sold all"
        else -> "Sold ${Math.round((1 - kept) * 100)}%"
    }
}
