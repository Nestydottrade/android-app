package trade.nesty.app.ui.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Holding
import trade.nesty.app.data.Trading
import trade.nesty.app.evm.EvmTrader
import trade.nesty.app.ui.Check
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import kotlin.math.ceil

internal val PINK_LINE = Color(0xFF4F2240)

/** A bar at the bottom of a page, over the system bar. The notes at the bottom sit above it. */
@Composable
fun BottomBarFrame(content: @Composable ColumnScope.() -> Unit) {
    val toasts = LocalToasts.current
    val density = LocalDensity.current
    DisposableEffect(toasts) { onDispose { toasts.lift = 0.dp } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.card)
            .onGloballyPositioned { toasts.lift = with(density) { it.size.height.toDp() } }
            .navigationBarsPadding(),
    ) {
        Divider()
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** The chain's own balance, which pays a buy here: dollars once read, a dash until then. */
fun chainBalanceText(chain: Chain): String {
    val wallet = AppGraph.session.active ?: return "$0.00"
    if (chain.isEvm && wallet.evm == null) return "$0.00"
    if (!chain.isEvm && wallet.sol == null) return "$0.00"
    return AppGraph.balances.active?.chains?.get(chain)?.let { Fmt.usdFull(it) } ?: Fmt.DASH
}

/**
 * The bar under a coin: one-tap buys, or selling and buying more once the wallet holds it. The
 * one-tap buttons show only once the user picks Buy, Sell or Buy more, so a stray tap trades
 * nothing. A coin this build cannot trade says so instead. Custom opens the buy sheet, and so
 * does a one-tap buy the chain's own balance cannot pay: the sheet says what is moved there first.
 */
@Composable
fun TradeBar(chain: Chain, token: String, coin: Coin, checks: List<Check>, onChecks: () -> Unit, openBuy: Boolean = false, openUsd: Double? = null) {
    val trading = AppGraph.trading
    var sheet by rememberSaveable { mutableStateOf(openBuy) }
    var sheetUsd by rememberSaveable { mutableStateOf(if (openBuy) openUsd else null) }
    if (trading.needsUpdate(chain, coin)) {
        LockedBar()
        return
    }
    val open: (Double?) -> Unit = { usd ->
        sheetUsd = usd
        sheet = true
    }
    val holding = trading.holding(chain, token, coin.priceUsd)
    if (holding != null && holding.open) HoldingBar(chain, token, coin, holding, open) else BuyBar(chain, token, coin, open)
    if (sheet) {
        BuySheet(chain, token, coin, checks, onChecks = {
            sheet = false
            onChecks()
        }, onClose = { sheet = false }, startUsd = sheetUsd)
    }
}

/** Whether the chain's own balance, as last read, is short of a buy of `usd`. */
private fun shortOn(chain: Chain, usd: Double): Boolean {
    val trading = AppGraph.trading
    val have = AppGraph.balances.active?.raw?.get(chain) ?: return false
    val price = trading.priceOf(chain) ?: return false
    return have < trading.weiOf(usd, chain, price)
}

/** A coin this build cannot trade: the rest of the page works, and the update is one tap away. */
@Composable
private fun LockedBar() {
    val nav = LocalNavigator.current
    BottomBarFrame {
        Text(Trading.LOCKED, style = text(13, color = Ink.text2, lineHeight = 19.sp))
        GreenButton(EvmTrader.UPDATE_APP, onClick = { nav.push(Route.AppUpdate) }, modifier = Modifier.fillMaxWidth(), solid = true, fontSize = 16)
    }
}

@Composable
private fun BuyBar(chain: Chain, token: String, coin: Coin, onCustom: (Double?) -> Unit) {
    val nav = LocalNavigator.current
    var open by rememberSaveable { mutableStateOf(false) }
    BottomBarFrame {
        if (!open) {
            val symbol = coin.symbol?.takeIf { it.isNotBlank() } ?: "this coin"
            GreenButton("Buy $symbol", onClick = { open = true }, modifier = Modifier.fillMaxWidth(), solid = true, fontSize = 16)
            return@BottomBarFrame
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Buy", style = text(15, FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Row(Modifier.clickable(role = Role.Button) { nav.tab(Route.Portfolio) }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${chain.title} balance", style = text(13, color = Ink.muted))
                Text(chainBalanceText(chain), style = text(13, FontWeight.SemiBold))
            }
        }
        BuyButtons(chain, token, coin, onCustom)
        Text("P1: ${presetWords(chain, AppGraph.settings.presets[0])}", style = text(12, color = Ink.muted), maxLines = 1)
    }
}

