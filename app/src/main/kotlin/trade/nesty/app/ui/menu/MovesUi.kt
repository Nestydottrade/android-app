package trade.nesty.app.ui.menu

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.move.LegKind
import trade.nesty.app.move.LegStatus
import trade.nesty.app.move.Move
import trade.nesty.app.move.MoveKind
import trade.nesty.app.move.MoveLeg
import trade.nesty.app.move.MoveState
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

private fun coinOf(m: Move) = m.symbol?.takeIf { it.isNotBlank() } ?: m.token?.let { Fmt.address(it) } ?: "the coin"

private fun destOf(m: Move) = m.toChain?.title ?: "the chain"

/** A leg on its way, in words. */
private fun legWords(leg: MoveLeg?, dest: String): String {
    val from = leg?.fromChain?.title ?: "the other chain"
    return when (leg?.status) {
        LegStatus.SENT -> if (leg.kind == LegKind.SEND) "Sent. Waiting for $dest…" else "Sent from $from. Waiting for it to land…"
        LegStatus.MOVING -> "Moving from $from to $dest, ${Fmt.eta(leg.timeS)}…"
        LegStatus.DONE -> "Arrived"
        else -> "Checking it, then sending it…"
    }
}

/**
 * A move that has not ended, at the top of Portfolio: a withdrawal on its way, funds moving for
 * a buy, or moved funds that wait on the user. Nothing half-done is hidden.
 */
@Composable
fun MoveCard(m: Move, onBuy: () -> Unit, onKeep: () -> Unit) {
    val dest = destOf(m)
    val leg = m.legs.firstOrNull()
    val (title, detail) = when {
        m.kind == MoveKind.SEND_COIN -> "Sending ${Fmt.amount(m.coinTokens)} ${coinOf(m)} to ${Fmt.address(m.recipient)} on $dest" to legWords(leg, dest)
        m.kind == MoveKind.WITHDRAW -> {
            val moving = m.legs.firstOrNull { it.kind == LegKind.BRIDGE && it.status != LegStatus.DONE }
            "Sending ${Fmt.usdFull(m.usd)} to ${Fmt.address(m.recipient)} on $dest" to
                (if (moving != null) legWords(moving, dest) else legWords(m.legs.firstOrNull { !it.status.final } ?: leg, dest))
        }
        m.state == MoveState.ASK -> "Moved ${Fmt.usdFull(m.arrivedUsd)} to $dest for ${coinOf(m)}" to
            (m.buyError?.let { "The buy was not sent: $it " }.orEmpty() +
                "The price has changed since you confirmed. Buy at today's price, or keep the funds on $dest.")
        m.state == MoveState.BUYING -> "Buying ${coinOf(m)} on $dest" to "With the funds moved from ${leg?.fromChain?.title ?: "another chain"}"
        else -> "Moving ${Fmt.usdFull(leg?.usdOut ?: leg?.usdIn ?: m.usd)} to $dest for ${coinOf(m)}" to legWords(leg, dest)
    }
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = text(15, FontWeight.Bold, lineHeight = 20.sp))
            Text(detail, style = text(13, color = Ink.text2, lineHeight = 18.sp))
            if (m.kind == MoveKind.BUY && m.state == MoveState.ASK) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GreenButton("Buy now", onBuy, Modifier.weight(1f), solid = true, height = 44.dp, fontSize = 15)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ink.raised)
                            .border(1.dp, Ink.line3, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button, onClick = onKeep),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Keep on $dest", style = text(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Where a move ended (or stands), in a word or two, for Activity. */
private fun stateWords(m: Move): String = when (m.kind) {
    MoveKind.WITHDRAW -> when (m.state) {
        MoveState.DONE -> "Arrived"
        MoveState.PARTIAL -> "Part arrived: ${Fmt.usdFull(m.arrivedUsd)}"
        MoveState.FAILED -> "Not sent"
        else -> "On its way"
    }
    MoveKind.SEND_COIN -> when (m.state) {
        MoveState.DONE -> "Sent"
        MoveState.PARTIAL -> "Not confirmed"
        MoveState.FAILED -> "Not sent"
        else -> "On its way"
    }
    MoveKind.BUY -> when (m.state) {
        MoveState.WORKING -> "Moving"
        MoveState.ASK -> "Waiting for you"
        MoveState.BUYING -> "Buying"
        MoveState.DONE -> "Bought"
        MoveState.KEPT -> "Kept on ${destOf(m)}"
        MoveState.FAILED -> if (m.legs.firstOrNull()?.status == LegStatus.DONE) "Moved, not bought" else "Not moved"
        MoveState.PARTIAL -> "Part moved"
    }
}

/** A move in Activity, among the buys and sells. */
@Composable
fun MoveRow(m: Move, now: Long, onOpen: (() -> Unit)?) {
    val leg = m.legs.firstOrNull()
    val bad = m.state == MoveState.FAILED || m.state == MoveState.PARTIAL
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val send = m.kind != MoveKind.BUY
        Text(
            if (send) "Send" else "Move",
            style = text(13, FontWeight.Bold, Ink.text2),
            modifier = Modifier.widthIn(min = 36.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (send) "To ${Fmt.address(m.recipient)}" else "${leg?.fromChain?.title ?: "?"} to ${destOf(m)}",
                    style = text(15, FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    when {
                        m.kind == MoveKind.WITHDRAW -> Fmt.usdFull(m.usd)
                        // A coin send: its worth then; a dash when it was not priced.
                        m.kind == MoveKind.SEND_COIN -> if (m.usd > 0) Fmt.usdFull(m.usd) else Fmt.DASH
                        else -> Fmt.usdFull(leg?.usdIn ?: m.usd)
                    },
                    style = figure(15, FontWeight.SemiBold),
                )
            }
            Text(
                when (m.kind) {
                    MoveKind.WITHDRAW -> "On ${destOf(m)}"
                    MoveKind.SEND_COIN -> "${Fmt.amount(m.coinTokens)} ${coinOf(m)} on ${destOf(m)}"
                    MoveKind.BUY -> "For ${coinOf(m)}"
                } + " · " + stateWords(m),
                style = text(13, color = if (bad) Ink.pink else Ink.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Fmt.age(now - m.at), style = text(12, color = Ink.muted))
    }
    Divider(Modifier.padding(horizontal = 16.dp))
}
