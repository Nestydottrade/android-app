package trade.nesty.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import trade.nesty.app.alerts.Notifier
import trade.nesty.app.data.AppGraph
import trade.nesty.app.ui.AppRoot
import trade.nesty.app.ui.theme.NestyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bars on a dark app, whatever the phone's own theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A tapped notification: its screen opens once the wallets are open (not on a restore).
        if (savedInstanceState == null) openLink(intent)
        setContent {
            NestyTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        Notifier.linkOf(intent?.getStringExtra(Notifier.EXTRA_LINK))?.let { AppGraph.pendingLink = it }
        intent?.getStringExtra(Notifier.EXTRA_ID)?.let { AppGraph.alertStore.markRead(it) }
        intent?.removeExtra(Notifier.EXTRA_LINK)
        intent?.removeExtra(Notifier.EXTRA_ID)
    }

    // The wallets lock once the app has been off the screen for the time picked in the menu.
    // While a screen is in front its alerts show on it; away, they come as notifications.
    override fun onStart() {
        super.onStart()
        AppGraph.session.onStart()
        AppGraph.alerts.visible = true
        AppGraph.syncWatch()
    }

    override fun onStop() {
        AppGraph.alerts.visible = false
        AppGraph.session.onStop()
        super.onStop()
    }
}
