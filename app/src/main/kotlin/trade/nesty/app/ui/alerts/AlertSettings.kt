package trade.nesty.app.ui.alerts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import trade.nesty.app.alerts.Notifier
import trade.nesty.app.data.AlertSwitch
import trade.nesty.app.data.AppGraph
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/**
 * The menu's Alerts: what alerts the user, and whether tracked wallets and signals keep coming
 * with Nesty closed. Exit plans always watch with Nesty closed while one is on.
 */
@Composable
fun AlertSettings() {
    val context = LocalContext.current
    val settings = AppGraph.settings
    var look by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { look++ }
    val notify = remember(look) { Notifier.allowed(context) }
    val limited = remember(look) { Notifier.batteryLimited(context) }
    val turnOn = rememberTurnOnNotifications { look++; AppGraph.syncWatch() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { look++; AppGraph.syncWatch() }

    val rows = listOf(
        Triple(AlertSwitch.TRACKER, "Tracked wallets trade", "Buys and sells by the wallets you follow"),
        Triple(AlertSwitch.SIGNALS, "New signals", "When proven wallets buy the same coin"),
        Triple(AlertSwitch.PLANS, "Exit plan sells", "Each step that fills, with what you got"),
        Triple(AlertSwitch.HELD, "Coins you hold", "A followed wallet sells it"),
        Triple(AlertSwitch.CLOSED, "With Nesty closed", "Tracked wallets and signals alert you too. Uses more battery."),
    )
    rows.forEachIndexed { i, (which, label, help) ->
        val on = settings.alert(which)
        SwitchRow(label, help, on, first = i == 0) { next ->
            settings.setAlert(which, next)
            if (which == AlertSwitch.CLOSED && next) {
                AppGraph.syncWatch()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notify) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    if (!notify) {
        Divider()
        AmberNote("Notifications are off for Nesty, so alerts show only in the app.", "Turn on", turnOn)
    }
    if (limited && (AppGraph.exitStore.plans.isNotEmpty() || settings.alert(AlertSwitch.CLOSED))) {
        Divider()
        AmberNote("Battery saver can stop the watch on some phones.", "Allow background use") { Notifier.askBackgroundUse(context) }
    }
}

/**
 * Turns Nesty's notifications on: Android's own question where it still asks, else the phone's
 * notification settings for Nesty.
 */
@Composable
fun rememberTurnOnNotifications(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Notifier.openSettings(context)
        onDone()
    }
    return {
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else Notifier.openSettings(context)
    }
}

@Composable
private fun SwitchRow(label: String, help: String, on: Boolean, first: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        if (!first) Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = text(15, FontWeight.SemiBold))
                Text(help, style = text(13, color = Ink.muted, lineHeight = 18.sp))
            }
            SwitchMark(on)
        }
    }
}

/** The board's switch: a track with a knob, green when on. */
@Composable
fun SwitchMark(on: Boolean) {
    Box(Modifier.size(width = 56.dp, height = 44.dp)) {
        val track = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .offset(x = 2.dp, y = 8.dp)
                .size(width = 52.dp, height = 28.dp)
                .clip(track)
                .background(if (on) Ink.greenDeep else Ink.raised)
                .border(1.dp, if (on) Ink.green else Ink.line3, track),
        )
        Box(
            Modifier
                .offset(x = if (on) 30.dp else 6.dp, y = 12.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) Ink.green else Ink.muted),
        )
    }
}

/** A note in amber with one button under it: notifications off, the battery saver. */
@Composable
fun AmberNote(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = text(13, color = Ink.amber, lineHeight = 18.sp))
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .height(44.dp)
                .clip(shape)
                .background(Ink.raised)
                .border(1.dp, Ink.line3, shape)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, style = text(14, FontWeight.Bold))
        }
    }
}
