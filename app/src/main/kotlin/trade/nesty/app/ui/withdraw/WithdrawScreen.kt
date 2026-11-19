package trade.nesty.app.ui.withdraw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Balances
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Preset
import trade.nesty.app.i18n.tr
import trade.nesty.app.move.LegKind
import trade.nesty.app.move.LegQuote
import trade.nesty.app.move.LegStatus
import trade.nesty.app.move.Move
import trade.nesty.app.move.MoveState
import trade.nesty.app.move.Mover
import trade.nesty.app.move.Part
import trade.nesty.app.move.Plans
import trade.nesty.app.move.Recipient
import trade.nesty.app.move.Typed
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.ChainDot
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NestySheet
import trade.nesty.app.ui.SecretClipboard
import trade.nesty.app.ui.SecureWindow
import trade.nesty.app.ui.TextBox
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.menu.inFours
import trade.nesty.app.ui.menu.oneBalance
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.AmountChip
import trade.nesty.app.ui.trade.AmountField
import trade.nesty.app.ui.trade.BuyButton
import trade.nesty.app.ui.trade.DetailRow
import trade.nesty.app.ui.trade.OutlineButton
import trade.nesty.app.ui.vault.Fingerprint
import trade.nesty.app.ui.vault.say
import trade.nesty.app.vault.VaultException
import java.math.BigInteger
import java.util.Locale
import kotlin.math.floor

/** One try of a read that may fail, without swallowing a cancel. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * The bridge's offers and the chains' send fees this screen asked for, kept a little while so
 * the lists and the summary share them, and asked one at a time.
 */
@Stable
private class Offers(private val mover: Mover, private val preset: Preset) {
    private class Kept<T>(val at: Long, val value: T)

    private val quotes = mutableStateMapOf<String, Kept<Result<LegQuote>>>()
    private val sendFees = mutableStateMapOf<Chain, Kept<Double>>()
    private val lock = Mutex()

    private fun key(from: Chain, to: Chain, recipient: String, amount: BigInteger) = "${from.feed}>${to.feed}>$recipient>$amount"

    fun quote(from: Chain, to: Chain, recipient: String, amount: BigInteger): Result<LegQuote>? = quotes[key(from, to, recipient, amount)]?.value

    fun sendFee(chain: Chain): Double? = sendFees[chain]?.value

    suspend fun readQuote(from: Chain, to: Chain, recipient: String, amount: BigInteger, maxAgeMs: Long) {
        lock.withLock {
            val k = key(from, to, recipient, amount)
            val kept = quotes[k]
            if (kept != null && System.currentTimeMillis() - kept.at < maxAgeMs) return
            val r = attempt { withContext(Dispatchers.Default) { mover.quote(from, to, recipient, amount) } }
            quotes[k] = Kept(System.currentTimeMillis(), r)
        }
    }

    suspend fun readSendFee(chain: Chain, maxAgeMs: Long) {
        val kept = sendFees[chain]
        if (kept != null && System.currentTimeMillis() - kept.at < maxAgeMs) return
        attempt { withContext(Dispatchers.Default) { mover.sendFeeUsd(chain, preset) } }.getOrNull()?.let { sendFees[chain] = Kept(System.currentTimeMillis(), it) }
    }
}

/** What one way of sending costs and takes, from the offers read so far. */
private data class Cost(
    /** Null until every part is read. */
    val feeUsd: Double?,
    val seconds: Double?,
    val receiveUsd: Double?,
    val bridged: Boolean,
    /** Why a part cannot be moved, when the bridge says. */
    val problem: String?,
    val quotes: Map<Chain, LegQuote>,
)

private fun costOf(offers: Offers, parts: List<Part>, to: Chain, recipient: String?): Cost {
    var fee = 0.0
    var known = recipient != null
    var seconds: Double? = null
    var receive = 0.0
    var problem: String? = null
    val quotes = HashMap<Chain, LegQuote>()
    for (p in parts) {
        if (p.chain == to) {
            val sf = offers.sendFee(to)
            if (sf == null) known = false else fee += sf
            receive += p.usd
            continue
        }
        val r = recipient?.let { offers.quote(p.chain, to, it, p.raw) }
        when {
            r == null -> known = false
            r.isFailure -> {
                known = false
                problem = problem ?: tr("Moving from {1}: {2}", p.chain.title, r.exceptionOrNull()?.message ?: tr("the bridge did not answer."))
            }
            else -> {
                val q = r.getOrThrow()
                quotes[p.chain] = q
                fee += q.feeUsd ?: ((q.usdIn ?: 0.0) - (q.usdOut ?: 0.0)).coerceAtLeast(0.0)
                seconds = maxOf(seconds ?: 0.0, q.timeS ?: 0.0)
                receive += q.usdOut ?: p.usd
            }
        }
    }
    return Cost(if (known) fee else null, seconds, if (known) receive else null, parts.any { it.chain != to }, problem, quotes)
}

