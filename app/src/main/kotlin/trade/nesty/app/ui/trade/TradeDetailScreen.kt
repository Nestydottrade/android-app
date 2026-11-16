package trade.nesty.app.ui.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Fill
import trade.nesty.app.data.FillDetail
import trade.nesty.app.data.FillTx
import trade.nesty.app.data.TradeCosts
import trade.nesty.app.data.Trading
import trade.nesty.app.exit.ExitMath
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.rememberCoinImage
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.copyText
import java.math.BigDecimal
import kotlin.math.abs

/**
 * One trade of Activity, as the TradeDetail boards draw it: what it got and for what, what it cost
 * in parts, where the money came from, how fast it went, its route and settings, and each of its
 * transactions. A trade that was sent and did not land says why and what it cost. Entries from
 * before the page existed have no detail: they show what they have.
 */
@Composable
fun TradeDetailScreen(wallet: String, tx: String) {
    val nav = LocalNavigator.current
    val fill = AppGraph.positions.fills.firstOrNull { it.wallet.equals(wallet, true) && it.tx.equals(tx, true) }
    val chain = fill?.chain
    val now = rememberNow(30_000)
    Column(Modifier.fillMaxSize()) {
        if (fill == null || chain == null) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48(NIcons.arrowBack, "Back", Ink.text) { nav.pop() }
            }
            Box(Modifier.padding(16.dp)) { EmptyNote("This trade is no longer on this phone.") }
            return@Column
        }
        val t = TradeFigures(fill, chain)
        Header(fill, chain, t.symbol, now) { nav.pop() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Summary(t)
            if (!fill.isBuy && !fill.failed) SaleResult(t, wallet)
            Costs(t)
            t.d?.move?.let { PaidFrom(t, it) }
            Speed(t)
            RouteCard(t)
            Transactions(t)
            Actions(t)
        }
    }
}

/** What one entry says, worked out once for the page. */
private class TradeFigures(val f: Fill, val chain: Chain) {
    val d: FillDetail? = f.detail
    val symbol: String = f.symbol?.takeIf { it.isNotBlank() } ?: Fmt.address(f.token)
    val buy: Boolean = f.isBuy

    /** What it paid (a buy) or got (a sell) in the chain's coin, or the token a sell paid out in. */
    val paidUnit: String = if (!buy && d?.payout != null) d.payout else chain.native
    val paidAmount: Double? = d?.native?.let { units(it, if (!buy && d.payout != null) d.payoutDecimals ?: chain.decimals else chain.decimals) }
    val feeNative: Double? = d?.feeRaw?.let { units(it, chain.decimals) }

    /** A Solana trade's tip for its private send. */
    val tipNative: Double? = d?.tipRaw?.let { units(it, chain.decimals) }?.takeIf { it > 0 }
    val tipUsd: Double? = tipNative?.let { n -> d?.nativeUsd?.let { n * it } }

    /** What the move that funded it cost, and the network fee without it or the tip: the entry's fee holds all three. */
    val moveFee: Double = d?.move?.feeUsd?.coerceAtLeast(0.0) ?: 0.0
    val networkUsd: Double? = f.feeUsd?.let { (it - moveFee - (tipUsd ?: 0.0)).coerceAtLeast(0.0) }

