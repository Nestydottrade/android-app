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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Settings
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

/**
 * The quick buy amounts: one is the list amount (one tap on a list buys it), all four are a coin
 * page's one-tap buys and the buy sheet's chips. Edit opens their form under the chips.
 */
@Composable
fun QuickBuySetting() {
    val settings = AppGraph.settings
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Quick buy amount", style = text(15, FontWeight.SemiBold))
                Text("One tap on a list buys this much", style = text(13, color = Ink.muted))
            }
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = if (editing) "Close the amounts" else "Edit the amounts", role = Role.Button) { editing = !editing }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (editing) "Close" else "Edit", style = text(14, FontWeight.Bold, Ink.green))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (q in settings.quickAmounts) {
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
                    Text("$$q", style = text(15, FontWeight.Bold, if (on) Ink.text else Ink.text2), maxLines = 1)
                }
            }
        }
        if (editing) {
            AmountsEditor(settings.quickAmounts) { amounts ->
                if (settings.setQuickAmounts(amounts)) {
                    editing = false
                    null
                } else {
                    "Use four different amounts, from $1 to ${Fmt.usdFull(Settings.QUICK_MAX.toDouble())}."
                }
            }
        }
    }
}

/** Four amounts to type, saved together. `onSave` answers why they cannot be saved, or null. */
@Composable
private fun AmountsEditor(now: List<Int>, onSave: (List<Int>) -> String?) {
    var typed by rememberSaveable(now) { mutableStateOf(now.map { it.toString() }) }
    var why by rememberSaveable { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Amounts in dollars", style = text(13, color = Ink.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            typed.forEachIndexed { i, value ->
                DollarBox(value, "Quick buy amount ${i + 1}", Modifier.weight(1f), bad = why != null) { v ->
                    typed = typed.toMutableList().also { it[i] = v }
                    why = null
                }
            }
        }
        why?.let { Text(it, style = text(13, color = Ink.pink)) }
        GreenButton(
            "Save",
            onClick = {
                val amounts = typed.map { it.toIntOrNull() ?: 0 }
                why = onSave(amounts)
            },
            modifier = Modifier.fillMaxWidth(),
            solid = true,
            height = 48.dp,
            fontSize = 15,
        )
    }
}

@Composable
private fun DollarBox(value: String, label: String, modifier: Modifier, bad: Boolean, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(Ink.card)
            .border(1.dp, if (bad) Ink.pink else Ink.line3, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$", style = figure(15, FontWeight.SemiBold, Ink.muted))
        BasicTextField(
            value = value,
            onValueChange = { v -> v.filter { it.isDigit() }.take(6).let(onChange) },
            singleLine = true,
            textStyle = figure(15, FontWeight.SemiBold, Ink.text),
            cursorBrush = SolidColor(Ink.green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).padding(start = 2.dp).semantics { contentDescription = label },
        )
    }
}