/** Reads the offers behind `parts` while this is on screen: once, or again every `refreshMs`. */
@Composable
private fun ReadOffers(offers: Offers, parts: List<Part>?, to: Chain, recipient: String?, refreshMs: Long? = null) {
    val key = parts?.joinToString { "${it.chain.feed}:${it.raw}" }
    LaunchedEffect(key, to, recipient) {
        if (parts == null || recipient == null) return@LaunchedEffect
        delay(500)
        while (true) {
            for (p in parts) {
                if (p.chain == to) offers.readSendFee(to, 30_000) else offers.readQuote(p.chain, to, recipient, p.raw, 20_000)
            }
            delay(refreshMs ?: return@LaunchedEffect)
        }
    }
}

private fun feeAndTime(c: Cost?): String = when {
    c == null -> ""
    c.problem != null -> tr("Cannot move now")
    c.feeUsd == null -> "…"
    else -> tr("Fee about {1} · {2}", Fmt.usdFull(c.feeUsd), if (c.bridged) Fmt.eta(c.seconds) else tr("a few seconds"))
}

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/**
 * Withdraw, as the AppWithdraw board draws it: One Balance sent to an EVM or Solana address,
 * pasted or typed. The arriving chain's share is a plain send; each other chain's share is bridged
 * to the receiver on the arriving chain. A little stays on each chain for its network fees.
 *
 * Reads that name the receiver go to the chains' own RPCs, and quotes to the bridge: Nesty's
 * server never learns whom the user pays.
 */