    /** The token it traded through on its way, from the route's name: "Flap curve via FXIon". */
    val via: String? = d?.route?.substringAfter(" via ", "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What went in at the coin's price: a buy's dollars, a sell's tokens at the coin's price (from
     * the route's own figures when the coin was not priced).
     */
    val value: Double?
    val split: TradeCosts.Split?

    /** Whether the route's quote was read for it: without one its cost has no parts. */
    val parted: Boolean = d != null && (d.impact != null || d.takeBps != null)

    init {
        val usd = f.usd
        val price = d?.priceUsd?.takeIf { it > 0 }
        val outside = d?.taxOutside ?: 0.0
        if (d == null || f.failed || usd == null || usd <= 0) {
            value = null
            split = null
        } else if (buy) {
            value = usd
            split = TradeCosts.split(usd, price?.let { f.tokens * it }, d.impact, d.takeBps, outside)
        } else {
            val worth = price?.let { f.tokens * it }
                ?: (usd / (1 - TradeCosts.split(1.0, null, d.impact, d.takeBps, outside).cost).coerceAtLeast(0.0001))
            value = worth
            split = TradeCosts.split(worth, usd, d.impact, d.takeBps, outside)
        }
    }

    /** Everything it cost: the route's part, the network fee, a tip, the move. */
    val totalCost: Double? = value?.let { v -> v * (split?.cost ?: 0.0) + (networkUsd ?: 0.0) + (tipUsd ?: 0.0) + moveFee }

    private fun units(raw: String, decimals: Int): Double? =
        raw.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }?.let { BigDecimal(it).movePointLeft(decimals).toDouble() }
}

@Composable
private fun Header(f: Fill, chain: Chain, symbol: String, now: Long, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton48(NIcons.arrowBack, "Back", Ink.text, onClick = onBack)
        CoinAvatar(rememberCoinImage(chain, f.token), symbol, f.token, chain, 38.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val title = when {
                f.failed -> (if (f.isBuy) "Buy of " else "Sell of ") + symbol + " failed"
                f.isBuy -> "Bought $symbol"
                else -> "Sold $symbol"
            }
            Text(title, style = text(18, FontWeight.Bold, lineHeight = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text("${chain.title} · ${Fmt.duration((now - f.at).toDouble())} ago", style = text(13, color = Ink.muted), maxLines = 1)
        }
        if (f.failed) Chip("Failed", Ink.pinkSoft, Ink.pinkBg) else Chip("Confirmed", Ink.green, Ink.greenDeep)
    }
}

@Composable
private fun Chip(label: String, ink: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(label, style = text(12, FontWeight.Bold, ink))
    }
}

@Composable
private fun Summary(t: TradeFigures) {
    val f = t.f
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f.failed) {
                Text(if (t.buy) "Nothing was bought" else "Nothing was sold", style = text(24, FontWeight.Bold))
                Text(failedWhy(t), style = text(14, color = Ink.text2, lineHeight = 20.sp))
                return@Column
            }
            val big = if (t.buy) {
                "+${Fmt.amount(f.tokens)} ${t.symbol}"
            } else {
                t.paidAmount?.let { "+${Fmt.amount(it)} ${t.paidUnit}" } ?: f.usd?.let { Fmt.usdSigned(it) } ?: "Sold"
            }
            Text(big, style = figure(30, FontWeight.SemiBold).copy(lineHeight = 34.sp), maxLines = 2)
            val each = f.usd?.let { u -> if (f.tokens > 0) u / f.tokens else null }
            val sub = listOfNotNull(
                if (t.buy) t.paidAmount?.let { "for ${Fmt.amount(it)} ${t.paidUnit}" } else "for ${Fmt.amount(f.tokens)} ${t.symbol}",
                f.usd?.let { Fmt.usdFull(it) },
                each?.let { "${priceText(it)} each" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = text(14, color = Ink.text2, lineHeight = 20.sp))
            f.mcapUsd?.let {
                Text((if (t.buy) "Entry market cap " else "Exit market cap ") + Fmt.usd(it), style = text(14, color = Ink.muted))
            }
        }
    }
}

/** Why a trade did not land, and where the funds stayed. */
private fun failedWhy(t: TradeFigures): String {
    val d = t.d
    val error = d?.error?.trim()?.trimEnd('.')
    val slip = d?.slippageBps?.let { Fmt.taxPct(it / 10_000.0) }
    val why = when {
        error == null -> "It did not land"
        error.startsWith("Reverted", ignoreCase = true) ->
            "It reverted on chain" + (slip?.let { ", most often because the price moved more than your $it slippage allows" } ?: "")
        else -> error
    }
    val kept = if (t.buy) {
        t.f.usd?.let { "Your ${Fmt.usdFull(it)} stayed in your ${t.chain.title} balance." } ?: "Your funds stayed in your ${t.chain.title} balance."
    } else {
        "Your ${Fmt.amount(t.f.tokens)} ${t.symbol} stayed in your wallet."
    }
    return "$why. $kept"
}

