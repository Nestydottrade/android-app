package trade.nesty.app.ui.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.Preset
import trade.nesty.app.evm.Quote
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.move.LegQuote
import trade.nesty.app.move.LegStatus
import trade.nesty.app.move.Move
import trade.nesty.app.move.MoveLeg
import trade.nesty.app.move.MoveState
import trade.nesty.app.move.Plans
import trade.nesty.app.ui.Check
import trade.nesty.app.ui.Chip
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NestySheet
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.venueName
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlin.math.floor
import trade.nesty.app.evm.Trade as EngineTrade

private val OFF_BG = Color(0xFF1B182F)
private val OFF_INK = Color(0xFF7D78A8)

/** Digits and one point, two decimals at most, under ten million. Null: keep what was there. */
fun cleanAmount(v: String): String? {
    val s = v.replace(',', '.')
    if (s.any { !it.isDigit() && it != '.' }) return null
    val parts = s.split('.')
    if (parts.size > 2) return null
    if (parts.size == 2 && parts[1].length > 2) return null
    if (parts[0].length > 7) return null
    return s
}

/** One try of a read that may fail, without swallowing a cancel. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** An amount as the field shows it: 25, or 61.40. */
fun usdText(v: Double): String = if (v == floor(v) && v < 1e7) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

/**
 * A buy of any amount with any of the three presets, as the AppBuy board draws it: what it
 * gets, what it costs to get in, then the buy itself and how it ended. Paid from the chain's
 * own balance. Where that is short, the missing amount is moved here first from the fullest
 * other chain (One Balance): the sheet says how much and what it costs, and the one Buy
 * confirms both. A buy whose moved funds landed too late for its price is asked again here.
 */