@Composable
fun WithdrawScreen() {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val session = AppGraph.session
    val mover = AppGraph.mover
    val wallet = session.active
    val balance = AppGraph.balances.active
    val preset = AppGraph.settings.presets[0]
    val offers = remember(preset) { Offers(mover, preset) }

    var address by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var onlyFeed by rememberSaveable { mutableStateOf<String?>(null) }
    var destFeed by rememberSaveable { mutableStateOf<String?>(null) }
    var takenOpen by rememberSaveable { mutableStateOf(false) }
    var arrivesOpen by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var doneId by rememberSaveable { mutableStateOf<String?>(null) }

    // The done screen shows its move as it goes; once left, the note at the bottom says how it ended.
    val shownId by rememberUpdatedState(doneId)
    DisposableEffect(Unit) { onDispose { shownId?.let { mover.quiet.remove(it) } } }

    Column(Modifier.fillMaxSize()) {
        BackHeader(tr("Withdraw"), sub = tr("One Balance {1}", oneBalance()), onBack = { nav.pop() })
        if (wallet == null) {
            EmptyNote(tr("Add a wallet first."))
            return@Column
        }
        val done = doneId?.let { mover.get(it) }
        if (done != null) {
            Done(done, onPortfolio = { nav.pop() }) {
                mover.quiet.remove(done.id)
                doneId = null
                amount = ""
            }
            return@Column
        }

        val chains = Balances.chainsOf(wallet)
        val typed = Recipient.parse(address)
        val to = when (typed) {
            is Typed.EvmAddress -> typed.address
            is Typed.SolAddress -> typed.address
            else -> null
        }
        val fits: (Chain) -> Boolean = { c -> typed == null || typed is Typed.Bad || typed.fits(c) }
        val funds = mover.funds()
        val reserves = mover.reserves(preset)
        fun movableUsd(c: Chain) = funds.firstOrNull { it.chain == c }?.let { it.usdOf(Plans.movable(it, reserves[c] ?: BigInteger.ZERO)) } ?: 0.0
        val only = Chain.of(onlyFeed)?.takeIf { it in chains }
        // Arrives on: the receiver's chain, the user's pick, or the fullest one the address works on.
        val dest = Chain.of(destFeed)?.takeIf(fits) ?: Chain.entries.filter(fits).maxByOrNull { movableUsd(it) } ?: Chain.entries.first()
        val usd = amount.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
        val max = Plans.withdrawMax(dest, funds, reserves, only)
        val parts = usd?.let { Plans.withdrawParts(it, dest, funds, reserves, only) }
        val over = usd != null && parts == null

        val ownHere = if (dest.isEvm) wallet.evm else wallet.sol
        val self = to != null && ownHere != null && to.equals(ownHere, ignoreCase = dest.isEvm)
        var check by remember { mutableStateOf<Mover.Check?>(null) }
        var checking by remember { mutableStateOf(false) }
        // The receiver as its chain sees it: a token contract, an address funds are lost at.
        LaunchedEffect(to, dest) {
            check = null
            checking = false
            if (to == null || self || !fits(dest)) return@LaunchedEffect
            checking = true
            delay(300)
            check = attempt { withContext(Dispatchers.Default) { mover.check(dest, to) } }.getOrNull()
            checking = false
        }
        val addressNote = when {
            typed is Typed.Bad -> typed.reason
            self -> tr("This is this wallet's own address on {1}.", dest.title)
            else -> check?.block
        }
        // Before an address is in, the fees are read as if to the wallet itself: the same to the bridge.
        val quoteTo = if (to != null && addressNote == null) to else ownHere ?: to
        ReadOffers(offers, parts, dest, quoteTo, refreshMs = 20_000)
        val cost = parts?.let { costOf(offers, it, dest, quoteTo) }

        val label = when {
            to == null || addressNote != null -> tr("Enter an address")
            usd == null -> tr("Enter an amount")
            over -> tr("Amount is too high")
            else -> tr("Review withdrawal")
        }
        val ready = to != null && addressNote == null && !checking && parts != null && cost != null && cost.problem == null && cost.feeUsd != null

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
                Text(tr("Send to"), style = text(13, color = Ink.muted), modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBox(
                        address,
                        { address = it.trim() },
                        tr("Address to send to"),
                        Modifier.weight(1f),
                        placeholder = tr("EVM or Solana address"),
                        bad = addressNote,
                        ime = ImeAction.Next,
                    )
                    Pill(tr("Paste")) {
                        val pasted = SecretClipboard.paste(context)?.trim()
                        if (pasted.isNullOrEmpty()) toasts.show(tr("Nothing to paste"), tr("Copy the address first.")) else address = pasted
                    }
                }
                if (addressNote != null) {
                    Text(addressNote, style = text(13, color = Ink.pink, lineHeight = 18.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }

            // Amount in dollars
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Amount in dollars"), style = text(13, color = Ink.muted), modifier = Modifier.semantics { heading() })
                AmountField(amount, enabled = true, bad = over) { amount = it }
                val from = if (only == null) tr("all chains") else only.title
                Text(
                    if (over) tr("The most you can send from {1} is {2}", from, Fmt.usdFull(max))
                    else tr("Up to {1} from {2}. A little stays behind for network fees.", Fmt.usdFull(max), from),
                    style = text(13, color = if (over) Ink.pink else Ink.muted, lineHeight = 18.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf(25, 50, 100)) {
                        AmountChip("$$v", on = usd == v.toDouble(), enabled = true, modifier = Modifier.weight(1f)) { amount = v.toString() }
                    }
                    val all = floor(max * 100) / 100
                    AmountChip(tr("All"), on = usd != null && all > 0 && usd == all, enabled = all > 0, modifier = Modifier.weight(1f)) {
                        amount = String.format(Locale.US, "%.2f", all)
                    }
                }
            }

            // Taken from
            Column {
                PickRow(
                    tr("Taken from"),
                    if (only == null) tr("All chains") else tr("Only {1}", only.title),
                    tr("up to {1}", Fmt.usdFull(max)),
                    takenOpen,
                ) {
                    takenOpen = !takenOpen
                    arrivesOpen = false
                }
                if (takenOpen) {
                    Choices {
                        val allMax = Plans.withdrawMax(dest, funds, reserves, null)
                        val allParts = usd?.let { Plans.withdrawParts(minOf(it, allMax), dest, funds, reserves, null) }
                        ReadOffers(offers, allParts, dest, quoteTo)
                        Choice(
                            tr("All chains"),
                            tr("Up to {1}, {2} first, then the others", Fmt.usdFull(allMax), dest.title),
                            feeAndTime(allParts?.let { costOf(offers, it, dest, quoteTo) }),
                            on = only == null,
                            enabled = true,
                        ) {
                            onlyFeed = null
                            takenOpen = false
                        }
                        for (c in chains) {
                            val m = Plans.withdrawMax(dest, funds, reserves, c)
                            val cParts = if (usd != null && m > 0) Plans.withdrawParts(minOf(usd, m), dest, funds, reserves, c) else null
                            ReadOffers(offers, cParts, dest, quoteTo)
                            Choice(
                                c.title,
                                when {
                                    m <= 0 -> if (c != dest && !Plans.canLeave(c) && movableUsd(c) > 0) tr("Nothing to send here: {1} funds stay on {2}", c.title, c.title) else tr("Nothing to send here")
                                    usd != null && m < usd -> tr("Up to {1}, less than this amount", Fmt.usdFull(m))
                                    else -> tr("Up to {1}", Fmt.usdFull(m))
                                },
                                feeAndTime(cParts?.let { costOf(offers, it, dest, quoteTo) }),
                                on = only == c,
                                enabled = m > 0,
                                chain = c,
                            ) {
                                onlyFeed = c.feed
                                takenOpen = false
                            }
                        }
                    }
                }
            }

            // Arrives on
            Column {
                PickRow(tr("Arrives on"), tr("{1} as {2}", dest.title, dest.native), null, arrivesOpen) {
                    arrivesOpen = !arrivesOpen
                    takenOpen = false
                }
                if (arrivesOpen) {
                    Choices {
                        for (d in Chain.entries) {
                            val works = fits(d)
                            val dParts = if (works && usd != null) Plans.withdrawParts(usd, d, funds, reserves, only) else null
                            val dTo = if (to != null && typed?.fits(d) == true) to else if (d.isEvm) wallet.evm else wallet.sol
                            ReadOffers(offers, dParts, d, dTo)
                            val here = balance?.chains?.get(d) ?: 0.0
                            Choice(
                                tr("{1} as {2}", d.title, d.native),
                                when {
                                    !works -> if (d.isEvm) tr("Needs an EVM address") else tr("Needs a Solana address")
                                    dParts == null -> tr("You have {1} here", Fmt.usdFull(here))
                                    dParts.all { it.chain == d } -> tr("You have {1} here · Straight from your {2} balance", Fmt.usdFull(here), d.title)
                                    dParts.any { it.chain == d } -> tr("You have {1} here · Part is moved here first", Fmt.usdFull(here))
                                    else -> tr("You have {1} here · All of it is moved here first", Fmt.usdFull(here))
                                },
                                if (works) feeAndTime(dParts?.let { costOf(offers, it, d, dTo) }) else "",
                                on = d == dest,
                                enabled = works,
                                chain = d,
                            ) {
                                destFeed = d.feed
                                arrivesOpen = false
                            }
                        }
                    }
                }
            }

            ChainNote(dest, parts, cost)

            Summary(dest, parts, cost, usd)

            Text(
                tr("Coins you hold are not part of this. To send one, open it in Portfolio."),
                style = text(13, color = Ink.muted, lineHeight = 18.sp),
            )
        }
        Column(Modifier.fillMaxWidth().background(Ink.card)) {
            Divider()
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                BuyButton(label, enabled = ready) { reviewing = true }
            }
        }

        if (reviewing && to != null && usd != null && parts != null && cost != null) {
            Review(dest, to, usd, parts, cost, onBack = { reviewing = false }) {
                try {
                    val m = mover.withdraw(dest, to, usd, parts, cost.quotes, preset)
                    mover.quiet.add(m.id)
                    doneId = m.id
                    reviewing = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reviewing = false
                    toasts.show(tr("Withdrawal not sent"), e.message ?: tr("Could not start."), bad = true)
                }
            }
        }
    }
}

