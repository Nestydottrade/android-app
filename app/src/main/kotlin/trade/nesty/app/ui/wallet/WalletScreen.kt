package trade.nesty.app.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.ApiException
import trade.nesty.app.data.Chain
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.WalletReport
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LaneTabs
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TagChip
import trade.nesty.app.ui.WalletTag
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.avatarColor
import trade.nesty.app.ui.rememberEntryModel
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.smart.Period
import trade.nesty.app.ui.smart.toggleFollow
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.copyText

/** A wallet's page, from Nesty's book: what it made on launchpad curves. EVM chains only. */
class WalletModel(private val api: NestyApi, val chain: Chain, val address: String) {
    var period by mutableStateOf(Period.D7)
    private val reports = mutableStateMapOf<Period, WalletReport>()
    var empty by mutableStateOf<Set<Period>>(emptySet())
        private set
    var failed by mutableStateOf(false)
        private set

    fun report(): WalletReport? = reports[period]

    suspend fun live() {
        if (!chain.book) return
        snapshotFlow { period }.collectLatest { p ->
            while (true) {
                try {
                    val a = api.wallet(chain, address, p.window)
                    val r = a.report
                    if (r == null) empty = empty + p else reports[p] = r
                    failed = false
                } catch (e: ApiException) {
                    if (e.notFound) empty = empty + p else failed = reports[p] == null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = reports[p] == null
                }
                delay(60_000)
            }
        }
    }
}

/** What a report's open coins are worth above what they cost, in dollars. */
fun unrealized(r: WalletReport): Double = r.holdings.sumOf { h ->
    val facts = r.book[h.token] ?: return@sumOf 0.0
    val usd = facts.usd ?: return@sumOf 0.0
    val quoteUsd = r.quotes[facts.quote] ?: return@sumOf 0.0
    h.held * usd - h.cost * quoteUsd
}