@Composable
fun BuySheet(chain: Chain, token: String, coin: Coin, checks: List<Check>, onChecks: () -> Unit, onClose: () -> Unit, startUsd: Double? = null) {
    val trading = AppGraph.trading
    if (trading.needsUpdate(chain, coin)) return
    val mover = AppGraph.mover
    val settings = AppGraph.settings
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val info = remember(coin) { CoinInfo.of(coin) }
    val symbol = info.label

    var amount by rememberSaveable { mutableStateOf(startUsd?.let { usdText(it) } ?: settings.quickBuy.toString()) }
    var typed by rememberSaveable { mutableStateOf(startUsd != null) }
    var presetIndex by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var flow by remember { mutableStateOf<StateFlow<EngineTrade>?>(null) }
    var startPrice by remember { mutableStateOf<Double?>(null) }
    // A move this sheet started (or a waiting one it buys with): followed here while it is open.
    var moveId by rememberSaveable { mutableStateOf<String?>(null) }
    // While the sheet is open it shows how its trade ended; once closed, the note at the bottom does.
    val shown = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            shown[0] = false
            flow?.value?.id?.let { trading.quiet.remove(it) }
            moveId?.let { id ->
                mover.quiet.remove(id)
                mover.buys[id]?.value?.id?.let { trading.quiet.remove(it) }
            }
        }
    }
    val move = moveId?.let { mover.get(it) }
    val moveFlow = moveId?.let { mover.buys[it] }
    LaunchedEffect(moveFlow) { moveFlow?.value?.id?.let { if (shown[0]) trading.quiet.add(it) } }
    val trade = (flow ?: moveFlow)?.collectAsState()?.value

    // Funds moved here for a buy of this coin, landed when its price was no longer fresh: asked again.
    val ask = move?.takeIf { it.state == MoveState.ASK } ?: if (moveId == null && flow == null) mover.asking(chain, token) else null
    LaunchedEffect(ask?.id) {
        if (ask != null && !typed) amount = usdText(ask.usd)
    }

    val preset = settings.presets[presetIndex]
    val usd = amount.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    val price = trading.priceOf(chain)
    val wallet = AppGraph.session.active
    val own = wallet?.let { if (chain.isEvm) it.evm else it.sol }
    val balance = if (own == null) null else AppGraph.balances.active
    val chainUsd = balance?.chains?.get(chain)
    val firstBuy = trading.holding(chain, token) == null

    var quote by remember { mutableStateOf<Quote?>(null) }
    var quoted by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    var fee by remember { mutableStateOf<Double?>(null) }
    val priced = price != null

    // The quote of this amount, again every 5 seconds: a fresh one lets the buy skip a read.
    LaunchedEffect(usd, priced) {
        quote = null
        quoted = false
        why = null
        if (usd == null || !priced) return@LaunchedEffect
        delay(300)
        while (true) {
            val p = trading.priceOf(chain)
            if (p != null) {
                val wei = trading.weiOf(usd, chain, p)
                val read = attempt { withContext(Dispatchers.Default) { trading.quoteBuy(chain, token, wei, info) } }
                if (read.isSuccess) {
                    quote = read.getOrNull()
                    quoted = true
                    why = if (quote == null) attempt { withContext(Dispatchers.Default) { trading.whyNone(chain, token, info) } }.getOrNull() else null
                }
            }
            delay(5_000)
        }
    }
    // Without a balance read yet, the chain's coin is priced by its wrapped coin.
    LaunchedEffect(Unit) {
        while (trading.priceOf(chain) == null) {
            trading.readNativePrice(chain)
            delay(10_000)
        }
    }
    // The network fee at this preset's speed: the swap, and the sale's approval on a first buy (EVM).
    LaunchedEffect(preset, firstBuy, priced) {
        while (true) {
            attempt { withContext(Dispatchers.Default) { trading.buyFeeUsd(chain, token, info, preset, firstBuy) } }.getOrNull()?.let { fee = it }
            delay(if (fee == null) 4_000 else 15_000)
        }
    }
    val f = fee

    // One Balance: what this chain lacks for the buy, and the one other chain that moves it here.
    val have = balance?.raw?.get(chain)
    val funds = if (own == null) emptyList() else mover.funds()
    val reserves = mover.reserves(preset)
    val keepWei = price?.let { trading.weiOf(trading.reserveUsd(chain), chain, it) } ?: BigInteger.ZERO
    val shortRaw = if (usd != null && price != null && have != null) {
        Plans.buyShortfall(have, trading.weiOf(usd, chain, price), f?.let { trading.weiOf(it, chain, price) } ?: BigInteger.ZERO, keepWei)
    } else null
    val shortUsd = if (shortRaw != null && price != null) trading.usdOf(shortRaw, chain.decimals, price) else null
    val needsMove = ask == null && shortUsd != null && shortUsd >= 0.01
    val source = if (needsMove) Plans.buySource(chain, shortUsd, funds, reserves) else null
    val haveUsd = if (have != null && price != null) trading.usdOf(have, chain.decimals, price) else null
    val bestOut = funds.filter { it.chain != chain && Plans.canLeave(it.chain) }
        .maxOfOrNull { it.usdOf(Plans.movable(it, reserves[it.chain] ?: BigInteger.ZERO)) } ?: 0.0

    var moveQuote by remember { mutableStateOf<LegQuote?>(null) }
    var moveWhy by remember { mutableStateOf<String?>(null) }
    val sourceChain = source?.first?.chain
    val latestShort by rememberUpdatedState(shortRaw)
    val latestRoom by rememberUpdatedState(source?.second)
    // The bridge's offer for the missing amount, arriving exactly, again every 15 seconds.
    LaunchedEffect(sourceChain, usd, presetIndex, own, f != null) {
        moveQuote = null
        moveWhy = null
        if (sourceChain == null || own == null) return@LaunchedEffect
        delay(400)
        while (true) {
            val s = latestShort
            val room = latestRoom
            if (s != null && s.signum() > 0 && room != null) {
                attempt { withContext(Dispatchers.Default) { mover.quote(sourceChain, chain, own, s, room) } }
                    .onSuccess {
                        moveQuote = it
                        moveWhy = null
                    }
                    .onFailure {
                        moveQuote = null
                        moveWhy = it.message ?: "The bridge did not answer."
                    }
            }
            delay(15_000)
        }
    }
    // A move that can happen: the shortfall has a chain to come from.
    val viaMove = needsMove && source != null
    val mq = if (viaMove) moveQuote else null
    val moveFee = mq?.let { it.feeUsd ?: if (it.usdIn != null && it.usdOut != null) (it.usdIn - it.usdOut).coerceAtLeast(0.0) else null }

    val dec = info.decimals ?: 18
    val tax = (coin.tax?.buy ?: 0.0).coerceIn(0.0, 0.99)
    val q = quote
    val tokens = q?.let { BigDecimal(it.out).movePointLeft(dec).toDouble() * (1 - tax) }
    val supply = info.supply
    val entryMc = if (usd != null && tokens != null && tokens > 0 && supply != null && supply > 0) usd / tokens * supply else null
    val impact = q?.priceImpactBps?.coerceAtLeast(0)?.let { it / 10_000.0 }
    val feeShown = if (viaMove) (if (f != null && moveFee != null) f + moveFee else null) else f
    val cost = if (usd != null && q != null && (!viaMove || feeShown != null)) usd * (impact ?: 0.0) + usd * tax + (feeShown ?: 0.0) else null
    // Max leaves twice the fee estimate, a little for the price moving before it lands, and on
    // Solana the rent of the accounts a buy opens. With One Balance it adds what the fullest
    // other chain can move here, its bridge room taken out.
    // From the balance in the chain's own coin when it is read, so Max never needs a move of a few cents.
    val ownMax = (haveUsd ?: chainUsd)?.let { floor((it * 0.99 - 2 * (f ?: 0.0) - trading.reserveUsd(chain)) * 100) / 100 }
    val oneMax = if (ask == null && haveUsd != null && f != null && bestOut > 0) {
        floor(((haveUsd + Plans.movableUsd(bestOut) * 0.99) * 0.99 - 2 * f - trading.reserveUsd(chain)) * 100) / 100
    } else null
    val maxUsd = listOfNotNull(ownMax, oneMax).maxOrNull()?.takeIf { it >= 0.01 }
    val total = balance?.total
    val over = usd != null && if (ask != null) chainUsd != null && usd > chainUsd else needsMove && source == null

    val moving = move != null && (move.state == MoveState.WORKING || (move.state == MoveState.BUYING && trade == null))
    val working = starting || moving || trade?.status == TradeStatus.WORKING
    val reason = when {
        wallet == null -> "Add a wallet first"
        own == null -> if (chain.isEvm) "This wallet has no EVM address" else "This wallet has no Solana address"
        usd == null -> "Enter an amount"
        price == null -> "Reading the ${chain.native} price…"
        over -> if (ask == null && total != null && usd > total) "More than your One Balance" else "Amount is too high"
        needsMove && f == null -> "Reading the network fee…"
        needsMove && mq == null -> if (moveWhy != null) "Cannot move funds right now" else "Reading the move…"
        quoted && q == null -> "No pool to buy from right now"
        else -> null
    }

    fun failed(e: Exception) {
        val message = e.message ?: "Could not start."
        if (shown[0]) refused = message else toasts.show("Buy not sent", message, bad = true)
    }

    fun start() {
        val u = usd ?: return
        if (reason != null || working) return
        flow?.value?.id?.let { trading.quiet.remove(it) }
        flow = null
        refused = null
        starting = true
        startPrice = price
        val p = preset
        val pi = presetIndex
        val a = ask
        val moveFrom = source
        val offer = mq
        // Not the sheet's own scope: closing the sheet does not stop a buy or a move that has started.
        QuickTrade.scope.launch {
            try {
                when {
                    a != null -> {
                        if (shown[0]) mover.quiet.add(a.id)
                        withContext(Dispatchers.Default) { mover.buyAsked(a.id, u, pi, info) }
                        moveId = a.id
                    }
                    needsMove && moveFrom != null && offer != null -> {
                        val m = withContext(Dispatchers.Default) {
                            mover.buyWithMove(chain, token, u, pi, info, moveFrom.first.chain, offer.amountOut, Plans.cap(offer.amountIn, moveFrom.second), offer)
                        }
                        if (shown[0]) mover.quiet.add(m.id)
                        moveId = m.id
                    }
                    else -> {
                        val started = withContext(Dispatchers.Default) { trading.buy(chain, token, u, p, info) }
                        if (shown[0]) trading.quiet.add(started.value.id)
                        flow = started
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            } finally {
                starting = false
            }
        }
    }

    NestySheet("Buy $symbol", onClose) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                listOfNotNull(coin.launchpad ?: venueName(coin.pool?.dex), chain.title, coin.mcapUsd?.let { "MC ${Fmt.usd(it)}" }).joinToString(" · "),
                style = text(13, color = Ink.muted),
                modifier = Modifier.padding(top = 0.dp),
            )
            if (trade != null && trade.status == TradeStatus.DONE) {
                Bought(trade, chain, info, startPrice)
                GreenButton("See your position", {
                    onClose()
                    nav.push(Route.Position(chain, token))
                }, Modifier.fillMaxWidth(), solid = true)
                OutlineButton("Done", onClose)
            } else {
                if (ask != null && !working) {
                    AskNote(ask, chain) {
                        mover.keep(ask.id)
                        toasts.show("Kept on ${chain.title}", "${Fmt.usdFull(ask.arrivedUsd)} stays in your ${chain.title} balance.")
                        onClose()
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Amount in dollars", style = text(13, color = Ink.muted))
                    AmountField(amount, enabled = !working, bad = over) { v ->
                        amount = v
                        typed = true
                        refused = null
                        if (!working) {
                            flow?.value?.id?.let { trading.quiet.remove(it) }
                            flow = null
                        }
                    }
                    if (own != null) {
                        val bad = over || (needsMove && moveWhy != null && mq == null)
                        Text(
                            when {
                                chainUsd == null -> "Reading your ${chain.title} balance…"
                                ask != null -> if (over) "More than your ${chain.title} balance of ${Fmt.usdFull(chainUsd)}" else "Paid from your ${chain.title} balance of ${Fmt.usdFull(chainUsd)}"
                                over && total != null && usd > total -> "More than your One Balance of ${Fmt.usdFull(total)}"
                                over -> if (maxUsd != null) "Up to ${Fmt.usdFull(maxUsd)} here, with funds moved from one other chain" else "More than your ${chain.title} balance, and no other chain holds enough to move here"
                                needsMove && source != null -> moveLine(chain, source.first.chain, usd ?: 0.0, shortUsd, mq, moveWhy)
                                usd != null && usd == ownMax -> "Leaves about ${Fmt.usdFull(chainUsd - usd)} for network fees"
                                else -> "Paid from your ${chain.title} balance of ${Fmt.usdFull(chainUsd)}"
                            },
                            style = text(13, color = if (bad) Ink.pink else Ink.muted, lineHeight = 18.sp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf(10, 25, 50, 100)) {
                        AmountChip("$$v", on = usd == v.toDouble(), enabled = !working, modifier = Modifier.weight(1f)) {
                            amount = v.toString()
                            typed = true
                        }
                    }
                    AmountChip("Max", on = maxUsd != null && usd == maxUsd, enabled = !working && maxUsd != null, modifier = Modifier.weight(1f)) {
                        maxUsd?.let {
                            amount = String.format(Locale.US, "%.2f", it)
                            typed = true
                        }
                    }
                }
                Details(symbol, usd, tokens, entryMc, impact, tax, feeShown, if (viaMove) "Network and move fees" else "Network fee", cost, quoted, q == null, why)
                PresetRow(chain, settings.presets, presetIndex, editing, enabled = !working, onPick = { presetIndex = it }) { editing = !editing }
                if (editing) {
                    PresetEditor(presetIndex, preset) { settings.setPreset(presetIndex, it) }
                }
                ChecksLine(checks, onChecks)
                val swap = trade?.swap
                val leg = move?.legs?.firstOrNull()
                val status = when {
                    move != null && move.state == MoveState.WORKING -> movingWords(leg, chain)
                    move != null && move.state == MoveState.BUYING && trade == null -> "Moved to ${chain.title}. Buying…"
                    working -> if (swap?.hash != null) "Sent. Waiting for the chain…" else "Checking the trade, then sending it…"
                    trade != null -> (if (swap?.hash != null) "Buy failed: " else "Buy not sent: ") + (swap?.error ?: "Stopped")
                    move != null && move.state == MoveState.FAILED -> (if (leg?.hash != null) "Move failed: " else "Move not sent: ") + (leg?.error ?: "Stopped")
                    refused != null -> "Buy not sent: $refused"
                    else -> null
                }
                if (status != null) {
                    Text(
                        status,
                        style = text(13, color = if (working) Ink.text2 else Ink.pink, lineHeight = 18.sp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                BuyButton(
                    when {
                        moving -> "Moving…"
                        working -> "Buying…"
                        reason != null -> reason
                        else -> "Buy ${Fmt.usdFull(usd!!)} of $symbol"
                    },
                    enabled = !working && reason == null,
                    onClick = ::start,
                )
            }
        }
    }
}

/** What a short chain's buy moves first, as the AppBuy board says it. */
private fun moveLine(to: Chain, from: Chain, usd: Double, shortUsd: Double, q: LegQuote?, why: String?): String {
    if (q == null && why != null) return "Moving funds from ${from.title}: $why"
    val moved = Fmt.usdFull(q?.usdIn ?: shortUsd)
    val time = if (q != null) ", ${Fmt.eta(q.timeS)}" else ""
    val ownPart = usd - (q?.usdOut ?: shortUsd)
    return if (ownPart >= 0.01) {
        "Pays ${Fmt.usdFull(ownPart)} on ${to.title} and moves $moved from ${from.title} first$time"
    } else {
        "Moves $moved from ${from.title} to ${to.title} first$time"
    }
}

/** Where a buy's move stands, before the buy itself goes out. */
private fun movingWords(leg: MoveLeg?, to: Chain): String {
    val from = leg?.fromChain?.title ?: "the other chain"
    return when (leg?.status) {
        LegStatus.SENT -> "Move sent. Waiting for $from…"
        LegStatus.MOVING -> "Moving to ${to.title}, ${Fmt.eta(leg.timeS)}…"
        else -> "Checking the move from $from, then sending it…"
    }
}

/** Funds that were moved here for this buy and wait on the user: buy at today's price, or keep them. */
@Composable
private fun AskNote(ask: Move, chain: Chain, onKeep: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.raised).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${Fmt.usdFull(ask.arrivedUsd)} was moved to ${chain.title} for this buy. The price has changed since: check the amount, then buy.",
            style = text(13, color = Ink.text2, lineHeight = 18.sp),
        )
        ask.buyError?.let { Text("The buy was not sent: $it", style = text(13, color = Ink.pink, lineHeight = 18.sp)) }
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onKeep),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Keep it on ${chain.title}", style = text(14, FontWeight.Bold, Ink.green))
        }
    }
}

