package trade.nesty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            NestyTheme { AppRoot() }
        }
    }

    // The wallets lock once the app has been off the screen for the time picked in the menu.
    override fun onStart() {
        super.onStart()
        AppGraph.session.onStart()
    }

    override fun onStop() {
        AppGraph.session.onStop()
        super.onStop()
    }
}