@Composable
internal fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.raised)
            .border(1.dp, Ink.line3, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(14, FontWeight.Bold))
    }
}

/** A choice's row: what is picked, and Change, which opens the list right under it. */
@Composable
private fun PickRow(title: String, value: String, sub: String?, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = if (open) tr("Close the list") else tr("Change"), role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = if (open) tr("Expanded") else tr("Collapsed") }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = text(13, color = Ink.muted))
            Text(value, style = text(15, FontWeight.Bold))
            if (sub != null) Text(sub, style = text(13, color = Ink.muted))
        }
        Text(if (open) tr("Close") else tr("Change"), style = text(14, FontWeight.Bold, Ink.green))
    }
}

@Composable
private fun Choices(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape)) {
        content()
    }
}

@Composable
private fun Choice(title: String, sub: String, right: String, on: Boolean, enabled: Boolean, chain: Chain? = null, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (on) Ink.raised else Ink.ground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onPick)
            .semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (chain != null) ChainDot(chain, 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = text(15, FontWeight.Bold, if (enabled) Ink.text else Ink.muted))
            Text(sub, style = text(13, color = Ink.muted, lineHeight = 17.sp))
        }
        if (right.isNotEmpty()) Text(right, style = text(12, color = Ink.text2), textAlign = TextAlign.End, modifier = Modifier.padding(start = 4.dp))
    }
}

