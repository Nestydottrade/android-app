package trade.nesty.app.ui.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Holding
import trade.nesty.app.data.Position
import trade.nesty.app.data.Served
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Checks
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.rememberCoinImage
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GhostButton
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NavRow
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.rememberTokenModel
import trade.nesty.app.ui.venueName

/**
 * The wallet's position in one coin, as the lean AppPosition board draws it: what it holds and
 * its result, its exit plan, then selling or buying more. The chart and "since you bought" come
 * in later steps.
 */
@Composable
fun PositionScreen(chain: Chain, address: String) {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val model = rememberTokenModel(chain, address)
    WhileShown { model.live(withChart = false) }
    val coin = model.coin
    val holding = AppGraph.trading.holding(chain, address, coin?.priceUsd)
    val now = rememberNow(5_000)
    val symbol = coin?.symbol?.takeIf { it.isNotBlank() } ?: holding?.position?.symbol ?: Fmt.address(address)

    // The coin's page: the one under this, when this was opened from it.
    val openToken: () -> Unit = {
        val under = nav.stack.getOrNull(nav.stack.lastIndex - 1)?.route
        if (under == Route.Token(chain, address)) nav.pop() else nav.push(Route.Token(chain, address))
    }

    Column(Modifier.fillMaxSize()) {
        Header(coin, chain, address, symbol, now) { nav.pop() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (holding == null) {
                EmptyNote("No trades of $symbol from this wallet yet.")
            } else {
                Yours(holding, symbol, coin, now)
                // An exit plan works from what was paid: a coin whose cost is not on record has none.
                if (holding.open && holding.known) ExitPlanCard(chain, address, symbol, holding, coin, now)
            }
            Card {
                if (holding != null && holding.held.signum() > 0 && AppGraph.trading.canTrade(chain)) {
                    NavRow("Send $symbol", onClick = { nav.push(Route.SendCoin(chain, address)) })
                    Divider()
                }
                NavRow("Coin checks and details", onClick = openToken)
                Divider()
                NavRow("Trades and holders", onClick = { nav.push(Route.TradesHolders(chain, address)) })
            }
            // A coin added by hand and never traded here leaves Portfolio as it came.
            if (holding != null && holding.position.imported && !holding.position.traded) {
                GhostButton(
                    "Remove from Portfolio",
                    {
                        AppGraph.trading.removeImport(chain, address)
                        toasts.show("Removed $symbol from Portfolio")
                        nav.pop()
                    },
                    Modifier.fillMaxWidth(),
                )
            }
        }
        if (coin != null) {
            val holderList = model.holders?.list?.takeIf { model.holdersServed == true }
            val checks = Checks.of(coin, model.share, holderList, chain == Chain.SOLANA, followed = model.holdersServed != false)
            TradeBar(chain, address, coin, checks, onChecks = openToken)
        } else {
            Box(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Header(coin: Coin?, chain: Chain, address: String, symbol: String, now: Long, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton48(NIcons.arrowBack, "Back", Ink.text, onClick = onBack)
        // Asked once the coin's answer is in, as it may carry the picture itself.
        val picture = if (coin == null) null else rememberCoinImage(chain, address, coin.image, Served(coin.launchpad))
        CoinAvatar(picture, symbol, address, chain, 38.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(symbol, style = text(20, FontWeight.Bold, lineHeight = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            val sub = listOfNotNull(
                coin?.launchpad ?: venueName(coin?.pool?.dex),
                chain.title,
                coin?.createdAt?.let { Fmt.ageSince(it, now) },
            ).joinToString(" · ")
            Text(sub, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Yours(h: Holding, symbol: String, coin: Coin?, now: Long) {
    val p = h.position
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (h.open) {
                Text("Your $symbol", style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.semantics { heading() })
                val pnl = h.pnl
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(h.value?.let { Fmt.usdFull(it) } ?: Fmt.DASH, style = figure(38, FontWeight.SemiBold).copy(lineHeight = 40.sp))
                    if (pnl != null) {
                        val pct = if (p.paid > 0) Fmt.pctChange(pnl / p.paid * 100) else null
                        Text(
                            Fmt.usdSigned(pnl) + (pct?.let { " ($it)" } ?: ""),
                            style = figure(17, FontWeight.SemiBold, signColor(pnl)),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }
                val bought = "${Fmt.amount(h.tokens)} $symbol"
                if (!h.known) {
                    // Got from outside Nesty: what it cost is not on record, so no result and no entry.
                    Text(bought, style = text(14, color = Ink.text2, lineHeight = 20.sp))
                    return@Column
                }
                val paid = "paid ${Fmt.usdFull(p.paid)}" + (p.entryMcap?.let { " at MC ${Fmt.usd(it)}" } ?: "")
                Text("$bought · $paid", style = text(14, color = Ink.text2, lineHeight = 20.sp))
                if (p.taken >= 0.01) Text("Taken out so far: ${Fmt.usdFull(p.taken)}", style = text(14, color = Ink.text2))
                // Right after a buy the position is down by what getting in cost, not by the market.
                if (pnl != null && pnl < 0 && p.sold.signum() == 0 && now - p.firstAt < FRESH_MS && -pnl < p.paid * 0.15) {
                    val parts = if ((coin?.tax?.buy ?: 0.0) > 0) "price impact, fees and tax" else "price impact and fees"
                    Text(
                        "The ${Fmt.usdFull(-pnl)} down is what it cost to get in: $parts.",
                        style = text(13, color = Ink.muted, lineHeight = 18.sp),
                    )
                }
            } else {
                // Gone without a sell from this phone: sent from here or elsewhere, or sold from another app.
                val title = when {
                    p.sold.signum() > 0 -> "You sold your $symbol"
                    p.sent.signum() > 0 -> "You sent your $symbol"
                    else -> "You no longer hold $symbol"
                }
                Text(title, style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.semantics { heading() })
                if (!h.known) {
                    Text("${Fmt.age(now - p.lastAt)} ago", style = text(14, color = Ink.text2, lineHeight = 20.sp))
                    return@Column
                }
                val result = (h.value ?: 0.0) + p.taken - p.paid
                Text(Fmt.usdSigned(result), style = figure(38, FontWeight.SemiBold, signColor(result)).copy(lineHeight = 40.sp))
                Text(
                    closedLine(p, now),
                    style = text(14, color = Ink.text2, lineHeight = 20.sp),
                )
            }
        }
    }
}

/** How long after the first buy the cost note shows. */
private const val FRESH_MS = 15 * 60_000L

/** A closed position in a line: paid, got back from sells, what was sent at its worth then, how long ago. */
fun closedLine(p: Position, now: Long): String = listOfNotNull(
    "Paid ${Fmt.usdFull(p.paid)}",
    // Sent away and never sold: nothing came back to name.
    if (p.sold.signum() > 0 || p.sent.signum() == 0) "got back ${Fmt.usdFull(p.taken - p.sentUsd)}" else null,
    if (p.sent.signum() > 0) "sent ${Fmt.usdFull(p.sentUsd)}" else null,
    "${Fmt.age(now - p.lastAt)} ago",
).joinToString(" · ")