/** The quick buy amounts at one tap with P1; Custom for any amount and preset. */
@Composable
private fun BuyButtons(chain: Chain, token: String, coin: Coin, onCustom: (Double?) -> Unit) {
    val toasts = LocalToasts.current
    val symbol = coin.symbol?.takeIf { it.isNotBlank() } ?: "this coin"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (usd in AppGraph.settings.quickAmounts) {
            GreenButton(
                quickLabel(usd),
                { if (shortOn(chain, usd.toDouble())) onCustom(usd.toDouble()) else QuickTrade.buy(toasts, chain, token, usd.toDouble(), coin.symbol, coin) },
                Modifier.weight(1f).semantics { contentDescription = "Buy $$usd of $symbol" },
                fontSize = 16,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.raised)
                .clickable(onClickLabel = "Choose an amount", role = Role.Button) { onCustom(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text("Custom", style = text(14, FontWeight.Bold), maxLines = 1)
        }
    }
}

/** $25, $1000, $25.0K: a quick buy amount on its button. */
private fun quickLabel(usd: Int): String = if (usd < 10_000) "$$usd" else Fmt.usd(usd.toDouble())

/** Selling the wallet's tokens, or buying more: one tap each, with P1. */
@Composable
private fun HoldingBar(chain: Chain, token: String, coin: Coin, holding: Holding, onCustom: (Double?) -> Unit) {
    val toasts = LocalToasts.current
    // Neither side picked: one-tap sells and buys show once the user picks one.
    var mode by rememberSaveable { mutableIntStateOf(NO_SIDE) }
    val symbol = coin.symbol?.takeIf { it.isNotBlank() } ?: holding.position.symbol ?: "this coin"
    val preset = AppGraph.settings.presets[0]
    BottomBarFrame {
        SideToggle(mode) { mode = if (mode == it) NO_SIDE else it }
        if (mode == NO_SIDE) return@BottomBarFrame
        if (mode == 0) {
            val value = holding.value
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val initials = holding.initials
                // A coin whose cost is not on record has no initials to take out.
                if (holding.known) {
                    SellButton(
                        "Initials",
                        initials?.let { Fmt.usdFull(it) } ?: "When up",
                        enabled = initials != null && value != null && value > 0,
                        does = initials?.let { "Sell ${Fmt.usdFull(it)} of your $symbol, what you paid" },
                        modifier = Modifier.weight(1f),
                    ) {
                        // Rounded up, as the extension does: enough to have what was paid back.
                        val bps = ceil(initials!! / value!! * 10_000).toInt().coerceIn(1, 10_000)
                        QuickTrade.sell(toasts, chain, token, bps, coin, "Taking your initials out of $symbol", "Took your initials out of $symbol")
                    }
                }
                for ((bps, label) in listOf(2_500 to "25%", 5_000 to "50%", 10_000 to "All")) {
                    val what = if (bps == 10_000) "all your $symbol" else "$label of your $symbol"
                    SellButton(
                        label,
                        value?.let { Fmt.usdFull(it * bps / 10_000) } ?: Fmt.DASH,
                        enabled = true,
                        does = "Sell $what",
                        modifier = Modifier.weight(1f),
                    ) {
                        QuickTrade.sell(toasts, chain, token, bps, coin, "Selling $what", "Sold $what")
                    }
                }
            }
            Text("Sells with P1: ${presetWords(chain, preset)}", style = text(12, color = Ink.muted), maxLines = 1)
        } else {
            BuyButtons(chain, token, coin, onCustom)
            Text(
                "From your ${chain.title} balance ${chainBalanceText(chain)} · P1: ${presetWords(chain, preset)}",
                style = text(12, color = Ink.muted, lineHeight = 16.sp),
                maxLines = 2,
            )
        }
    }
}

/** The holding bar before Sell or Buy more is picked. */
private const val NO_SIDE = -1

/** Sell or Buy more; a tap on the picked side puts its buttons away. */
@Composable
private fun SideToggle(mode: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ink.raised), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("Sell", "Buy more").forEachIndexed { i, label ->
            val on = i == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) Ink.text else Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(i) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = text(14, FontWeight.Bold, if (on) Ink.ground else Ink.text2), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SellButton(label: String, sub: String, enabled: Boolean, does: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(if (enabled) Ink.pinkBg else Ink.raised)
            .border(1.dp, if (enabled) PINK_LINE else Ink.line2, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (does != null) contentDescription = does },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = text(15, FontWeight.Bold, if (enabled) Ink.pinkSoft else Ink.muted), maxLines = 1)
        Text(sub, style = figure(12, FontWeight.Medium, if (enabled) Ink.pinkSoft.copy(alpha = 0.8f) else Ink.muted), maxLines = 1)
    }
}
