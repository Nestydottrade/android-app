package trade.nesty.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.AppRelease
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.FigureText
import trade.nesty.app.ui.GhostButton
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.Meter
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.trade.BottomBarFrame
import trade.nesty.app.update.Releases
import trade.nesty.app.update.UpdateState

/**
 * The app's own update, as the AppUpdate board draws it: the newest version, what it brings,
 * what the phone checked, then Install update, which hands it to Android. After an update the
 * new version says it is installed, once.
 */
@Composable
fun AppUpdateScreen() {
    val nav = LocalNavigator.current
    val updater = AppGraph.updater
    val state by updater.state.collectAsState()
    val installed = remember { updater.justUpdated }
    LaunchedEffect(Unit) { if (installed == null) updater.look(fetchNow = true) }
    DisposableEffect(Unit) { onDispose { if (installed != null) updater.seenUpdated() } }

    Column(Modifier.fillMaxSize()) {
        BackHeader("Update", onBack = { nav.pop() })
        Divider()
        if (installed != null) {
            Installed(installed) {
                updater.seenUpdated()
                nav.tab(Route.Discover)
            }
            return@Column
        }
        val s = state
        val r = s.release
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FigureText("Nesty ${r?.version ?: BuildConfigLite.VERSION}", 34)
                Text(lineOf(s), style = text(14, color = Ink.muted, lineHeight = 20.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (s is UpdateState.Fetching && r != null) {
                    Meter(s.bytes.toDouble() / r.size, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            if (s is UpdateState.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                    ToneMark(Tone.BAD, 18.dp)
                    Text(s.why, style = text(14, color = Ink.text2, lineHeight = 20.sp), modifier = Modifier.weight(1f))
                }
            }
            if (r != null && r.notes.isNotEmpty()) {
                Part("New in this version") {
                    r.notes.forEachIndexed { i, n ->
                        if (i > 0) Divider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.title, style = text(15, FontWeight.Bold))
                            if (n.text.isNotBlank()) Text(n.text, style = text(13, color = Ink.muted, lineHeight = 18.sp))
                        }
                    }
                }
            }
            if (s is UpdateState.Ready || s is UpdateState.Installing) {
                Checked(r!!, signerRead = (s as? UpdateState.Ready)?.signerRead ?: true)
            }
            if (r == null && (s is UpdateState.Newest || s is UpdateState.Unreachable)) {
                GhostButton("Check again", onClick = { updater.retry() }, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (r != null) {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Nesty changes only through updates you install. It never downloads code while it runs. Your wallets and settings stay on this phone.",
                        style = text(13, color = Ink.muted, lineHeight = 19.sp),
                    )
                    if (AppGraph.exitStore.plans.isNotEmpty()) {
                        Text(
                            "Your exit plans stop while Android installs it, and sell again once you unlock Nesty.",
                            style = text(13, color = Ink.muted, lineHeight = 19.sp),
                        )
                    }
                }
            }
        }
        if (r != null) {
            BottomBarFrame {
                when (s) {
                    is UpdateState.Ready -> GreenButton("Install update", onClick = { updater.installNow() }, modifier = Modifier.fillMaxWidth(), solid = true, height = 56.dp)
                    is UpdateState.Failed -> GreenButton("Try again", onClick = { updater.retry() }, modifier = Modifier.fillMaxWidth(), solid = true, height = 56.dp)
                    else -> Waiting(if (s is UpdateState.Installing) "Installing…" else "Downloading…")
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { nav.pop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Later", style = text(15, FontWeight.Bold, Ink.text2))
                }
            }
        }
    }
}

/** Where the button goes while the file is on its way: a quiet bar, not a button. */
@Composable
private fun Waiting(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.greenDeep)
            .border(1.dp, Ink.greenLine, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(17, FontWeight.Bold, Ink.greenDim))
    }
}

/** The line under the version: what the phone has and where the file is. */
private fun lineOf(s: UpdateState): String {
    val have = "You have ${BuildConfigLite.VERSION}"
    val r = s.release
    return when (s) {
        UpdateState.Unknown, UpdateState.Asking -> "Asking nesty.trade for a newer version…"
        UpdateState.Newest -> "This is the newest version."
        UpdateState.Unreachable -> "nesty.trade could not be reached. Check your connection."
        is UpdateState.Out -> "$have · ${Releases.sizeText(r!!.size)}"
        is UpdateState.Fetching -> "$have · ${Releases.sizeText(r!!.size)} · downloading ${(s.bytes * 100 / r.size.coerceAtLeast(1)).coerceIn(0, 100)}%"
        is UpdateState.Ready -> "$have · ${Releases.sizeText(r!!.size)} · already downloaded"
        is UpdateState.Failed -> "$have · ${Releases.sizeText(r!!.size)}"
        is UpdateState.Installing -> "$have · Android is installing it"
    }
}

/** A titled card of rows, as the board's two lists. */
@Composable
private fun Part(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Card(content = content)
    }
}

/** What the phone checked of the file before Android is asked. */
@Composable
private fun Checked(r: AppRelease, signerRead: Boolean) {
    val fromSite = r.url.startsWith("https://${Releases.HOST}/")
    Part("Checked on this phone") {
        CheckRow(if (fromSite) "Came from nesty.trade" else "Came from the test server", if (fromSite) "The address is built into the app" else "A debug build's test server on this computer")
        Divider()
        CheckRow("Matches the published fingerprint", null, figure = Releases.shortHash(r.sha256))
        Divider()
        CheckRow(
            "Signed with Nesty's key",
            if (signerRead) "Android refuses an update signed by anyone else" else "Android checks it as it installs and refuses any other key",
        )
    }
}

@Composable
private fun CheckRow(title: String, sub: String?, figure: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp)) { ToneMark(Tone.OK, 20.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = text(15, FontWeight.SemiBold))
            if (sub != null) Text(sub, style = text(13, color = Ink.muted, lineHeight = 18.sp))
            if (figure != null) FigureText(figure, 14, Ink.muted)
        }
    }
}

/** The new version's first word: it is in, and nothing of the user's moved. */
@Composable
private fun ColumnScope.Installed(version: String, onOpen: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(NIcons.doneCircle, contentDescription = null, tint = Ink.green, modifier = Modifier.size(64.dp))
        Text("Nesty $version is installed", style = text(24, FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text("Your wallets, positions and exit plans are as you left them.", style = text(15, color = Ink.text2, lineHeight = 22.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        GreenButton("Open Nesty", onClick = onOpen, modifier = Modifier.fillMaxWidth(), solid = true, height = 56.dp)
    }
}