/** A sell against what its tokens cost: from this phone's buys of the coin before it. */
@Composable
private fun SaleResult(t: TradeFigures, wallet: String) {
    val f = t.f
    val got = f.usd ?: return
    val before = AppGraph.positions.of(wallet).filter { it.feed == f.feed && Trading.sameToken(t.chain, it.token, f.token) && it.at < f.at }
    val basis = ExitMath.costBasis(before)
    if (basis.tokens.signum() <= 0) return
    val sold = f.amount.min(basis.tokens)
    val paid = basis.costUsd * BigDecimal(sold).divide(BigDecimal(basis.tokens), java.math.MathContext.DECIMAL64).toDouble()
    val back = got - (t.networkUsd ?: 0.0) - (t.tipUsd ?: 0.0)
    val made = back - paid
    val trading = AppGraph.trading
    val held = if (trading.addressOn(t.chain)?.equals(wallet, true) == true) trading.holding(t.chain, f.token)?.takeIf { it.open } else null
    Section("What the sale made") {
        DetailRow("You paid for these tokens", Fmt.usdFull(paid))
        DetailRow("You got back, after fees", Fmt.usdFull(back))
        MadeRow(made, if (paid > 0) made / paid * 100 else null)
        if (held != null) {
            DetailRow("Still held", "${Fmt.amount(held.tokens)} ${t.symbol}" + (held.value?.let { " · ${Fmt.usdFull(it)}" } ?: ""))
        }
    }
}

@Composable
private fun MadeRow(made: Double, pct: Double?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Made on this sale", style = text(14, FontWeight.Bold), modifier = Modifier.weight(1f))
        Text(
            Fmt.usdSigned(made) + (pct?.let { " · ${Fmt.pctChange(it)}" } ?: ""),
            style = figure(15, FontWeight.SemiBold, signColor(made)),
        )
    }
}

@Composable
private fun Costs(t: TradeFigures) {
    val d = t.d
    val rows = mutableListOf<Pair<String, String>>()
    val v = t.value
    val s = t.split
    if (v != null && s != null) {
        if (t.parted) {
            val onCurve = d?.onCurve == true
            val takeBps = d?.takeBps ?: 0
            var take = s.take
            var other = s.other
            // Without a token on the way, what the route took beyond its own figures is its own.
            if (t.via == null) {
                take += other
                other = 0.0
            }
            val venue = if (onCurve) d?.launchpad ?: "Launchpad" else "Pool"
            val takeLabel = venue + if (takeBps > TAX_ABOVE_BPS) " fee and tax" else " fee"
            if (s.impact * v >= CENT) rows += "Price impact" to part(v * s.impact, s.impact)
            if (take * v >= CENT) rows += takeLabel to part(v * take, take)
            if (s.outside * v >= CENT) rows += "${t.symbol} tax" to part(v * s.outside, s.outside)
            if (other * v >= CENT) rows += "Swap into ${t.via} on the way" to part(v * other, other)
        } else if (s.cost * v >= CENT && d?.priceUsd != null) {
            rows += "Price impact, fees and tax" to part(v * s.cost, s.cost)
        }
    }
    t.networkUsd?.let { usd -> rows += "Network fee" to (dollars(usd) + (t.feeNative?.let { " · ${Fmt.amount(it)} ${t.chain.native}" } ?: "")) }
    t.tipNative?.let { n -> rows += "MEV protection tip" to listOfNotNull(t.tipUsd?.let { dollars(it) }, "${Fmt.amount(n)} ${t.chain.native}").joinToString(" · ") }
    d?.move?.let { m -> if (t.moveFee > 0) rows += "Moving funds from ${Chain.of(m.from)?.title ?: "another chain"}" to dollars(t.moveFee) }
    if (rows.isEmpty()) return
    Section(if (t.buy || t.f.failed) "What it cost" else "What it cost to sell") {
        rows.forEach { (label, value) -> DetailRow(label, value) }
        val total = t.totalCost
        if (total != null && v != null && !t.f.failed) {
            Divider(Modifier.padding(horizontal = 14.dp))
            DetailRow(if (t.buy) "Cost to get in" else "Cost to get out", part(total, total / v), strong = true)
        }
    }
}

