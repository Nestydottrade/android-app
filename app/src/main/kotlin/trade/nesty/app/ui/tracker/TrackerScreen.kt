package trade.nesty.app.ui.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.PriceRow
import trade.nesty.app.data.Settings
import trade.nesty.app.data.WalletReport
import trade.nesty.app.data.WalletTrade
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.Chip
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalRoot
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NavRow
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TabHeader
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.discover.shareKey
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.QuickTrade

/** A followed wallet's trade, with what the coin is worth now. */
data class TrackedTrade(
    val chain: Chain,
    val wallet: String,
    val trade: WalletTrade,
    val symbol: String,
    /** Dollars per token when it traded, and now. */
    val priceThen: Double?,
    val priceNow: Double?,
    val mcThen: Double?,
    val mcNow: Double?,
)

private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
private val SOLANA_ADDRESS = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

/**
 * The followed wallets' launchpad trades of the last day, read from Nesty's book on every EVM
 * chain. The list of who is followed stays on the phone; the server sees only which wallets it is
 * asked about, as for any wallet page.
 */
class TrackerModel(private val api: NestyApi, private val settings: Settings) {
    var who by mutableStateOf<String?>(null)
    /** 0 all, 1 buys, 2 sells. */
    var kind by mutableIntStateOf(0)
    var min by mutableIntStateOf(0)
    var chain by mutableStateOf<Chain?>(null)

    private val reports = mutableStateMapOf<String, WalletReport>()
    private val prices = mutableStateMapOf<String, PriceRow>()
    var loaded by mutableStateOf(false)
        private set

    suspend fun live() {
        snapshotFlow { settings.followed.map { it.address.lowercase() } }.collectLatest { wallets ->
            while (true) {
                read(wallets)
                delay(if (wallets.size <= 5) 30_000 else 60_000)
            }
        }
    }

    private suspend fun read(wallets: List<String>) = coroutineScope {
        val gate = Semaphore(6)
        wallets.filter { EVM_ADDRESS.matches(it) }.flatMap { w -> Chain.booked.map { c -> c to w } }.map { (c, w) ->
            async {
                gate.withPermit {
                    runCatching { api.wallet(c, w, "1d").report }.getOrNull()?.let { reports["${c.feed}:$w"] = it }
                }
            }
        }.awaitAll()
        loaded = true
        // What each traded coin is worth now, and its supply for market caps: 30 coins a call.
        val coins = trades(raw = true).map { it.chain to it.trade.token }.distinctBy { shareKey(it.first, it.second) }.take(90)
        coins.chunked(30).map { part ->
            async { runCatching { api.prices(part).prices }.getOrNull()?.forEach { p -> Chain.of(p.feed)?.let { prices[shareKey(it, p.address)] = p } } }
        }.awaitAll()
    }

    /** The chain of a wallet's newest trade, for its wallet page. */
    fun chainOf(wallet: String): Chain =
        trades(raw = true).firstOrNull { it.wallet.equals(wallet, ignoreCase = true) }?.chain ?: Chain.BSC

    fun trades(raw: Boolean = false): List<TrackedTrade> {
        val followed = settings.followed.map { it.address.lowercase() }.toSet()
        val out = ArrayList<TrackedTrade>()
        for ((key, r) in reports) {
            val c = Chain.of(key.substringBefore(':')) ?: continue
            val w = key.substringAfter(':')
            if (w !in followed) continue
            for (t in r.trades) {
                val facts = r.book[t.token]
                val then = t.dollars?.let { d -> if (t.tokens > 0) d / t.tokens else null }
                val p = prices[shareKey(c, t.token)]
                val now = p?.priceUsd ?: facts?.usd
                val supply = p?.let { pr -> if (pr.priceUsd != null && pr.mcapUsd != null && pr.priceUsd > 0) pr.mcapUsd / pr.priceUsd else null }
                out += TrackedTrade(
                    chain = c,
                    wallet = w,
                    trade = t,
                    symbol = facts?.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(t.token),
                    priceThen = then,
                    priceNow = now,
                    mcThen = if (then != null && supply != null) then * supply else null,
                    mcNow = p?.mcapUsd,
                )
            }
        }
        out.sortByDescending { it.trade.at }
        if (raw) return out
        return out.filter { tt ->
            (who == null || tt.wallet.equals(who, ignoreCase = true)) &&
                (kind == 0 || (kind == 1) == tt.trade.isBuy) &&
                (tt.trade.dollars ?: 0.0) >= min &&
                (chain == null || tt.chain == chain)
        }.take(200)
    }
}

