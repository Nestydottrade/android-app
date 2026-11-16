package trade.nesty.app.ui.menu

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Settings
import trade.nesty.app.data.VaultState
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.alerts.AlertSettings
import trade.nesty.app.ui.vault.ExportForm
import trade.nesty.app.ui.vault.Fingerprint
import trade.nesty.app.ui.vault.FingerprintSetting
import trade.nesty.app.vault.WalletInfo
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.update.UpdateState

/** `atAlerts`: scrolled to the Alerts section, for "Choose what alerts you". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuScreen(atAlerts: Boolean = false) {
    val nav = LocalNavigator.current
    val settings = AppGraph.settings
    val session = AppGraph.session
    val wallet = session.active
    val update by AppGraph.updater.state.collectAsState()
    LaunchedEffect(Unit) { AppGraph.updater.look(fetchNow = false) }
    val alerts = remember { BringIntoViewRequester() }
    // Once: coming back to the menu from Stats or Updates keeps where the user scrolled.
    var placed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (atAlerts && !placed) alerts.bringIntoView()
        placed = true
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton48(NIcons.close, "Close menu", Ink.text) { nav.pop() }
            Text("Nesty", style = text(19, FontWeight.Bold), modifier = Modifier.semantics { heading() })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.clickable(role = Role.Button) { nav.tab(Route.Portfolio) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("One Balance", style = text(15, FontWeight.Bold))
                            Text("Portfolio and deposit", style = text(13, color = Ink.muted))
                        }
                        Text(oneBalance(), style = figure(24, FontWeight.SemiBold))
                    }
                    if (wallet == null) {
                        Text("No wallet yet", style = text(13, color = Ink.text2))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            wallet.evm?.let { Text("EVM chains: ${Fmt.address(it)}", style = text(13, color = Ink.text2)) }
                            wallet.sol?.let { Text("Solana: ${Fmt.address(it)}", style = text(13, color = Ink.text2)) }
                        }
                    }
                }
            }

            Card {
                val count = session.wallets.size
                when {
                    session.state == VaultState.NONE ->
                        MenuLink("Set up a wallet", "Make a password, then create or import one") { nav.push(Route.Setup) }
                    wallet == null ->
                        MenuLink("Add your first wallet", "Create one or import one") { nav.push(Route.Wallets(adding = true)) }
                    else ->
                        MenuLink("Trading with ${wallet.name}", "${Fmt.counted(count.toLong(), "wallet")} · switch or make a new one") { nav.push(Route.Wallets()) }
                }
            }

            Section("Trading") { QuickBuySetting() }

            Section("Alerts", Modifier.bringIntoViewRequester(alerts)) { Column { AlertSettings() } }

            Section("More") {
                MenuLink("Stats", "Launches and trades on each chain, from 1 hour to 7 days") { nav.push(Route.Stats) }
                Divider()
                MenuLink("Updates", "What changed in Nesty, newest first") { nav.push(Route.Updates) }
            }

            if (session.state == VaultState.OPEN) {
                Section("Security") { Security(wallet) }
            }

            Card {
                val r = update.release
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Nesty for Android ${BuildConfigLite.VERSION}", style = text(15, FontWeight.SemiBold))
                        Text(
                            when {
                                update is UpdateState.Ready -> "Version ${r?.version} is ready to install"
                                r != null -> "Version ${r.version} is out"
                                update is UpdateState.Newest -> "This is the newest version"
                                else -> " "
                            },
                            style = text(13, color = Ink.muted, lineHeight = 18.sp),
                        )
                    }
                    if (r != null) {
                        Box(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Ink.green)
                                .clickable(role = Role.Button) { nav.push(Route.AppUpdate) }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Update", style = text(14, FontWeight.Bold, Ink.greenInk))
                        }
                    }
                }
            }
        }
    }
}

/** When the wallets lock by themselves, a lock right now, and a key's export behind the password. */
@Composable
private fun Security(wallet: WalletInfo?) {
    val settings = AppGraph.settings
    val session = AppGraph.session
    var exporting by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Lock after", style = text(15, FontWeight.SemiBold))
            Text(
                if (session.fingerprintOn) "Time away from the app. Your fingerprint or password opens Nesty again." else "Time away from the app. Your password opens Nesty again.",
                style = text(13, color = Ink.muted),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in Settings.LOCK_STEPS) {
                val on = settings.lockAfterMinutes == minutes
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Ink.violetBg else Ink.raised)
                        .border(1.dp, if (on) Ink.violet else Ink.line2, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { settings.pickLockAfter(minutes) }
                        .semantics { selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (minutes >= 60) "1 hour" else "$minutes min", style = text(15, FontWeight.Bold, if (on) Ink.text else Ink.text2))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { session.lock() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Lock now", style = text(15, FontWeight.Bold))
        }
    }
    if (Fingerprint.can(LocalContext.current) != Fingerprint.Can.NO) {
        Divider()
        FingerprintSetting()
    }
    if (wallet != null) {
        Divider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Export a private key", style = text(15, FontWeight.SemiBold))
                    Text("Of ${wallet.name}. Asks for your password first.", style = text(13, color = Ink.muted))
                }
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink.raised)
                        .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { exporting = !exporting }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (exporting) "Hide" else "Show", style = text(14, FontWeight.Bold))
                }
            }
            // The form opens right under its row.
            if (exporting) ExportForm(wallet, onClose = { exporting = false })
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Card { content() }
    }
}

@Composable
private fun MenuLink(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = text(15, FontWeight.SemiBold))
            Text(sub, style = text(13, color = Ink.muted))
        }
        Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
    }
}
