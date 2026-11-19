package trade.nesty.app.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import trade.nesty.app.i18n.I18n
import trade.nesty.app.i18n.Lang
import trade.nesty.app.i18n.tr
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.NestySheet
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/**
 * The menu's Language: the one Nesty shows, the phone's own until another is picked. The row opens
 * a sheet over the menu with each language by its own name, in alphabetical order, the one in use
 * checked; a pick closes it.
 */
@Composable
fun LanguageSetting() {
    var open by rememberSaveable { mutableStateOf(false) }
    val now = I18n.lang
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(now.label, style = text(15, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
    }
    if (open) {
        NestySheet(tr("Language"), onClose = { open = false }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp).selectableGroup()) {
                Lang.entries.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    Choice(l.label, on = l == now) {
                        // The phone's own language is kept as no pick, so Nesty follows the phone again.
                        I18n.pick(if (l == I18n.phoneLang()) null else l)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = on, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = text(15, if (on) FontWeight.SemiBold else FontWeight.Medium, if (on) Ink.text else Ink.text2), modifier = Modifier.weight(1f))
        if (on) Icon(NIcons.check, contentDescription = null, tint = Ink.green, modifier = Modifier.size(20.dp))
    }
}