/** What the receiver must know about the chain, and what is moved there on the way. */
@Composable
private fun ChainNote(dest: Chain, parts: List<Part>?, cost: Cost?) {
    val moved = parts.orEmpty().filter { it.chain != dest }
    val text = when {
        cost?.problem != null -> cost.problem
        parts == null -> tr("The receiver must be able to use {1}. Ask them if you are not sure.", dest.title)
        moved.isEmpty() -> tr("Sent straight from your {1} balance. The receiver must be able to use {2}.", dest.title, dest.title)
        else -> tr(
            "{1} is moved to {2} on the way. The receiver must be able to use {3}.",
            Fmt.joinAnd(moved.map { tr("{1} from {2}", Fmt.usdFull(it.usd), it.chain.title) }),
            dest.title,
            dest.title,
        )
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToneMark(if (cost?.problem != null) Tone.BAD else Tone.INFO, 18.dp)
        Text(text, style = text(13, color = if (cost?.problem != null) Ink.pink else Ink.text2, lineHeight = 18.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Summary(dest: Chain, parts: List<Part>?, cost: Cost?, usd: Double?) {
    val shape = RoundedCornerShape(14.dp)
    val wait = if (usd != null && parts != null) "…" else Fmt.DASH
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape)) {
        DetailRow(if (cost?.bridged == true) tr("Network and move fees") else tr("Network fee"), cost?.feeUsd?.let { tr("about {1}", Fmt.usdFull(it)) } ?: wait)
        DetailRow(tr("Arrives in"), if (cost == null) wait else if (cost.bridged) cap(Fmt.eta(cost.seconds)) else tr("A few seconds"))
        Box(Modifier.fillMaxWidth().background(Ink.raised)) {
            DetailRow(tr("They receive about"), cost?.receiveUsd?.let { tr("{1} in {2}", Fmt.usdFull(it), dest.native) } ?: wait, strong = true)
        }
    }
}

/** The last look before a withdrawal: what leaves, what costs and what arrives. */
@Composable
private fun Review(dest: Chain, to: String, usd: Double, parts: List<Part>, cost: Cost, onBack: () -> Unit, onConfirmed: () -> Unit) {
    val moved = parts.filter { it.chain != dest }
    SendReview(dest, to, tr("Confirm withdrawal"), tr("{1} to {2} on {3}", Fmt.usdFull(usd), Fmt.address(to), dest.title), onBack, onConfirmed) {
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape)) {
            DetailRow(tr("Leaves One Balance"), Fmt.usdFull(usd))
            DetailRow(if (cost.bridged) tr("Network and move fees") else tr("Network fee"), cost.feeUsd?.let { tr("about {1}", Fmt.usdFull(it)) } ?: Fmt.DASH)
            DetailRow(tr("Arrives in"), if (cost.bridged) cap(Fmt.eta(cost.seconds)) else tr("A few seconds"))
            Box(Modifier.fillMaxWidth().background(Ink.raised)) {
                DetailRow(tr("They receive about"), cost.receiveUsd?.let { tr("{1} in {2}", Fmt.usdFull(it), dest.native) } ?: Fmt.DASH, strong = true)
            }
        }
        if (moved.isNotEmpty()) {
            Text(
                tr("{1} is moved to {2} on the way.", Fmt.joinAnd(moved.map { tr("{1} from {2}", Fmt.usdFull(it.usd), it.chain.title) }), dest.title),
                style = text(13, color = Ink.text2, lineHeight = 18.sp),
            )
        }
    }
}

/**
 * The last look before any send: the whole address in groups of four, the send's `figures`, and
 * the fingerprint (or the password, with fingerprint unlock off). `confirmTitle` and
 * `confirmDetail` are what the fingerprint prompt says.
 */
