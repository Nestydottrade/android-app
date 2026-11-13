package trade.nesty.app.ui.menu

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.AppRelease
import trade.nesty.app.data.Settings
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

@Composable
fun MenuScreen() {
    val nav = LocalNavigator.current
    val settings = AppGraph.settings
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        release = runCatching { AppGraph.api.app() }.getOrNull()
        asked = true
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
                        Text("$0.00", style = figure(24, FontWeight.SemiBold))
                    }
                    Text("No wallet yet", style = text(13, color = Ink.text2))
                }
            }

            Section("Trading") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Quick buy amount", style = text(15, FontWeight.SemiBold))
                        Text("One tap on a list buys this much", style = text(13, color = Ink.muted))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (q in Settings.QUICK_STEPS) {
                            val on = settings.quickBuy == q
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) Ink.violetBg else Ink.raised)
                                    .border(1.dp, if (on) Ink.violet else Ink.line2, RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { settings.pickQuickBuy(q) }
                                    .semantics { selected = on },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$$q", style = text(15, FontWeight.Bold, if (on) Ink.text else Ink.text2))
                            }
                        }
                    }
                }
            }

            Section("More") {
                MenuLink("Stats", "Launches and trades on each chain, from 1 hour to 7 days") { nav.push(Route.Stats) }
                Divider()
                MenuLink("Updates", "What changed in Nesty, newest first") { nav.push(Route.Updates) }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nesty for Android ${BuildConfigLite.VERSION}", style = text(15, FontWeight.SemiBold))
                    val r = release
                    Text(
                        when {
                            r != null && r.code > BuildConfigLite.CODE -> "Version ${r.version} is ready to install"
                            asked -> "This build reads only: wallets and trading come next"
                            else -> " "
                        },
                        style = text(13, color = Ink.muted, lineHeight = 18.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
