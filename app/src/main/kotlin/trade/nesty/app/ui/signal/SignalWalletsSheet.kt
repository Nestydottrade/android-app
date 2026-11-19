package trade.nesty.app.ui.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import trade.nesty.app.data.ApiException
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.Quote
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.i18n.tr
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.NestySheet
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TagChip
import trade.nesty.app.ui.WalletTag
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.smart.FollowButton
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

/**
 * A coin's trader tables for a sheet opened away from its token page: read on opening, then
 * every 30 seconds while it is shown. Only the coin's address goes to the server.
 */
class CoinTraders(private val api: NestyApi, val chain: Chain, val address: String, knownSupply: Double?) {
    var answer by mutableStateOf<TradersAnswer?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    /** For the market cap at a wallet's average price; asked of the coin where the list had none. */
    var supply by mutableStateOf(knownSupply)
        private set

    suspend fun live() = coroutineScope {
        if (supply == null) launch { runCatching { api.token(chain, address).supply }.getOrNull()?.let { supply = it } }
        var wait = 4_000L
        while (true) {
            try {
                answer = api.traders(chain, address, 100)
                failed = false
                wait = 30_000
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // A coin the server sums no trades of: its wallets are listed without figures.
                if (e.notFound) {
                    if (answer == null) answer = TradersAnswer()
                    wait = 30_000
                } else {
                    failed = answer == null
                }
            } catch (_: Exception) {
                failed = answer == null
            }
            delay(wait)
            if (answer == null) wait = (wait * 2).coerceAtMost(30_000)
        }
    }
}

/**
 * What each wallet of a signal did in the coin, one card a wallet, in the signal's order.
 * `answer` is the coin's trader tables, null until they are read.
 */
@Composable
fun SignalWalletsSheet(
    chain: Chain,
    symbol: String,
    head: SignalHead,
    answer: TradersAnswer?,
    failed: Boolean,
    supply: Double?,
    onClose: () -> Unit,
) {
    val nav = LocalNavigator.current
    val now = rememberNow()
    NestySheet(SignalWallets.title(head.size, symbol, head.at, now), onClose) {
        when {
            answer == null && failed -> SheetNote(tr("Could not read what they did. Trying again."))
            answer == null -> SheetNote(tr("Reading what they did…"))
            else -> {
                val view = remember(head, answer) { SignalWallets.view(head, answer) }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (w in view.wallets) {
                        WalletCard(w, view.quote, supply) { nav.push(Route.Wallet(chain, w.wallet)) }
                    }
                    if (view.wallets.isEmpty()) QuietLine(tr("The wallets of this signal are not listed."))
                    if (view.partial) QuietLine(SignalWallets.PARTIAL)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SheetNote(message: String) {
    Text(
        message,
        style = text(14, color = Ink.muted),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 44.dp),
    )
}

@Composable
private fun QuietLine(message: String) {
    Text(
        message,
        style = text(12, color = Ink.muted, lineHeight = 17.sp),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun WalletCard(w: SignalWallet, quote: Quote?, supply: Double?, onOpen: () -> Unit) {
    val name = AppGraph.settings.nameOf(w.wallet)
    val short = Fmt.address(w.wallet)
    val tags = WalletTag.list(w.row?.tags.orEmpty()).take(2)
    val figures = remember(w.row, quote, supply) { w.row?.let { SignalWallets.figures(it, quote, supply) } }
    // One sentence for a screen reader: who, then what it did here.
    val spoken = listOf(name ?: short).plus(tags.map { it.label }).joinToString(", ") + ". " + (figures?.spoken ?: "${SignalWallets.NO_TRADES}.")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Ink.raised).border(1.dp, Ink.line2, shape)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = tr("Open this wallet"), role = Role.Button, onClick = onOpen)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The follow switch sits over the end of this row.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    name ?: short,
                    style = text(16, FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (name != null) Text(short, style = text(13, color = Ink.muted), maxLines = 1)
                for (t in tags) TagChip(t)
            }
            if (figures == null) {
                Text(SignalWallets.NO_TRADES, style = text(13, color = Ink.muted))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cell(tr("Bought"), figures.bought, Ink.green)
                    Cell(tr("Sold"), figures.sold, Ink.pink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cell(tr("Realized"), Figure(figures.realized), signInk(figures.realizedSign))
                    Cell(tr("Unrealized"), Figure(figures.unrealized), signInk(figures.unrealizedSign))
                    Cell(tr("Holds"), figures.holds, Ink.text)
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)) { FollowButton(w.wallet) }
    }
}

/** Green up, pink down, quiet at nothing: the plus or minus is in the figure itself. */
private fun signInk(sign: Int): Color = when {
    sign > 0 -> Ink.green
    sign < 0 -> Ink.pink
    else -> Ink.text2
}

@Composable
private fun RowScope.Cell(label: String, f: Figure, ink: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = text(12, color = Ink.muted), maxLines = 1)
        Text(
            f.value,
            style = figure(17, FontWeight.SemiBold, if (f.value == Fmt.DASH) Ink.muted else ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        f.note?.let { Text(it, style = text(12, color = Ink.muted, lineHeight = 16.sp), maxLines = 2) }
    }
}
