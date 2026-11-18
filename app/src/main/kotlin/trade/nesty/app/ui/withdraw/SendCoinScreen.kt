package trade.nesty.app.ui.withdraw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.move.CoinSend
import trade.nesty.app.move.Move
import trade.nesty.app.move.MoveState
import trade.nesty.app.move.Mover
import trade.nesty.app.move.Recipient
import trade.nesty.app.move.Typed
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.SecretClipboard
import trade.nesty.app.ui.TextBox
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.AmountChip
import trade.nesty.app.ui.trade.AmountField
import trade.nesty.app.ui.trade.BuyButton
import trade.nesty.app.ui.trade.DetailRow
import java.math.BigInteger

/** One try of a read that may fail, without swallowing a cancel. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * Send, from a coin's page in Portfolio: some or all of a coin the wallet holds, to an address
 * pasted or typed, on the coin's own chain. The receiver is checked, and the send simulated, on
 * the chain's own RPCs: Nesty's server never learns whom the user pays.
 */
@Composable
fun SendCoinScreen(chain: Chain, token: String) {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val trading = AppGraph.trading
    val mover = AppGraph.mover
    val wallet = AppGraph.session.active
    val preset = AppGraph.settings.presets[0]
    WhileShown(wallet?.id) { if (wallet != null) trading.livePrices() }

    val holding = if (wallet != null) trading.holding(chain, token) else null
    val symbol = holding?.position?.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(token)
    val decimals = holding?.position?.decimals ?: 18

    var address by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var doneId by rememberSaveable { mutableStateOf<String?>(null) }

    // The done screen shows its send as it goes; once left, the note at the bottom says how it ended.
    val shownId by rememberUpdatedState(doneId)
    DisposableEffect(Unit) { onDispose { shownId?.let { mover.quiet.remove(it) } } }

    Column(Modifier.fillMaxSize()) {
        BackHeader("Send $symbol", sub = "On ${chain.title}", onBack = { nav.pop() })
        if (wallet == null || holding == null) {
            EmptyNote(if (wallet == null) "Add a wallet first." else "This coin is not in your Portfolio.")
            return@Column
        }
        val done = doneId?.let { mover.get(it) }
        if (done != null) {
            CoinDone(done, onClose = { nav.pop() })
            return@Column
        }

        val held = holding.held
        val typed = Recipient.parse(address)
        val to = when (typed) {
            is Typed.EvmAddress -> typed.address
            is Typed.SolAddress -> typed.address
            else -> null
        }
        // A coin is on one chain only: an address of the other kind cannot hold it.
        val otherKind = to != null && typed?.fits(chain) == false
        val ownHere = if (chain.isEvm) wallet.evm else wallet.sol
        val self = to != null && ownHere != null && to.equals(ownHere, ignoreCase = chain.isEvm)

        var check by remember { mutableStateOf<Mover.CoinCheck?>(null) }
        var checking by remember { mutableStateOf(false) }
        // The receiver as its chain sees it: a token contract, an address funds are lost at.
        LaunchedEffect(to) {
            check = null
            checking = false
            if (to == null || self || otherKind) return@LaunchedEffect
            checking = true
            delay(300)
            check = attempt { withContext(Dispatchers.Default) { mover.checkCoin(chain, token, to) } }.getOrNull()
            checking = false
        }
        var fee by remember { mutableStateOf<BigInteger?>(null) }
        LaunchedEffect(chain, preset) {
            while (true) {
                attempt { withContext(Dispatchers.Default) { mover.coinFee(chain, preset) } }.getOrNull()?.let { fee = it }
                delay(30_000)
            }
        }

        val addressNote = when {
            typed is Typed.Bad -> typed.reason
            otherKind -> if (chain.isEvm) "This coin is on ${chain.title}: paste an EVM address." else "This coin is on Solana: paste a Solana address."
            self -> "This is this wallet's own address."
            else -> check?.block
        }
        val units = CoinSend.unitsOf(amount, decimals)
        val over = units != null && units > held
        val usd = units?.let { u -> holding.priceUsd?.let { trading.usdOf(u, decimals, it) } }
        val nativePrice = trading.priceOf(chain)
        val rent = check?.rent ?: BigInteger.ZERO
        val feeUsd = fee?.let { f -> nativePrice?.let { trading.usdOf(f, chain.decimals, it) } }
        val rentUsd = rent.takeIf { it.signum() > 0 }?.let { r -> nativePrice?.let { trading.usdOf(r, chain.decimals, it) } }
        val feeBlock = CoinSend.feeBlock(chain, AppGraph.balances.active?.raw?.get(chain), fee, rent, check?.keep ?: BigInteger.ZERO)

        val label = when {
            to == null || addressNote != null -> "Enter an address"
            units == null -> "Enter an amount"
            over -> "Amount is too high"
            feeBlock != null -> "Not enough ${chain.native} for the fee"
            else -> "Review send"
        }
        val ready = to != null && addressNote == null && !checking && units != null && !over && fee != null && feeBlock == null

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Send to
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Send to", style = text(13, color = Ink.muted), modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBox(
                        address,
                        { address = it.trim() },
                        "Address to send to",
                        Modifier.weight(1f),
                        placeholder = if (chain.isEvm) "EVM address" else "Solana address",
                        bad = addressNote,
                        ime = ImeAction.Next,
                    )
                    Pill("Paste") {
                        val pasted = SecretClipboard.paste(context)?.trim()
                        if (pasted.isNullOrEmpty()) toasts.show("Nothing to paste", "Copy the address first.") else address = pasted
                    }
                }
                if (addressNote != null) {
                    Text(addressNote, style = text(13, color = Ink.pink, lineHeight = 18.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }

            // Amount in the coin
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Amount in $symbol", style = text(13, color = Ink.muted), modifier = Modifier.semantics { heading() })
                AmountField(amount, enabled = true, bad = over, prefix = null, describe = "Amount in $symbol", size = 36, clean = { CoinSend.clean(it, decimals) }) { amount = it }
                Text(
                    if (over) "You hold ${Fmt.amount(holding.tokens)} $symbol"
                    else listOfNotNull(usd?.let { "About ${Fmt.usdFull(it)}" }, "You hold ${Fmt.amount(holding.tokens)}").joinToString(" · "),
                    style = text(13, color = if (over) Ink.pink else Ink.muted, lineHeight = 18.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((bps, chip) in listOf(2_500 to "25%", 5_000 to "50%", 10_000 to "All")) {
                        val v = CoinSend.share(held, bps)
                        AmountChip(chip, on = v.signum() > 0 && units == v, enabled = v.signum() > 0, modifier = Modifier.weight(1f)) {
                            amount = CoinSend.typed(v, decimals)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToneMark(Tone.INFO, 18.dp)
                Text(
                    "Sent on ${chain.title}. The receiver must be able to use ${chain.title}.",
                    style = text(13, color = Ink.text2, lineHeight = 18.sp),
                    modifier = Modifier.weight(1f),
                )
            }

            Column(Modifier.fillMaxWidth().clip(BOX).background(Ink.ground).border(1.dp, Ink.line, BOX)) {
                DetailRow("Network fee", feeUsd?.let { "about ${Fmt.usdFull(it)}" } ?: Fmt.DASH)
                if (rentUsd != null) DetailRow("Their account for this coin", "about ${Fmt.usdFull(rentUsd)}")
                Box(Modifier.fillMaxWidth().background(Ink.raised)) {
                    DetailRow("You send", units?.let { "${Fmt.amount(CoinSend.tokens(it, decimals))} $symbol" } ?: Fmt.DASH, strong = true)
                }
            }
            // On Solana it says what the fee, their account and the wallet's minimum take; elsewhere the button says it all.
            if (feeBlock != null && units != null && !over && chain == Chain.SOLANA) {
                Text(feeBlock, style = text(13, color = Ink.pink, lineHeight = 18.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Column(Modifier.fillMaxWidth().background(Ink.card)) {
            Divider()
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                BuyButton(label, enabled = ready) { reviewing = true }
            }
        }

        if (reviewing && to != null && units != null) {
            val tokens = CoinSend.tokens(units, decimals)
            SendReview(
                chain,
                to,
                "Confirm send",
                "${Fmt.amount(tokens)} $symbol to ${Fmt.address(to)} on ${chain.title}",
                onBack = { reviewing = false },
                onConfirmed = {
                    try {
                        val m = mover.sendCoin(chain, holding.position.token, holding.position.symbol, decimals, to, units, usd, preset)
                        mover.quiet.add(m.id)
                        doneId = m.id
                        reviewing = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reviewing = false
                        toasts.show("$symbol not sent", e.message ?: "Could not start.", bad = true)
                    }
                },
            ) {
                Column(Modifier.fillMaxWidth().clip(BOX).background(Ink.ground).border(1.dp, Ink.line, BOX)) {
                    Box(Modifier.fillMaxWidth().background(Ink.raised)) {
                        DetailRow("You send", "${Fmt.amount(tokens)} $symbol", strong = true)
                    }
                    if (usd != null) DetailRow("Worth about", Fmt.usdFull(usd))
                    DetailRow("Network fee", feeUsd?.let { "about ${Fmt.usdFull(it)}" } ?: Fmt.DASH)
                    if (rentUsd != null) DetailRow("Their account for this coin", "about ${Fmt.usdFull(rentUsd)}")
                }
            }
        }
    }
}

private val BOX = RoundedCornerShape(14.dp)

/** After the confirmation: where the send stands, as it goes. */
@Composable
private fun CoinDone(m: Move, onClose: () -> Unit) {
    val coin = m.symbol?.takeIf { it.isNotBlank() } ?: "coins"
    val error = m.legs.firstNotNullOfOrNull { it.error }
    val title = when (m.state) {
        MoveState.DONE -> "Sent"
        MoveState.PARTIAL -> "Not confirmed"
        MoveState.FAILED -> "Not sent"
        else -> "On its way"
    }
    val note = when (m.state) {
        MoveState.WORKING -> "It arrives in a few seconds."
        MoveState.FAILED -> error ?: "Stopped."
        MoveState.PARTIAL -> "It may still land. Check the explorer from Activity, in Portfolio."
        else -> null
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (m.state) {
                MoveState.DONE -> Icon(NIcons.doneCircle, contentDescription = null, tint = Ink.green, modifier = Modifier.size(48.dp))
                MoveState.FAILED, MoveState.PARTIAL -> ToneMark(Tone.BAD, 40.dp)
                else -> Icon(NIcons.pulse, contentDescription = null, tint = Ink.green, modifier = Modifier.size(48.dp))
            }
            Text(title, style = text(22, FontWeight.Bold, lineHeight = 28.sp), textAlign = TextAlign.Center)
            Text(
                "${Fmt.amount(m.coinTokens)} $coin to ${Fmt.address(m.recipient)} on ${m.toChain?.title ?: "its chain"}",
                style = text(15, color = Ink.text2, lineHeight = 21.sp),
                textAlign = TextAlign.Center,
            )
            if (note != null) Text(note, style = text(13, color = Ink.muted, lineHeight = 18.sp), textAlign = TextAlign.Center)
        }
        Box(Modifier.height(8.dp))
        BuyButton("Done", enabled = true, onClick = onClose)
    }
}