@Composable
internal fun SendReview(
    chain: Chain,
    to: String,
    confirmTitle: String,
    confirmDetail: String,
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
    figures: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val session = AppGraph.session
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    val finger = session.fingerprintOn && Fingerprint.can(context) == Fingerprint.Can.YES
    NestySheet(tr("Check before you send"), onBack) {
        if (!finger) SecureWindow()
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("To this address on {1}", chain.title), style = text(13, color = Ink.muted))
                Text(
                    inFours(to),
                    style = figure(18, FontWeight.SemiBold).copy(lineHeight = 26.sp),
                    modifier = Modifier.semantics { contentDescription = to },
                )
            }
            figures()
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToneMark(Tone.WARN, 18.dp)
                Text(
                    tr("A send cannot be undone. Compare every character with the address you were given."),
                    style = text(13, color = Ink.text2, lineHeight = 18.sp),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!finger) {
                TextBox(password, { password = it; note = null }, tr("Your Nesty password"), Modifier.fillMaxWidth(), placeholder = tr("Your Nesty password"), secret = true, bad = note)
            }
            note?.let { Text(it, style = text(13, color = Ink.pink, lineHeight = 18.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            BuyButton(
                when {
                    busy -> tr("Checking…")
                    finger -> tr("Confirm with fingerprint")
                    else -> tr("Confirm with password")
                },
                enabled = !busy && (finger || password.isNotEmpty()),
            ) {
                busy = true
                note = null
                scope.launch {
                    try {
                        if (finger) {
                            val ok = Fingerprint.confirm(context, confirmTitle, confirmDetail)
                            if (ok) onConfirmed() else note = if (session.fingerprintOn) tr("Not confirmed. Nothing was sent.") else Fingerprint.DROPPED
                        } else {
                            session.checkPassword(password)
                            password = ""
                            onConfirmed()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VaultException) {
                        note = e.say()
                    } catch (e: Exception) {
                        note = e.message ?: tr("That did not work. Try again.")
                    } finally {
                        busy = false
                    }
                }
            }
            OutlineButton(tr("Go back"), onBack)
        }
    }
}

/** After the confirmation: where the withdrawal stands, as it goes. */
@Composable
private fun Done(m: Move, onPortfolio: () -> Unit, onAgain: () -> Unit) {
    val dest = m.toChain
    val destName = dest?.title ?: tr("the chain")
    val receive = m.legs.sumOf { it.usdOut ?: it.usdIn ?: 0.0 }
    val bridged = m.legs.filter { it.kind == LegKind.BRIDGE }
    val title = when (m.state) {
        MoveState.DONE -> tr("Sent {1}", Fmt.usdFull(m.usd))
        MoveState.PARTIAL -> tr("Part of it arrived")
        MoveState.FAILED -> tr("Not sent")
        else -> tr("On its way")
    }
    val waitS = bridged.mapNotNull { it.timeS }.maxOrNull()
    val note = when (m.state) {
        MoveState.WORKING -> when {
            bridged.isEmpty() -> tr("It arrives in a few seconds.")
            bridged.size == m.legs.size -> tr("It is being moved to {1} first and arrives in {2}. You can follow it in Portfolio, under Activity.", destName, Fmt.eta(waitS))
            else -> tr("Part of it is being moved to {1} first and arrives in {2}. You can follow it in Portfolio, under Activity.", destName, Fmt.eta(waitS))
        }
        MoveState.PARTIAL -> m.legs.firstNotNullOfOrNull { l -> l.error?.takeIf { l.status != LegStatus.DONE } }
            ?.let { tr("About {1} arrived. {2} What did not leave is still in One Balance.", Fmt.usdFull(m.arrivedUsd), it) }
            ?: tr("About {1} arrived. What did not leave is still in One Balance.", Fmt.usdFull(m.arrivedUsd))
        MoveState.FAILED -> m.legs.firstNotNullOfOrNull { it.error } ?: tr("Stopped.")
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
                tr("About {1} to {2} on {3}", Fmt.usdFull(receive), Fmt.address(m.recipient), destName),
                style = text(15, color = Ink.text2, lineHeight = 21.sp),
                textAlign = TextAlign.Center,
            )
            if (note != null) Text(note.trim(), style = text(13, color = Ink.muted, lineHeight = 18.sp), textAlign = TextAlign.Center)
        }
        Box(Modifier.height(8.dp))
        BuyButton(tr("Back to Portfolio"), enabled = true, onClick = onPortfolio)
        OutlineButton(tr("Withdraw again"), onAgain)
    }
}
