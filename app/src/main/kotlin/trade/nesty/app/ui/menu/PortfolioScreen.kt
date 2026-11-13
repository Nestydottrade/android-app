package trade.nesty.app.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TabHeader
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

/** Wallets come in the next build; until then the tab says so plainly. */
@Composable
fun PortfolioScreen() {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        TabHeader("Portfolio", onMenu = { nav.push(Route.Menu) })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("One Balance", style = text(13, color = Ink.muted))
                Text("$0.00", style = figure(44, FontWeight.SemiBold))
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No wallet yet", style = text(16, FontWeight.Bold))
                    Text(
                        "This build reads only. Creating or importing a wallet, deposits and trading come in the next builds.",
                        style = text(13, color = Ink.text2, lineHeight = 18.sp),
                    )
                }
            }
        }
    }
}
