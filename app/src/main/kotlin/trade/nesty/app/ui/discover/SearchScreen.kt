package trade.nesty.app.ui.discover

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ListToken
import trade.nesty.app.ui.CoinAvatar
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.IconButton48
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.pctColor
import trade.nesty.app.ui.signColor
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.venueName

private sealed interface Found {
    data object Idle : Found
    data object Looking : Found
    data class Some(val list: List<ListToken>, val partial: Boolean = false) : Found
    data object Failed : Found
}

/** Search by name, symbol or a pasted address, on every chain at once. No board yet: kept plain. */
@Composable
fun SearchScreen() {
    val nav = LocalNavigator.current
    // The text with its cursor, so coming back from a coin keeps the cursor at the end.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val q = field.text
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    var found by remember { mutableStateOf<Found>(Found.Idle) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The keyboard opens on the first visit only, not on the way back from a coin.
    LaunchedEffect(Unit) {
        if (!focusedOnce) runCatching { focus.requestFocus() }
        focusedOnce = true
    }
    LaunchedEffect(q) {
        val term = q.trim()
        if (term.length < 2) {
            found = Found.Idle
            return@LaunchedEffect
        }
        delay(350)
        found = Found.Looking
        // A pasted address the search does not list is asked for on each chain it can be on.
        found = CoinSearch.find(AppGraph.api, term)?.let { Found.Some(it.rows, it.partial) } ?: Found.Failed
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton48(NIcons.arrowBack, "Back", Ink.text) { nav.pop() }
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(Ink.raised, RoundedCornerShape(22.dp))
                    .border(1.dp, Ink.line2, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(NIcons.search, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Name, symbol or address", style = text(15, color = Ink.muted))
                    BasicTextField(
                        value = field,
                        onValueChange = { field = if (it.text.length > 120) it.copy(text = it.text.take(120)) else it },
                        singleLine = true,
                        textStyle = text(15),
                        cursorBrush = SolidColor(Ink.green),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search coins or paste an address" },
                    )
                }
                if (q.isNotEmpty()) {
                    Icon(
                        NIcons.close,
                        contentDescription = "Clear",
                        tint = Ink.muted,
                        modifier = Modifier.size(18.dp).clickable(role = Role.Button) { field = TextFieldValue("") },
                    )
                }
            }
        }
        Divider()
        when (val f = found) {
            Found.Idle -> EmptyNote("Search every chain by name or symbol, or paste a coin's address.")
            Found.Looking -> EmptyNote("Looking…")
            Found.Failed -> EmptyNote("Could not reach Nesty. Try again.")
            is Found.Some -> if (f.list.isEmpty()) EmptyNote("No coin found.") else LazyColumn(Modifier.fillMaxSize()) {
                items(f.list, key = { it.feed + ":" + it.address.lowercase() }) { t ->
                    val chain = Chain.of(t.feed) ?: return@items
                    SearchRow(t, chain) {
                        keyboard?.hide()
                        nav.push(Route.Token(chain, t.address))
                    }
                }
                if (f.partial) {
                    item {
                        Text(
                            "Some coins may be missing. Try again in a moment.",
                            style = text(12, color = Ink.muted),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(t: ListToken, chain: Chain, onClick: () -> Unit) {
    val symbol = t.symbol?.takeIf { it.isNotBlank() } ?: t.name ?: "?"
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable(onClickLabel = "Open $symbol", role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoinAvatar(t.image, symbol, t.address, chain, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(symbol, style = text(16, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The name gives way first: where it trades and the chain tell coins of one symbol apart.
                val name = t.name?.takeIf { it.isNotBlank() && it != symbol }
                val where = listOfNotNull(venueName(t.dex), chain.title).joinToString(" · ")
                Row {
                    if (name != null) {
                        Text(name, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" · ", style = text(13, color = Ink.muted))
                    }
                    Text(where, style = text(13, color = Ink.muted), maxLines = 1)
                }
            }
            // A launchpad coin the server has only trades of comes without a market cap: nothing is written for it.
            if (t.mcapUsd != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Fmt.usd(t.mcapUsd), style = figure(17, FontWeight.SemiBold, Ink.green))
                    t.change?.h24?.let { Text(Fmt.pctChange(it) + " 24h", style = text(12, FontWeight.Bold, pctColor(it))) }
                }
            }
        }
        Divider()
    }
}
