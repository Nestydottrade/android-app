package trade.nesty.app.ui.trade

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import trade.nesty.app.alerts.Notifier
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.Holding
import trade.nesty.app.data.Trading
import trade.nesty.app.exit.ExitDraft
import trade.nesty.app.exit.ExitMath
import trade.nesty.app.exit.ExitPlan
import trade.nesty.app.exit.ExitRule
import trade.nesty.app.exit.ExitWatcher
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.alerts.AmberNote
import trade.nesty.app.ui.alerts.rememberTurnOnNotifications
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * The position's exit plan, as the AppPosition board draws it: a take profit, a trailing stop
 * that can be off, and a stop loss, each with its choices under its row. Off, it is a suggestion;
 * on, the watcher checks it every few seconds and sells from this wallet, also with Nesty closed.
 * The multiples are of what the held tokens cost through Nesty, fees included.
 */
@Composable
fun ExitPlanCard(chain: Chain, token: String, symbol: String, h: Holding, coin: Coin?, now: Long) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val wallet = AppGraph.session.active ?: return
    val address = (if (chain.isEvm) wallet.evm?.lowercase() else wallet.sol) ?: return
    if (!AppGraph.trading.canTrade(chain)) return
    val exits = AppGraph.exits
    val key = ExitMath.keyOf(wallet.id, chain.feed, token)
    val plan = AppGraph.exitStore.get(key)
    val status = exits.status[key]
    var draft by remember(key) { mutableStateOf(plan?.let { ExitDraft.of(it.rules) } ?: ExitDraft()) }
    // A plan that changed on its own (a step sold) shows its steps as saved.
    LaunchedEffect(plan?.rules) { plan?.let { draft = ExitDraft.of(it.rules) } }
    var editing by rememberSaveable(key) { mutableStateOf<String?>(null) }

    // Notifications and the battery saver, read again each time Nesty comes back to the front.
    var look by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { look++ }
    val notify = remember(look) { Notifier.allowed(context) }
    val limited = remember(look) { Notifier.batteryLimited(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { look++; AppGraph.syncWatch() }
    val turnOn = rememberTurnOnNotifications { look++; AppGraph.syncWatch() }

    // What the held tokens cost, and the multiple now: the watcher's, or the price's until it checks.
    val fills = AppGraph.positions.of(address).filter { it.feed == chain.feed && Trading.sameToken(chain, it.token, token) }
    val covered = ExitMath.covered(h.held, ExitMath.costBasis(fills))
    val share = if (h.held.signum() > 0) BigDecimal(covered.tokens).divide(BigDecimal(h.held), MathContext.DECIMAL64).toDouble() else 0.0
    val priced = covered.costUsd > 0 && share > 0
    val mNow = status?.nowBps?.takeIf { it > 0 }?.let { it / 10_000.0 }
        ?: h.value?.let { v -> if (priced) v * share / covered.costUsd else null }
    val mcNow = coin?.mcapUsd?.takeIf { it > 0 }

    fun mcAt(m: Double): Double? = if (mcNow != null && mNow != null && mNow > 0) mcNow * m / mNow else null

    /** What selling all of it pays at a multiple. */
    fun worth(m: Double): Double? = if (priced) m * covered.costUsd / share else null
    fun fromNow(m: Double): String? = mNow?.takeIf { it > 0 }?.let { Fmt.pctChange((m / it - 1) * 100) + " from now" }

    fun save(next: ExitDraft) {
        draft = next
        val p = plan ?: return
        exits.set(p.copy(rules = next.rules()))
    }

    val on = plan != null
    val ruleOf = { id: String -> plan?.rules?.firstOrNull { it.id == id } }

    val cardShape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(cardShape).background(Ink.card).border(1.dp, if (on) Ink.greenLine else Ink.line, cardShape)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exit plan", style = text(16, FontWeight.Bold), modifier = Modifier.weight(1f).semantics { heading() })
                if (priced) {
                    val (bg, ink, label) = if (on) Triple(Ink.greenDeep, Ink.green, "On") else Triple(Ink.violetBg, Ink.violetSoft, "Suggested")
                    Box(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(bg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = text(12, FontWeight.Bold, ink))
                    }
                }
            }
            val sub = when {
                !priced -> "Nesty has no cost on record for what you hold, so it cannot measure multiples of it."
                on -> "Sells from this phone, even with Nesty closed."
                else -> "A starting point. Change any step, then turn it on."
            }
            Text(sub, style = text(13, color = Ink.muted, lineHeight = 18.sp))
            val paused = status?.paused?.takeIf { on && it != ExitWatcher.LOCKED }
            if (paused != null) Text(paused, style = text(13, color = Ink.amber, lineHeight = 18.sp))
        }
        if (priced) {
        val d = draft
        // Take profit.
        val tpM = d.tpX.toDouble()
        val tpGet = worth(tpM)?.let { it * d.tpPct / 100.0 }
        val stakeBack = tpGet != null && h.position.paid > 0 && abs(tpGet - h.position.paid) <= h.position.paid * 0.05
        Step(
            mark = Mark(Ink.greenDeep, Ink.green, NIcons.stepUp),
            title = if (d.tpPct >= 100) "Sell all at ${d.tpX}×" else "Sell ${d.tpPct}% at ${d.tpX}×",
            detail = listOfNotNull(
                mcAt(tpM)?.let { "MC ${Fmt.usd(it)}" },
                fromNow(tpM),
                tpGet?.let { "you get about ${Fmt.usdFull(it)}" + if (stakeBack) ", your stake back" else "" },
            ).joinToString(" · "),
            rule = ruleOf(ExitDraft.TP),
            selling = status?.selling == ExitDraft.TP,
            now = now,
            open = editing == ExitDraft.TP,
            onEdit = { editing = if (editing == ExitDraft.TP) null else ExitDraft.TP },
        ) {
            Options("Amount", ExitDraft.TP_PCT.map { "$it%" }, ExitDraft.TP_PCT.indexOf(d.tpPct)) { save(d.copy(tpPct = ExitDraft.TP_PCT[it])) }
            Options("At", ExitDraft.TP_X.map { "$it×" }, ExitDraft.TP_X.indexOf(d.tpX)) { save(d.copy(tpX = ExitDraft.TP_X[it])) }
        }

        // Trailing stop: from the highest multiple since the plan was set (now, before it is on).
        val t = d.trailPct / 100.0
        val peakM = plan?.peakBps?.takeIf { it > 0 }?.let { it / 10_000.0 }?.let { p -> mNow?.let { maxOf(p, it) } ?: p } ?: mNow
        Step(
            mark = Mark(Ink.pinkBg, Ink.pinkSoft, NIcons.stepTrail),
            title = if (d.trailOn) "Sell all if it falls ${d.trailPct}% from its high" else "Trailing stop is off",
            titleColor = if (d.trailOn) Ink.text else Ink.muted,
            detail = if (d.trailOn && peakM != null) {
                listOfNotNull(
                    mcAt(peakM)?.let { "High so far MC ${Fmt.usd(it)}" },
                    listOfNotNull(
                        mcAt(peakM * (1 - t))?.let { "sells at ${Fmt.usd(it)}" },
                        worth(peakM * (1 - t))?.let { "you get about ${Fmt.usdFull(it)}" },
                    ).joinToString(", ").ifBlank { null },
                ).joinToString(" · ")
            } else if (d.trailOn) {
                "Its high counts from when the plan is on"
            } else {
                "Turn it on to sell when $symbol falls from its best price"
            },
            rule = ruleOf(ExitDraft.TRAIL),
            selling = status?.selling == ExitDraft.TRAIL,
            now = now,
            open = editing == ExitDraft.TRAIL,
            onEdit = { editing = if (editing == ExitDraft.TRAIL) null else ExitDraft.TRAIL },
        ) {
            Options("Trailing stop", listOf("Off", "On"), if (d.trailOn) 1 else 0) { save(d.copy(trailOn = it == 1)) }
            Options("Falls from its high by", ExitDraft.TRAIL_PCT.map { "$it%" }, if (d.trailOn) ExitDraft.TRAIL_PCT.indexOf(d.trailPct) else -1) {
                save(d.copy(trailOn = true, trailPct = ExitDraft.TRAIL_PCT[it]))
            }
        }

        // Stop loss.
        val slM = 1 - d.slPct / 100.0
        Step(
            mark = Mark(Ink.pinkBg, Ink.pink, NIcons.stepDown),
            title = "Sell all at −${d.slPct}%",
            detail = listOfNotNull(
                mcAt(slM)?.let { "MC ${Fmt.usd(it)}" },
                fromNow(slM),
                worth(slM)?.let { "you get about ${Fmt.usdFull(it)}" },
            ).joinToString(" · "),
            rule = ruleOf(ExitDraft.SL),
            selling = status?.selling == ExitDraft.SL,
            now = now,
            open = editing == ExitDraft.SL,
            onEdit = { editing = if (editing == ExitDraft.SL) null else ExitDraft.SL },
        ) {
            Options("Down from your entry by", ExitDraft.SL_PCT.map { "$it%" }, ExitDraft.SL_PCT.indexOf(d.slPct)) { save(d.copy(slPct = ExitDraft.SL_PCT[it])) }
        }

        if (on && !notify) {
            Divider()
            AmberNote("Notifications are off, so you only see a sale in Nesty.", "Turn on", turnOn)
        }
        if (on && limited) {
            Divider()
            AmberNote("Battery saver can stop the watch on some phones.", "Allow background use") { Notifier.askBackgroundUse(context) }
        }

        Divider()
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(shape)
                    .background(if (on) Ink.raised else Ink.green)
                    .then(if (on) Modifier.border(1.dp, Ink.line3, shape) else Modifier)
                    .clickable(role = Role.Button) {
                        if (on) {
                            exits.remove(key)
                            toasts.show("Exit plan off", "Nothing sells by itself now.")
                        } else if (!exits.roomFor(key)) {
                            toasts.show("No room for another exit plan", "${ExitMath.MAX_PLANS} are on already. Turn one off first.", bad = true)
                        } else {
                            editing = null
                            exits.set(
                                ExitPlan(
                                    walletId = wallet.id,
                                    wallet = address,
                                    feed = chain.feed,
                                    token = if (chain.isEvm) token.lowercase() else token,
                                    symbol = symbol,
                                    decimals = h.position.decimals,
                                    rules = draft.rules(),
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            AppGraph.syncWatch()
                            toasts.show("Exit plan on", "It sells from this phone when $symbol gets there.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notify) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (on) "Turn off exit plan" else "Turn on exit plan", style = text(16, FontWeight.Bold, if (on) Ink.text else Ink.greenInk))
            }
        }
        }
    }
}

private data class Mark(val bg: Color, val ink: Color, val icon: ImageVector)

@Composable
private fun Step(
    mark: Mark,
    title: String,
    detail: String,
    rule: ExitRule?,
    selling: Boolean,
    now: Long,
    open: Boolean,
    onEdit: () -> Unit,
    titleColor: Color = Ink.text,
    options: @Composable () -> Unit,
) {
    // On, a step that sold, or failed, says so in place of its figures.
    val sold = rule?.firedAt
    val shown = when {
        selling -> Mark(Ink.violetBg, Ink.violet, NIcons.tonePlan) to "Selling now…"
        sold != null -> Mark(Ink.greenOk, Ink.green, NIcons.toneOk) to
            ("Sold" + (rule.firedBps?.let { " at ${ExitMath.times(it)}" } ?: "") + " · ${Fmt.age(now - sold)} ago")
        rule != null && rule.failed -> Mark(Ink.pinkBg, Ink.pink, NIcons.toneBad) to "Could not sell: ${rule.error ?: "it did not go through"}. Change the step to try again."
        rule != null && rule.tries > 0 -> mark to "Tried ${rule.tries} of ${ExitMath.TRIES}: ${rule.error ?: "it did not go through"}. It tries again."
        else -> mark to detail
    }
    Column {
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val m = shown.first
            Box(Modifier.size(18.dp).clip(CircleShape).background(m.bg), contentAlignment = Alignment.Center) {
                Icon(m.icon, contentDescription = null, tint = m.ink, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = text(15, FontWeight.Bold, if (sold != null) Ink.text2 else titleColor))
                if (shown.second.isNotBlank()) Text(shown.second, style = text(13, color = Ink.muted, lineHeight = 18.sp))
            }
            if (sold == null && !selling) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 64.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Ink.raised)
                        .border(1.dp, Ink.line2, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = onEdit)
                        .semantics { stateDescription = if (open) "Open" else "Closed" }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (open) "Done" else "Edit", style = text(14, FontWeight.Bold))
                }
            }
        }
        // The step's choices open right under it.
        if (open && sold == null && !selling) {
            Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 16.dp, top = 2.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                options()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(label: String, labels: List<String>, picked: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = text(12, FontWeight.SemiBold, Ink.muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l ->
                val on = i == picked
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 58.dp)
                        .height(44.dp)
                        .clip(shape)
                        .background(if (on) Ink.violetBg else Ink.raised)
                        .border(1.dp, if (on) Ink.violet else Ink.line2, shape)
                        .clickable(role = Role.Button) { onPick(i) }
                        .semantics { selected = on }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(l, style = figure(16, FontWeight.SemiBold, if (on) Ink.text else Ink.text2))
                }
            }
        }
    }
}
