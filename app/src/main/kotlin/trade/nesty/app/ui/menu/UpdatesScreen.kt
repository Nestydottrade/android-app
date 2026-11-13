package trade.nesty.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Update
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Card
import trade.nesty.app.ui.Chip
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.uniqueItems
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/** What an entry is about, as the app files it: App for the app, Data for what the server reads. */
fun kindOf(u: Update): String? = when {
    "app" in u.tags || "android" in u.tags -> "App"
    "website" in u.tags -> "Data"
    else -> null
}

/** The chains an entry names, or All chains. */
fun chainsOf(u: Update): String {
    val list = u.chains ?: return "All chains"
    val names = list.mapNotNull { Chain.ofSlug(it)?.label }
    return if (names.isEmpty()) "All chains" else names.joinToString(" · ")
}

@Composable
fun UpdatesScreen() {
    val nav = LocalNavigator.current
    var list by remember { mutableStateOf<List<Update>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { AppGraph.api.updates(200).updates }.onSuccess { list = it }.onFailure { failed = true }
    }
    // Entries only about the browser extension are left out: they do not change the app.
    val shown = list.orEmpty().filter { u ->
        val k = kindOf(u) ?: return@filter false
        filter == 0 || (filter == 1 && k == "App") || (filter == 2 && k == "Data")
    }
    val days = shown.groupBy { it.day }.toList()

    Column(Modifier.fillMaxSize()) {
        BackHeader("Updates", onBack = { nav.pop() })
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "App", "Data").forEachIndexed { i, label -> Chip(label, filter == i, { filter = i }) }
        }
        Divider()
        when {
            list == null && failed -> EmptyNote("Could not reach Nesty. Try again later.")
            list == null -> EmptyNote("Loading…")
            days.isEmpty() -> EmptyNote("Nothing here yet.")
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                uniqueItems(days, { it.first }) { (day, entries) ->
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Fmt.day(day), style = text(13, FontWeight.SemiBold, Ink.muted), modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
                        Card {
                            entries.forEachIndexed { i, u ->
                                if (i > 0) Divider()
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(u.title, style = text(15, FontWeight.Bold), modifier = Modifier.weight(1f))
                                        val k = kindOf(u) ?: ""
                                        Box(
                                            Modifier
                                                .height(22.dp)
                                                .background(if (k == "App") Ink.violetBg else Ink.track, RoundedCornerShape(11.dp))
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) { Text(k, style = text(12, FontWeight.Bold, if (k == "App") Ink.violetSoft else Ink.text2)) }
                                    }
                                    Text(u.text, style = text(13, color = Ink.text2, lineHeight = 19.sp))
                                    Text(chainsOf(u), style = text(12, color = Ink.muted))
                                }
                            }
                        }
                    }
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }
}
