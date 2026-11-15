package trade.nesty.app.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.alerts.Alert
import trade.nesty.app.alerts.AlertLink
import trade.nesty.app.alerts.AlertTone
import trade.nesty.app.alerts.Alerts
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Chip
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.Gap
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Navigator
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.uniqueItems

/** Where an alert's button goes, as a screen of the app. */
fun routeOf(link: AlertLink): Route? {
    val chain = Chain.of(link.feed)
    val token = link.token
    return when (link.to) {
        AlertLink.POSITION -> if (chain != null && token != null) Route.Position(chain, token) else null
        AlertLink.TOKEN -> if (chain != null && token != null) Route.Token(chain, token) else null
        AlertLink.PORTFOLIO -> Route.Portfolio
        AlertLink.ALERTS -> Route.Alerts
        else -> null
    }
}

/** Opens an alert's screen: a tab as a tab, anything else over the screen in front. */
fun Navigator.open(link: AlertLink) {
    val r = routeOf(link) ?: return
    when {
        r.isTab -> tab(r)
        top.route != r -> push(r)
    }
}

private fun labelOf(link: AlertLink): String? = when (link.to) {
    AlertLink.POSITION -> "Your position"
    AlertLink.TOKEN -> "Open coin"
    AlertLink.PORTFOLIO -> "Portfolio"
    else -> null
}

private data class Look(val bg: Color, val ink: Color, val icon: ImageVector)

private fun lookOf(tone: AlertTone): Look = when (tone) {
    AlertTone.BAD -> Look(Ink.pinkBg, Ink.pink, NIcons.toneBad)
    AlertTone.WARN -> Look(Ink.amberBg, Ink.amber, NIcons.toneWarn)
    AlertTone.WALLETS -> Look(Ink.violetBg, Ink.violet, NIcons.toneWallets)
    AlertTone.SIGNAL -> Look(Ink.violetBg, Ink.violet, NIcons.toneSignal)
    AlertTone.PLAN -> Look(Ink.violetBg, Ink.violet, NIcons.tonePlan)
    AlertTone.OK -> Look(Ink.greenOk, Ink.green, NIcons.toneOk)
    AlertTone.INFO -> Look(Ink.track, Ink.muted, NIcons.toneInfo)
}

/** The bell of the tab screens' top bar, with a green dot while there are new alerts. */
@Composable
fun BellButton() {
    val nav = LocalNavigator.current
    val unread = AppGraph.alertStore.unread
    val label = if (unread > 0) "Notifications, $unread new" else "Notifications"
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Open notifications", role = Role.Button) { nav.push(Route.Alerts) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(NIcons.bell, contentDescription = null, tint = Ink.text2, modifier = Modifier.size(22.dp))
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 12.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Ink.ground)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Ink.green),
            )
        }
    }
}

@Composable
fun AlertsScreen() {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val store = AppGraph.alertStore
    val settings = AppGraph.settings
    var filter by rememberSaveable { mutableStateOf(Alerts.Filter.ALL) }
    val now = rememberNow(30_000)
    val shown = store.list.filter { filter.has(it) }
    val fresh = shown.filter { !it.read }
    val seen = shown.filter { it.read }
    val unread = store.unread

    fun open(a: Alert) {
        store.markRead(a.id)
        a.link?.let { nav.open(it) }
    }

    fun mute(a: Alert) {
        val who = a.who ?: return
        val name = settings.nameOf(who) ?: Fmt.address(who)
        val was = settings.isMuted(who)
        settings.mute(who, !was)
        toasts.show(
            if (was) "$name unmuted" else "$name muted",
            if (was) "Their trades alert you again" else "Their trades still show in Tracker, with no alerts",
            action = "Undo",
        ) { settings.mute(who, was) }
    }

    Column(Modifier.fillMaxSize()) {
        BackHeader("Notifications", onBack = { nav.pop() }) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(enabled = unread > 0, role = Role.Button) {
                        val ids = store.markAllRead()
                        toasts.show("All marked read", "${ids.size} moved to Seen", action = "Undo") { store.markUnread(ids) }
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Mark all read", style = text(14, FontWeight.Bold, if (unread > 0) Ink.green else Ink.muted))
            }
        }
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in Alerts.Filter.entries) Chip(f.label, filter == f, { filter = f })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            item(key = "new") { Heading("New") }
            uniqueItems(fresh, { it.id }) { a ->
                Box(Modifier.padding(bottom = 8.dp)) {
                    AlertCard(a, now, onOpen = { open(a) }, onMute = { mute(a) }, muted = settings.isMuted(a.who))
                }
            }
            if (fresh.isEmpty()) item(key = "none") { NothingNew() }
            if (seen.isNotEmpty()) {
                item(key = "seen") { Box(Modifier.padding(top = 8.dp)) { Heading("Seen") } }
                uniqueItems(seen, { it.id }) { a ->
                    AlertRow(a, now, onOpen = { open(a) }, onMute = { mute(a) }, muted = settings.isMuted(a.who))
                }
            }
            item(key = "choose") {
                Column {
                    Gap(16.dp)
                    ChooseRow { nav.push(Route.Menu(alerts = true)) }
                }
            }
        }
    }
}