@Composable
internal fun AmountField(value: String, enabled: Boolean, bad: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$", style = figure(44, FontWeight.SemiBold, if (value.isEmpty()) Ink.muted else Ink.text))
        Box(Modifier.weight(1f).padding(start = 2.dp)) {
            if (value.isEmpty()) Text("0", style = figure(44, FontWeight.SemiBold, Ink.muted))
            BasicTextField(
                value = value,
                onValueChange = { v -> cleanAmount(v)?.let(onChange) },
                enabled = enabled,
                singleLine = true,
                textStyle = figure(44, FontWeight.SemiBold, if (bad) Ink.pink else Ink.text),
                cursorBrush = SolidColor(Ink.green),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Amount in dollars" },
            )
        }
    }
}

@Composable
internal fun AmountChip(label: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (on) Ink.greenDeep else Ink.raised)
            .border(1.dp, if (on) Ink.green else Ink.line2, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(14, FontWeight.Bold, if (on) Ink.green else if (enabled) Ink.text2 else Ink.muted), maxLines = 1)
    }
}

@Composable
private fun Details(
    symbol: String,
    usd: Double?,
    tokens: Double?,
    entryMc: Double?,
    impact: Double?,
    tax: Double,
    fee: Double?,
    feeLabel: String,
    cost: Double?,
    quoted: Boolean,
    none: Boolean,
    why: String?,
) {
    val shape = RoundedCornerShape(14.dp)
    // Before the first quote: an ellipsis. No pool: a dash and why, when a venue says.
    val wait = if (usd != null && !quoted) "…" else Fmt.DASH
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape)) {
        if (quoted && none && usd != null) {
            Text(
                why ?: "No pool trades $symbol right now.",
                style = text(13, color = Ink.text2, lineHeight = 18.sp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        DetailRow("You get about", tokens?.let { "${Fmt.amount(it)} $symbol" } ?: wait)
        DetailRow("Your entry market cap", entryMc?.let { Fmt.usd(it) } ?: wait)
        DetailRow("Price impact · Tax", "${impact?.let { Fmt.share(it) } ?: wait} · ${Fmt.taxPct(tax)}")
        DetailRow(feeLabel, fee?.let { "about ${Fmt.usdFull(it)}" } ?: wait)
        Box(Modifier.fillMaxWidth().background(Ink.raised)) {
            DetailRow(
                "Cost to get in",
                if (cost != null && usd != null) "${Fmt.usdFull(cost)} (${Fmt.share(cost / usd)})" else wait,
                strong = true,
            )
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String, strong: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = text(14, if (strong) FontWeight.Bold else FontWeight.Normal, if (strong) Ink.text else Ink.text2), modifier = Modifier.weight(1f))
        Text(value, style = figure(15, FontWeight.SemiBold, Ink.text), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PresetRow(chain: Chain, presets: List<Preset>, index: Int, editing: Boolean, enabled: Boolean, onPick: (Int) -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.indices.forEach { i ->
            val on = i == index
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(shape)
                    .background(if (on) Ink.violetBg else Ink.raised)
                    .border(1.dp, if (on) Ink.violet else Ink.line2, shape)
                    .clickable(enabled = enabled, role = Role.Button) { onPick(i) }
                    .semantics {
                        selected = on
                        contentDescription = "Preset ${i + 1}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("P${i + 1}", style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.text2))
            }
        }
        Text(presetWords(chain, presets[index]), style = text(13, color = Ink.text2, lineHeight = 17.sp), modifier = Modifier.weight(1f), maxLines = 2)
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (editing) "Close" else "Edit", style = text(14, FontWeight.Bold, Ink.green))
        }
    }
}

/** Opens under the preset row; each pick is saved to the preset at once. */
@Composable
private fun PresetEditor(index: Int, preset: Preset, onChange: (Preset) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Slippage", style = text(13, color = Ink.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf(5, 10, 15, 25)) Chip("$s%", preset.slippage == s, { onChange(preset.copy(slippage = s)) })
        }
        Text("Speed", style = text(13, color = Ink.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf("Normal", "Fast", "Turbo")) Chip(s, preset.speed == s, { onChange(preset.copy(speed = s)) })
        }
        Text(
            if (index == 0) "Saved as P1. One-tap buys and sells use P1." else "Saved as P${index + 1}.",
            style = text(12, color = Ink.muted),
        )
    }
}