@Composable
private fun PaidFrom(t: TradeFigures, move: trade.nesty.app.data.FillMove) {
    val from = Chain.of(move.from)?.title ?: "another chain"
    val own = t.f.usd?.let { u -> move.arrivedUsd?.let { (u - it).coerceAtLeast(0.0) } }
    Section("Paid from") {
        if (own != null && own >= CENT) DetailRow("${t.chain.title} balance", Fmt.usdFull(own))
        val moved = listOfNotNull(move.sentUsd?.let { "${Fmt.usdFull(it)} sent" }, move.arrivedUsd?.let { "${Fmt.usdFull(it)} arrived" }).joinToString(" · ")
        DetailRow("Moved from $from", moved.ifEmpty { Fmt.DASH })
    }
}

@Composable
private fun Speed(t: TradeFigures) {
    val d = t.d ?: return
    val moveMs = d.move?.ms
    if (moveMs == null && d.sentMs == null && d.confirmMs == null) return
    Section("Speed") {
        moveMs?.let { DetailRow("Moving funds", seconds(it)) }
        d.sentMs?.let {
            val label = when {
                d.byPlan -> "Plan to sent"
                d.move != null -> "Sent after the move"
                else -> "Tap to sent"
            }
            DetailRow(label, seconds(it))
        }
        d.confirmMs?.let { DetailRow("Confirmed after", seconds(it)) }
    }
}

@Composable
private fun RouteCard(t: TradeFigures) {
    val d = t.d ?: return
    Section("Route and settings") {
        d.route?.let { DetailRow("Pool", it) }
        val preset = listOfNotNull(
            d.preset?.let { "P${it + 1}" },
            d.slippageBps?.let { "${Fmt.taxPct(it / 10_000.0)} slippage" },
            d.speed,
        ).joinToString(" · ")
        if (preset.isNotEmpty()) DetailRow("Preset", preset)
        d.minOut?.let { raw ->
            val least = if (t.buy) {
                raw.toBigIntegerOrNull()?.let { "${Fmt.amount(BigDecimal(it).movePointLeft(t.f.decimals).toDouble())} ${t.symbol}" }
            } else {
                val decimals = if (d.payout != null) d.payoutDecimals ?: t.chain.decimals else t.chain.decimals
                raw.toBigIntegerOrNull()?.let { "${Fmt.amount(BigDecimal(it).movePointLeft(decimals).toDouble())} ${t.paidUnit}" }
            }
            least?.let { DetailRow("Least you would take", it) }
        }
        DetailRow("MEV protection", mevWords(d, t.chain))
        if (d.byPlan) DetailRow("Sent by", "Your exit plan")
        DetailRow("Nesty fee", "None")
    }
}

/** Whether it went out away from front-runners, or why it did not need to. */
private fun mevWords(d: FillDetail, chain: Chain): String = when {
    d.mev == true -> "On"
    // Rollups whose sequencer keeps no public queue of transactions.
    chain == Chain.BASE || chain == Chain.ROBINHOOD -> "Not needed on ${chain.title}"
    else -> "Off"
}