@Composable
private fun Heading(label: String) {
    Text(label, style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

@Composable
private fun NothingNew() {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    Ink.line2,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Nothing new. You are up to date.", style = text(14, color = Ink.muted), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Mark(tone: AlertTone) {
    val look = lookOf(tone)
    Box(Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape).background(look.bg), contentAlignment = Alignment.Center) {
        Icon(look.icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(22.dp))
    }
}

/** The title, its age and what it says; a muted wallet's alerts say so. */
@Composable
private fun Words(a: Alert, now: Long, fresh: Boolean, muted: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(a.title, style = text(15, if (fresh) FontWeight.Bold else FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text(Fmt.age(now - a.at), style = text(12, color = Ink.muted), modifier = Modifier.padding(top = 2.dp))
    }
    val name = a.who?.let { AppGraph.settings.nameOf(it) ?: Fmt.address(it) }
    val body = if (muted && name != null) "${a.body} Alerts from $name are muted.".trim() else a.body
    if (body.isNotBlank()) Text(body, style = text(13, color = if (fresh) Ink.text2 else Ink.muted, lineHeight = 19.sp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(a: Alert, fresh: Boolean, muted: Boolean, onOpen: () -> Unit, onMute: () -> Unit) {
    val label = a.link?.let { labelOf(it) }
    if (label == null && a.who == null) return
    FlowRow(Modifier.padding(top = if (fresh) 6.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(if (fresh) Ink.raised else Ink.card)
                    .border(1.dp, if (fresh) Ink.line3 else Ink.line2, shape)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = text(14, FontWeight.Bold))
            }
        }
        val who = a.who
        if (who != null && AppGraph.settings.isFollowed(who)) {
            val name = AppGraph.settings.nameOf(who) ?: Fmt.address(who)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onMute)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (muted) "Unmute $name" else "Mute $name", style = text(14, FontWeight.Bold, Ink.text2))
            }
        }
    }
}

@Composable
private fun AlertCard(a: Alert, now: Long, onOpen: () -> Unit, onMute: () -> Unit, muted: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.card)
            .border(1.dp, Ink.line2, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(a.tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Words(a, now, fresh = true, muted = muted)
                Buttons(a, fresh = true, muted = muted, onOpen = onOpen, onMute = onMute)
            }
        }
        // New: a violet edge on the left.
        Box(Modifier.matchParentSize()) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Ink.violet))
        }
    }
}

@Composable
private fun AlertRow(a: Alert, now: Long, onOpen: () -> Unit, onMute: () -> Unit, muted: Boolean) {
    Column {
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(a.tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Words(a, now, fresh = false, muted = muted)
                Buttons(a, fresh = false, muted = muted, onOpen = onOpen, onMute = onMute)
            }
        }
    }
}

@Composable
private fun ChooseRow(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(Ink.card)
            .border(1.dp, Ink.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Choose what alerts you", style = text(15, FontWeight.SemiBold))
            Text("Tracker, signals, your coins and exit plans", style = text(13, color = Ink.muted))
        }
        Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(20.dp))
    }
}