/** The coin's red flags, or what to watch, in one line that opens the checks. */
@Composable
private fun ChecksLine(checks: List<Check>, onClick: () -> Unit) {
    val bad = checks.filter { it.tone == Tone.BAD }
    val warn = checks.filter { it.tone == Tone.WARN }
    val shown = bad.ifEmpty { warn }
    if (shown.isEmpty()) return
    val head = when {
        bad.size == 1 -> "1 red flag"
        bad.isNotEmpty() -> "${bad.size} red flags"
        else -> "${warn.size} to watch"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.raised)
            .clickable(onClickLabel = "See the checks", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToneMark(if (bad.isNotEmpty()) Tone.BAD else Tone.WARN, 18.dp)
        Text(
            "$head: ${shown.joinToString(", ") { it.label }}",
            style = text(13, color = Ink.text2, lineHeight = 18.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun BuyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Ink.green else OFF_BG)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(17, FontWeight.Bold, if (enabled) Ink.greenInk else OFF_INK), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun OutlineButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .border(1.dp, Ink.line3, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(16, FontWeight.Bold))
    }
}

/** How the buy ended, from its receipt: what came in, what it cost, at what market cap. */
@Composable
private fun Bought(trade: EngineTrade, chain: Chain, info: CoinInfo, price: Double?) {
    val dec = trade.decimals ?: info.decimals ?: 18
    val got = trade.swap.amountOut?.let { BigDecimal(it).movePointLeft(dec).toDouble() }
    val paid = trade.swap.amountIn?.let { w -> price?.let { AppGraph.trading.usdOf(w, chain.decimals, it) } }
    val supply = info.supply
    val mc = if (got != null && got > 0 && paid != null && supply != null) paid / got * supply else null
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(NIcons.doneCircle, contentDescription = null, tint = Ink.green, modifier = Modifier.size(44.dp))
        Text(
            got?.let { "Bought ${Fmt.amount(it)} ${info.label}" } ?: "Bought ${info.label}",
            style = text(20, FontWeight.Bold, lineHeight = 26.sp),
            textAlign = TextAlign.Center,
        )
        val sub = listOfNotNull(paid?.let { "Paid ${Fmt.usdFull(it)}" }, mc?.let { "entry market cap ${Fmt.usd(it)}" }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = text(14, color = Ink.text2), textAlign = TextAlign.Center)
    }
}