private val MINS = listOf(0, 50, 100, 250)

@Composable
fun TrackerScreen() {
    val model = LocalRoot.current.tracker
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val settings = AppGraph.settings
    WhileShown { model.live() }
    val now = rememberNow()
    var adding by rememberSaveable { mutableStateOf(false) }
    val rows = model.trades()
    val followed = settings.followed

    Column(Modifier.fillMaxSize()) {
        TabHeader("Tracker", onMenu = { nav.push(Route.Menu) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text("Following", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.semantics { heading() })
                        Text("${followed.size} of ${Settings.MAX_FOLLOWED} wallets", style = text(12, color = Ink.muted))
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip("All wallets", model.who == null, { model.who = null })
                        for (f in followed) {
                            Chip(f.name.ifBlank { Fmt.address(f.address) }, model.who.equals(f.address, ignoreCase = true), { model.who = f.address })
                        }
                        if (!adding && followed.size < Settings.MAX_FOLLOWED) {
                            Chip("Add", false, { adding = true }, leading = { Icon(NIcons.plus, contentDescription = null, tint = Ink.green, modifier = Modifier.size(16.dp)) })
                        }
                    }
                    if (adding) AddForm(onDone = { adding = false })
                    model.who?.let { w ->
                        NavRow(
                            settings.nameOf(w) ?: Fmt.address(w),
                            "Wallet page",
                            onClick = { nav.push(Route.Wallet(model.chainOf(w), w)) },
                        )
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Ink.raised).padding(2.dp)) {
                            listOf("All", "Buys", "Sells").forEachIndexed { i, label ->
                                val on = model.kind == i
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(if (on) Ink.violetBg else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(role = Role.Button) { model.kind = i },
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.muted)) }
                            }
                        }
                        val m = model.min
                        Box(
                            Modifier
                                .widthIn(min = 108.dp)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (m > 0) Ink.violetBg else Ink.raised)
                                .border(1.dp, if (m > 0) Ink.violet else Ink.line2, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { model.min = MINS[(MINS.indexOf(m) + 1) % MINS.size] }
                                .semantics { contentDescription = "Smallest trade shown: ${if (m > 0) "$$m" else "any size"}. Tap to change" }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (m > 0) "$$m or more" else "Any size", style = text(14, FontWeight.Bold, if (m > 0) Ink.text else Ink.text2))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip("All chains", model.chain == null, { model.chain = null })
                        for (c in Chain.booked) Chip(c.title, model.chain == c, { model.chain = c }, leading = { ChainDot(c, 8.dp) })
                    }
                    Divider()
                }
            }
            when {
                followed.isEmpty() -> item {
                    Notice("Follow a wallet to see its trades", "Follow one from the Leaderboard, a coin's holders, or add an address here.")
                }
                rows.isNotEmpty() -> items(rows, key = { it.chain.feed + it.wallet + it.trade.tx + it.trade.token + it.trade.isBuy }) { r ->
                    TrackedRow(r, now, settings, onOpen = { nav.push(Route.Token(r.chain, r.trade.token)) }, onBuy = { QuickTrade.buy(toasts, r.chain, r.trade.token, settings.quickBuy.toDouble(), r.symbol) })
                }
                !model.loaded -> item { Notice("Reading their trades…", "") }
                else -> item { Notice("No trades match", "Try a smaller minimum, another chain or all wallets.") }
            }
            item {
                Text(
                    "Trades made through smart accounts and some trading bots do not show here yet. Solana wallets come later: Nesty's book does not read Solana yet.",
                    style = text(13, color = Ink.muted, lineHeight = 18.sp),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = text(15, FontWeight.Bold))
        if (sub.isNotEmpty()) Text(sub, style = text(13, color = Ink.muted, lineHeight = 18.sp))
    }
}

