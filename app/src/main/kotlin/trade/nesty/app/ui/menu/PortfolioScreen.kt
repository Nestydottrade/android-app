package trade.nesty.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import trade.nesty.app.data.Fill
import androidx.compose.ui.platform.LocalUriHandler
import trade.nesty.app.move.Move
import trade.nesty.app.move.MoveKind
import trade.nesty.app.move.MoveState
import trade.nesty.app.data.Holding
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Stat
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.signColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Balances
import trade.nesty.app.data.Chain
import trade.nesty.app.data.VaultState
import trade.nesty.app.data.WalletBalance
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LaneTabs
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NestySheet
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TabHeader
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.uniqueItems
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.copyText
import trade.nesty.app.vault.WalletInfo

/** One Balance as a figure: dollars once it is read, a dash until then, $0.00 with no wallet. */
fun oneBalanceText(wallet: WalletInfo?, balance: WalletBalance?): String = when {
    wallet == null -> "$0.00"
    balance == null -> Fmt.DASH
    else -> Fmt.usdFull(balance.total)
}

/** One Balance of the wallet that trades, for the bars that show it beside a buy button. */
@Composable
fun oneBalance(): String = oneBalanceText(AppGraph.session.active, AppGraph.balances.active)

@Composable
fun PortfolioScreen() {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val session = AppGraph.session
    val trading = AppGraph.trading
    val wallet = session.active
    val balance = AppGraph.balances.active
    var deposit by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    WhileShown(wallet?.id) { if (wallet != null) trading.livePrices() }
    val now = rememberNow(10_000)
    val holdings = if (wallet != null) trading.holdings() else emptyList()
    val open = holdings.filter { it.open }
    val closed = holdings.filter { !it.open }
    val activity = if (wallet != null) trading.activity() else emptyList()
    val mover = AppGraph.mover
    val moves = if (wallet != null) mover.ofActive() else emptyList()
    val openMoves = moves.filter { it.state.open }
    // Buys and sells with the moves among them, newest first.
    val entries: List<Any> = (activity + moves).sortedByDescending { if (it is Move) it.at else (it as Fill).at }.take(200)
    val uri = LocalUriHandler.current
    val openMove: (Move) -> Unit = { m ->
        val c = m.toChain
        val t = m.token
        if (m.kind == MoveKind.BUY && c != null && t != null) {
            nav.push(Route.Token(c, t, buy = m.state == MoveState.ASK))
        } else {
            // A withdrawal: its first transaction on the explorer of the chain it left.
            m.legs.firstOrNull { it.hash != null }?.let { l -> l.fromChain?.let { fc -> runCatching { uri.openUri("${fc.explorer}/tx/${l.hash}") } } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabHeader("Portfolio", onMenu = { nav.push(Route.Menu()) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "balance") {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
                    BalanceCard(wallet, balance, onDeposit = { deposit = true }) { nav.push(Route.Withdraw) }
                }
            }
            when {
                session.state == VaultState.NONE -> item(key = "setup") {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SetupCard(
                            "No wallet yet",
                            "Make a password, then create a wallet or import one. Its keys stay encrypted on this phone.",
                            "Set up a wallet",
                        ) { nav.push(Route.Setup) }
                    }
                }
                wallet == null -> item(key = "setup") {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SetupCard(
                            "No wallet yet",
                            "Create a wallet or import one to deposit and trade.",
                            "Add a wallet",
                        ) { nav.push(Route.Wallets(adding = true)) }
                    }
                }
                else -> {
                    uniqueItems(openMoves, { "m:" + it.id }) { m ->
                        Box(Modifier.padding(bottom = 10.dp)) {
                            MoveCard(m, onBuy = { openMove(m) }) {
                                mover.keep(m.id)
                                toasts.show("Kept on ${m.toChain?.title ?: "its chain"}", "The funds stay in that chain's balance.")
                            }
                        }
                    }
                    if (holdings.isNotEmpty()) item(key = "totals") { Totals(open, holdings) }
                    item(key = "tabs") {
                        LaneTabs(
                            listOf(if (open.isEmpty()) "Open" else "Open ${open.size}", "Closed", "Activity"),
                            tab,
                            { tab = it },
                            Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    when (tab) {
                        0 -> if (open.isEmpty()) {
                            item(key = "none") { EmptyNote("No open positions. Buy a coin from Discover or from its page.") }
                        } else {
                            uniqueItems(open, { "o:" + it.position.chain.feed + it.position.token }) { h ->
                                OpenRow(h, trading.mcaps[Balances.tokenKey(h.position.chain, h.position.token)]) {
                                    nav.push(Route.Position(h.position.chain, h.position.token))
                                }
                            }
                        }
                        1 -> if (closed.isEmpty()) {
                            item(key = "none") { EmptyNote("Nothing closed yet.") }
                        } else {
                            uniqueItems(closed, { "c:" + it.position.chain.feed + it.position.token }) { h ->
                                ClosedRow(h, now) { nav.push(Route.Position(h.position.chain, h.position.token)) }
                            }
                        }
                        else -> if (entries.isEmpty()) {
                            item(key = "none") { EmptyNote("Nothing yet. Your buys, sells and withdrawals will list here.") }
                        } else {
                            uniqueItems(entries, { e -> if (e is Move) "m:" + e.id else (e as Fill).let { "a:" + it.tx + it.token } }) { e ->
                                if (e is Move) {
                                    MoveRow(e, now, onOpen = if (e.kind == MoveKind.BUY || e.legs.any { it.hash != null }) ({ openMove(e) }) else null)
                                } else {
                                    val f = e as Fill
                                    ActivityRow(f, now) { f.chain?.let { c -> nav.push(Route.Position(c, f.token)) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (deposit && wallet != null) DepositSheet(wallet, onClose = { deposit = false }, onBackUp = {
        deposit = false
        nav.push(Route.Wallets())
    })
}

@Composable
private fun BalanceCard(wallet: WalletInfo?, balance: WalletBalance?, onDeposit: () -> Unit, onWithdraw: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("One Balance", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.semantics { heading() })
                Text(oneBalanceText(wallet, balance), style = figure(38, FontWeight.SemiBold).copy(lineHeight = 40.sp))
            }
            if (wallet != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GreenButton("Deposit", onDeposit, Modifier.weight(1f), solid = true, height = 44.dp, fontSize = 15)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Ink.raised)
                            .border(1.dp, Ink.line3, RoundedCornerShape(22.dp))
                            .clickable(role = Role.Button, onClick = onWithdraw),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Withdraw", style = text(15, FontWeight.Bold))
                    }
                }
                // The chains this wallet has an address on, three across.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in Balances.chainsOf(wallet).chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (chain in row) ChainCell(chain, balance, Modifier.weight(1f))
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
                if (balance != null && balance.unread.isNotEmpty()) {
                    Text("Not read just now: ${balance.unread.joinToString(", ") { it.title }}. Trying again.", style = text(12, color = Ink.muted))
                }
            }
        }
    }
}

/** A position's result: what it is worth now, plus what was taken out, less what was paid. */
private fun resultOf(h: Holding): Double? =
    (if (h.open) h.value else h.value ?: 0.0)?.let { it + h.position.taken - h.position.paid }

@Composable
private fun Totals(open: List<Holding>, all: List<Holding>) {
    val inOpen = open.sumOf { it.value ?: 0.0 }
    val results = all.mapNotNull { resultOf(it) }
    val profit = results.sum()
    val paid = all.sumOf { it.position.paid }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("In open positions", Fmt.usdFull(inOpen), Modifier.weight(1f))
        Stat(
            "Profit so far",
            if (results.isEmpty()) Fmt.DASH else Fmt.usdSigned(profit) + if (paid > 0) " (${Fmt.pctChange(profit / paid * 100)})" else "",
            Modifier.weight(1f),
            valueColor = if (results.isEmpty()) Ink.muted else signColor(profit),
        )
    }
}

@Composable
private fun PositionRow(
    symbol: String,
    h: Holding,
    sub: String,
    right: String,
    rightColor: Color,
    rightSub: String?,
    rightSubColor: Color,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoinAvatar(null, symbol, h.position.token, h.position.chain, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(symbol, style = text(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(right, style = figure(16, FontWeight.SemiBold, rightColor), maxLines = 1)
            if (rightSub != null) Text(rightSub, style = figure(13, FontWeight.Medium, rightSubColor), maxLines = 1)
        }
    }
    Divider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun OpenRow(h: Holding, mcapNow: Double?, onOpen: () -> Unit) {
    val p = h.position
    val symbol = p.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(p.token)
    val pnl = h.pnl
    val times = h.value?.let { v -> if (p.paid > 0) (v + p.taken) / p.paid else null }
    PositionRow(
        symbol,
        h,
        "Entry ${Fmt.usd(p.entryMcap)} → now ${Fmt.usd(mcapNow)}",
        h.value?.let { Fmt.usdFull(it) } ?: Fmt.DASH,
        Ink.text,
        listOfNotNull(pnl?.let { Fmt.usdSigned(it) }, times?.let { Fmt.times(it) }).joinToString(" · ").ifEmpty { null },
        signColor(pnl),
        onOpen,
    )
}

@Composable
private fun ClosedRow(h: Holding, now: Long, onOpen: () -> Unit) {
    val p = h.position
    val symbol = p.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(p.token)
    val result = resultOf(h)
    PositionRow(
        symbol,
        h,
        "Paid ${Fmt.usdFull(p.paid)} · got back ${Fmt.usdFull(p.taken)} · ${Fmt.age(now - p.lastAt)}",
        result?.let { Fmt.usdSigned(it) } ?: Fmt.DASH,
        signColor(result),
        result?.let { r -> if (p.paid > 0) Fmt.pctChange(r / p.paid * 100) else null },
        signColor(result),
        onOpen,
    )
}

@Composable
private fun ActivityRow(f: Fill, now: Long, onOpen: () -> Unit) {
    val symbol = f.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(f.token)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (f.isBuy) "Buy" else "Sell",
            style = text(13, FontWeight.Bold, if (f.isBuy) Ink.green else Ink.pinkSoft),
            modifier = Modifier.widthIn(min = 36.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(symbol, style = text(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                f.usd?.let { Text(Fmt.usdFull(it), style = figure(15, FontWeight.SemiBold)) }
            }
            Text(
                listOfNotNull(Fmt.amount(f.tokens), f.mcapUsd?.let { "at MC ${Fmt.usd(it)}" }).joinToString(" "),
                style = text(13, color = Ink.muted),
                maxLines = 1,
            )
        }
        Text(Fmt.age(now - f.at), style = text(12, color = Ink.muted))
    }
    Divider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ChainCell(chain: Chain, balance: WalletBalance?, modifier: Modifier) {
    val usd = balance?.chains?.get(chain)
    val figure = usd?.let { Fmt.usdFull(it) } ?: Fmt.DASH
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${chain.title} ${if (usd != null) figure else "not read yet"}" }, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChainDot(chain, 8.dp)
            Text(chain.title, style = text(12, color = Ink.muted), maxLines = 1)
        }
        Text(figure, style = figure(16, FontWeight.SemiBold, if (usd != null) Ink.text else Ink.muted), maxLines = 1)
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = text(16, FontWeight.Bold))
            Text(body, style = text(13, color = Ink.text2, lineHeight = 19.sp))
            GreenButton(action, onAction, Modifier.fillMaxWidth(), solid = true, height = 48.dp, fontSize = 15)
        }
    }
}

/** An address in groups of four, the 0x kept with its first group: 0x7a3F 9c1E 4B20 … */
fun inFours(address: String): String =
    if (address.startsWith("0x")) "0x" + address.drop(2).chunked(4).joinToString(" ") else address.chunked(4).joinToString(" ")

/** The wallet's own addresses to send coins to. Nothing is asked of the server here. */
@Composable
private fun DepositSheet(wallet: WalletInfo, onClose: () -> Unit, onBackUp: () -> Unit) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    NestySheet("Deposit to One Balance", onClose) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!wallet.backedUp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.raised)
                        .clickable(role = Role.Button, onClick = onBackUp)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToneMark(Tone.WARN, 18.dp)
                    Text("Back up this wallet's key before you send funds to it", style = text(13, color = Ink.text2, lineHeight = 18.sp), modifier = Modifier.weight(1f))
                    Text("Back up", style = text(14, FontWeight.Bold, Ink.green))
                }
            }
            wallet.evm?.let { address ->
                AddressCard(
                    "One address for Ethereum, Base, BNB Chain, Robinhood and Elysium",
                    address,
                    "Send ETH on Ethereum, Base or Robinhood, BNB on BNB Chain, or HYPE on Elysium.",
                ) {
                    copyText(context, "EVM address", address)
                    toasts.show("EVM address copied", Fmt.address(address))
                }
            }
            wallet.sol?.let { address ->
                AddressCard("Solana address", address, "Send SOL.") {
                    copyText(context, "Solana address", address)
                    toasts.show("Solana address copied", Fmt.address(address))
                }
            }
            if (wallet.evm == null) Text("This wallet was made from a Solana key: it has no EVM address.", style = text(13, color = Ink.muted))
            if (wallet.sol == null) Text("This wallet was made from an EVM key: it has no Solana address.", style = text(13, color = Ink.muted))
        }
    }
}

@Composable
private fun AddressCard(title: String, address: String, note: String, onCopy: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = text(13, color = Ink.muted, lineHeight = 18.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The whole address, in fours, so it can be checked before anything is sent to it.
            Text(
                inFours(address),
                style = figure(16, FontWeight.SemiBold).copy(lineHeight = 22.sp),
                modifier = Modifier.weight(1f).semantics { contentDescription = address },
            )
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Ink.raised)
                    .border(1.dp, Ink.line3, RoundedCornerShape(22.dp))
                    .clickable(onClickLabel = "Copy this address", role = Role.Button, onClick = onCopy)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Copy", style = text(14, FontWeight.Bold))
            }
        }
        Text(note, style = text(13, color = Ink.text2, lineHeight = 19.sp))
    }
}