@Composable
private fun Transactions(t: TradeFigures) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val toasts = LocalToasts.current
    val txs = t.d?.txs?.takeIf { it.isNotEmpty() } ?: listOf(FillTx(if (t.buy) "buy" else "sell", t.f.feed, t.f.tx, ok = !t.f.failed))
    Section(if (txs.size == 1) "Transaction" else "Transactions") {
        txs.forEachIndexed { i, tx ->
            if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
            val chain = Chain.of(tx.feed)
            TxRow(
                label = txLabel(tx, t),
                tx = tx,
                onCopy = {
                    copyText(context, "Transaction", tx.hash)
                    toasts.show("Transaction copied", Fmt.address(tx.hash))
                },
                onOpen = chain?.let { c -> { runCatching { uri.openUri(c.txUrl(tx.hash)) } } },
            )
        }
    }
}

private fun txLabel(tx: FillTx, t: TradeFigures): String {
    val chain = Chain.of(tx.feed)
    return when (tx.kind) {
        "move" -> "Move from ${chain?.title ?: "another chain"}"
        "arrive" -> "Arrived on ${chain?.title ?: "the chain"}"
        "fund" -> "Swap into ${t.via ?: "its pay token"}"
        "back" -> "Back to ${t.chain.native}"
        "approve" -> if (t.buy) "Allow selling later" else "Allow selling"
        "buy" -> "Buy"
        "sell" -> "Sell"
        else -> tx.kind.replaceFirstChar { it.uppercase() }
    }
}

@Composable
private fun TxRow(label: String, tx: FillTx, onCopy: () -> Unit, onOpen: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label + if (tx.ok) "" else " · failed", style = text(14, color = if (tx.ok) Ink.text2 else Ink.pinkSoft))
            Text(Fmt.address(tx.hash), style = figure(13, FontWeight.Normal, Ink.muted))
        }
        IconButton48(NIcons.copy, "Copy transaction", Ink.muted, 20.dp, onCopy)
        if (onOpen != null) IconButton48(NIcons.external, "Open in explorer", Ink.muted, 20.dp, onOpen)
    }
}

@Composable
private fun Actions(t: TradeFigures) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val f = t.f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (f.failed) {
            GreenButton("Try again", onClick = {
                if (f.isBuy) nav.push(Route.Token(t.chain, f.token, buy = true, buyUsd = f.usd)) else nav.push(Route.Position(t.chain, f.token))
            }, modifier = Modifier.weight(1f), solid = true, height = 48.dp, fontSize = 15)
        } else {
            GreenButton("See your position", onClick = { nav.push(Route.Position(t.chain, f.token)) }, modifier = Modifier.weight(1f), solid = true, height = 48.dp, fontSize = 15)
        }
        GreenButton("Copy coin address", onClick = {
            copyText(context, "Coin address", f.token)
            toasts.show("Coin address copied", Fmt.address(f.token))
        }, modifier = Modifier.weight(1f), height = 48.dp, fontSize = 15)
    }
}

/** A card with its heading and rows. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Text(
            title,
            style = text(13, FontWeight.SemiBold, Ink.muted),
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
        )
        content()
        Box(Modifier.height(6.dp))
    }
}

/** "$2.29 · 2.3%": a part of the cost and its share. */
private fun part(usd: Double, share: Double): String = "${dollars(usd)} · ${Fmt.share(abs(share))}"

/** Dollars, with a fee of under a cent written as such. */
private fun dollars(usd: Double): String = if (usd > 0 && usd < 0.005) "<$0.01" else Fmt.usdFull(usd)

/** A coin's price: $0.0000044 below a dollar, else in full. */
private fun priceText(p: Double): String = if (p < 1) "$" + Fmt.amount(p) else Fmt.usdFull(p)

/** 0.7 s, 18 s, 2 minutes. */
private fun seconds(ms: Long): String = when {
    ms < 10_000 -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    ms < 60_000 -> "${(ms + 500) / 1000} s"
    else -> Fmt.duration(ms.toDouble())
}

/** Below this a part of the cost is not listed. */
private const val CENT = 0.005

/** A curve's take above a plain fee also holds the coin's tax. */
private const val TAX_ABOVE_BPS = 150