/** A New form opens in the button's place: an address and a Follow button. */
@Composable
private fun AddForm(onDone: () -> Unit) {
    val settings = AppGraph.settings
    val toasts = LocalToasts.current
    var draft by rememberSaveable { mutableStateOf("") }
    val add = {
        val a = draft.trim()
        when {
            EVM_ADDRESS.matches(a) -> {
                if (settings.isFollowed(a)) toasts.show("Already followed", Fmt.address(a))
                else if (settings.follow(a)) toasts.show("Following ${Fmt.address(a)}", "Their trades show here · ${settings.followed.size} of ${Settings.MAX_FOLLOWED}")
                else toasts.show("You follow ${Settings.MAX_FOLLOWED} wallets already", "Stop following one first")
                draft = ""
                onDone()
            }
            SOLANA_ADDRESS.matches(a) -> toasts.show("Solana wallets come later", "Nesty's book does not read Solana yet")
            else -> toasts.show("That is not a wallet address", "Paste a 0x address")
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wallet address on any chain Nesty supports", style = text(13, color = Ink.muted))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink.raised)
                .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) Text("0x… address", style = text(15, color = Ink.muted))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.trim().take(64) },
                singleLine = true,
                textStyle = text(15),
                cursorBrush = SolidColor(Ink.green),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Wallet address" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Ink.green).clickable(role = Role.Button) { add() },
                contentAlignment = Alignment.Center,
            ) { Text("Follow this wallet", style = text(15, FontWeight.Bold, Ink.greenInk)) }
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(Ink.raised).clickable(role = Role.Button) { onDone() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", style = text(15, FontWeight.Bold, Ink.text2)) }
        }
    }
}

@Composable
private fun TrackedRow(r: TrackedTrade, now: Long, settings: Settings, onOpen: () -> Unit, onBuy: () -> Unit) {
    val t = r.trade
    val name = settings.nameOf(r.wallet) ?: Fmt.address(r.wallet)
    val change = if (r.priceThen != null && r.priceNow != null && r.priceThen > 0) (r.priceNow / r.priceThen - 1) * 100 else null
    val ratio = if (r.priceThen != null && r.priceNow != null && r.priceThen > 0) r.priceNow / r.priceThen else null
    val quick = settings.quickBuy
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinAvatar(null, r.symbol, t.token, r.chain, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = if (t.isBuy) Ink.green else Ink.pink, fontWeight = FontWeight.Bold)) { append(name) }
                                append(if (t.isBuy) " bought ${r.symbol}" else " sold ${r.symbol}")
                            },
                            style = text(15, FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(Fmt.age(now - t.at), style = text(12, color = Ink.muted))
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.dollars?.let { Fmt.usdFull(it) } ?: Fmt.DASH, style = figure(15, FontWeight.SemiBold))
                        if (r.mcThen != null && r.mcNow != null) {
                            Text("MC ${Fmt.usd(r.mcThen)} → now ${Fmt.usd(r.mcNow)}", style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        if (change != null) Text(Fmt.pctChange(change), style = text(13, FontWeight.Bold, pctColor(change)))
                    }
                    if (t.isBuy && ratio != null && ratio >= 1.5) {
                        Text("You would pay ${Fmt.times(ratio)} what they paid", style = text(13, color = Ink.amber))
                    }
                }
            }
            if (t.isBuy) {
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .width(64.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink.greenDeep)
                        .border(1.dp, Ink.greenLine, RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Buy $$quick of ${r.symbol}", role = Role.Button, onClick = onBuy),
                    contentAlignment = Alignment.Center,
                ) { Text("$$quick", style = text(15, FontWeight.Bold, Ink.green)) }
            }
        }
        Divider()
    }
}