@Composable
fun WalletScreen(chain: Chain, address: String) {
    val model = rememberEntryModel("wallet") { WalletModel(AppGraph.api, chain, address) }
    val nav = LocalNavigator.current
    WhileShown { model.live() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val report = model.report()
    val now = rememberNow()

    Column(Modifier.fillMaxSize()) {
        BackHeader("Wallet", onBack = { nav.pop() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { Who(chain, address, report) }
            if (!chain.book) {
                item { EmptyNote("Wallet pages are not ready for Solana yet.") }
                return@LazyColumn
            }
            item { PeriodRow(model) }
            when {
                report != null -> {
                    item { PnlCard(report) }
                    item { LaneTabs(listOf("Recent PnL", "Activity"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp)) }
                    if (tab == 0) {
                        val coins = report.coins.sortedByDescending { it.last }.take(100)
                        if (coins.isEmpty()) item { EmptyNote("No coin traded in the ${model.period.words}.") }
                        items(coins, key = { "c:" + it.token }) { c ->
                            val facts = report.book[c.token]
                            val holding = report.holdings.firstOrNull { it.token == c.token }
                            val symbol = facts?.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(c.token)
                            val open = holding != null && c.sells == 0
                            val pct = if (c.basis > 0) c.profit / c.basis * 100 else null
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 66.dp)
                                    .clickable(role = Role.Button) { nav.push(Route.Token(chain, c.token)) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CoinAvatar(null, symbol, c.token, null, 36.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(symbol, style = text(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Text(
                                            if (holding != null) "Holding · ${Fmt.age(now - holding.opened)}" else Fmt.age(now - c.last),
                                            style = text(12, color = Ink.muted),
                                            maxLines = 1,
                                        )
                                    }
                                    Text(
                                        if (c.sells == 0) "Paid ${Fmt.usdFull(c.spent)}" else "Paid ${Fmt.usdFull(c.spent)} → got ${Fmt.usdFull(c.got)}",
                                        style = text(13, color = Ink.muted),
                                        maxLines = 1,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (open) {
                                        Text("Open", style = figure(16, FontWeight.SemiBold, Ink.text2))
                                        Text("not sold yet", style = text(12, color = Ink.muted))
                                    } else {
                                        Text(Fmt.usdSigned(c.profit), style = figure(16, FontWeight.SemiBold, signColor(c.profit)))
                                        Text(
                                            listOfNotNull(pct?.let { Fmt.pctChange(it) }, c.holdMs?.let { "held ${Fmt.age(it.toLong())}" }).joinToString(" · "),
                                            style = text(12, color = Ink.muted),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            Divider(Modifier.padding(horizontal = 16.dp))
                        }
                    } else {
                        val trades = report.trades.sortedByDescending { it.at }.take(200)
                        if (trades.isEmpty()) item { EmptyNote("No trade in the ${model.period.words}.") }
                        items(trades, key = { "t:" + it.tx + it.token + it.isBuy }) { t ->
                            val symbol = report.book[t.token]?.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(t.token)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clickable(role = Role.Button) { nav.push(Route.Token(chain, t.token)) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(if (t.isBuy) "Buy" else "Sell", style = text(14, FontWeight.Bold, if (t.isBuy) Ink.green else Ink.pink), modifier = Modifier.width(40.dp))
                                Text(
                                    "$symbol ${t.dollars?.let { Fmt.usdFull(it) } ?: ""}".trim(),
                                    style = text(14, FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!t.isBuy && t.profit != null) {
                                    Text(Fmt.usdSigned(t.profit), style = text(13, FontWeight.SemiBold, signColor(t.profit)))
                                }
                                Text(Fmt.age(now - t.at), style = text(13, color = Ink.muted), modifier = Modifier.width(34.dp))
                            }
                            Divider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                model.period in model.empty -> item { EmptyNote("Nesty's book has no launchpad trade of this wallet in the ${model.period.words}.") }
                model.failed -> item { EmptyNote("Could not reach Nesty. Trying again.") }
                else -> item { EmptyNote("Loading…") }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Who(chain: Chain, address: String, report: WalletReport?) {
    val settings = AppGraph.settings
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val name = settings.nameOf(address)
    val following = settings.isFollowed(address)
    val tag = WalletTag.of(report?.totals?.tag)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(avatarColor(address)), contentAlignment = Alignment.Center) {
                Text(address.removePrefix("0x").take(2).uppercase(), style = figure(17, FontWeight.Bold, Ink.ground))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (name != null) "$name · ${Fmt.address(address)}" else Fmt.address(address),
                    style = text(18, FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChainDot(chain, 8.dp)
                    Text(chain.title, style = text(13, color = Ink.text2))
                    tag?.let { TagChip(it) }
                }
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        copyText(context, "Address", address)
                        toasts.show("Address copied", Fmt.address(address))
                    }
                    .semantics { contentDescription = "Copy address" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(NIcons.copy, contentDescription = null, tint = Ink.text2, modifier = Modifier.size(20.dp))
            }
        }
        report?.totals?.holdMs?.let { hold ->
            val fast = hold < 15 * 60_000
            Text(
                "Usually holds a coin about ${Fmt.duration(hold)}." + if (fast) " To follow trades like these you need to act fast." else "",
                style = text(13, color = Ink.text2, lineHeight = 18.sp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (following) Ink.greenDeep else Ink.green)
                .border(1.dp, if (following) Ink.greenLine else Ink.green, RoundedCornerShape(14.dp))
                .clickable(role = Role.Switch) { toggleFollow(settings, toasts, address) }
                .semantics { stateDescription = if (following) "Following" else "Not following" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val ink = if (following) Ink.green else Ink.greenInk
            Icon(if (following) NIcons.check else NIcons.plus, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Text(if (following) "Following" else "Follow", style = text(16, FontWeight.Bold, ink))
        }
        if (following) NameEditor(address)
    }
}

/** The name the user gives a followed wallet, right under the button that follows it. */
@Composable
private fun NameEditor(address: String) {
    val settings = AppGraph.settings
    val toasts = LocalToasts.current
    var draft by rememberSaveable(address) { mutableStateOf(settings.nameOf(address) ?: "") }
    val save = {
        val n = draft.trim()
        settings.rename(address, n)
        toasts.show(if (n.isNotEmpty()) "Saved as $n" else "Name removed", if (n.isNotEmpty()) "Shows in Tracker and on coin pages" else "The address shows instead")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Name in your Tracker and on coin pages", style = text(13, color = Ink.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.raised)
                    .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) Text("For example, Fast sniper", style = text(15, color = Ink.muted))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(16) },
                    singleLine = true,
                    textStyle = text(15),
                    cursorBrush = SolidColor(Ink.green),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name in your Tracker and on coin pages" },
                )
            }
            Box(
                Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.greenDeep)
                    .border(1.dp, Ink.greenLine, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { save() }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", style = text(15, FontWeight.Bold, Ink.green))
            }
        }
    }
}

@Composable
private fun PeriodRow(model: WalletModel) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.raised).padding(2.dp),
    ) {
        for (p in Period.entries) {
            val on = model.period == p
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
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
}

@Composable
private fun PnlCard(r: WalletReport) {
    val t = r.totals
    val roi = if (t.basis > 0) t.profit / t.basis * 100 else null
    val won = t.wins
    val lost = (t.sold - t.wins).coerceAtLeast(0)
    val win = if (t.sold > 0) won.toDouble() / t.sold else null
    val open = unrealized(r)
    val worst = r.coins.filter { it.sells > 0 }.minOfOrNull { it.profit }
    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Realized profit", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.semantics { heading() })
                        Text(Fmt.usdSigned(t.profit), style = figure(34, FontWeight.SemiBold, signColor(t.profit)))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ROI", style = text(13, color = Ink.muted))
                        Text(roi?.let { Fmt.pctChange(it) } ?: Fmt.DASH, style = figure(22, FontWeight.SemiBold, pctColor(roi)))
                    }
                }
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = if (win != null) "Win rate ${Math.round(win * 100)}%: $won coins won, $lost lost" else "Nothing sold yet"
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            Text("Win rate ${win?.let { "${Math.round(it * 100)}%" } ?: Fmt.DASH}", style = text(14, FontWeight.Bold))
                            Text(" of " + Fmt.counted(t.sold.toLong(), "coin"), style = text(14, color = Ink.muted))
                        }
                        Text("$won won · $lost lost", style = text(13, color = Ink.muted))
                    }
                    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val w = (win ?: 0.0).toFloat()
                        if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)).background(Ink.green))
                        if (w < 1f) Box(Modifier.weight(1f - w).fillMaxHeight().clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Ink.pinkBg))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Unrealized", style = text(12, color = Ink.muted))
                        Text(if (r.holdings.isEmpty()) Fmt.DASH else Fmt.usdSigned(open), style = figure(17, FontWeight.SemiBold))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Total", style = text(12, color = Ink.muted))
                        Text(Fmt.usdSigned(t.profit + open), style = figure(17, FontWeight.SemiBold))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Worst trade", style = text(12, color = Ink.muted))
                        Text(worst?.let { Fmt.usdSigned(it) } ?: Fmt.DASH, style = figure(17, FontWeight.SemiBold, if (worst != null && worst < 0) Ink.pinkSoft else Ink.text))
                    }
                }
            }
        }
    }
}
