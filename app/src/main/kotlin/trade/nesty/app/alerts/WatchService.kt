package trade.nesty.app.alerts

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import trade.nesty.app.data.AppGraph

/**
 * Keeps Nesty running with the app closed while an exit plan is on, or while the menu asks for
 * alerts with Nesty closed: Android's foreground service, with its notification in the shade.
 * The checks run in the app's own process (see AppGraph); this keeps the process alive and the
 * phone's processor awake for them, and stops itself once nothing needs it.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var shown: Pair<String, String>? = null
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        Notifier.channels(this)
        val now = AppGraph.watchSummary()
        try {
            ServiceCompat.startForeground(this, Notifier.WATCH_ID, Notifier.watching(this, now.first, now.second), TYPE)
        } catch (_: Exception) {
            // Android did not allow it now (started again from the background on a phone that limits Nesty).
            stopSelf()
            return START_NOT_STICKY
        }
        shown = now
        // Android started it again after it ended the app: the wallets locked with the app.
        if (intent == null) AppGraph.watchRestarted()
        hold()
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        while (true) {
            AppGraph.watchLooked()
            if (!AppGraph.watchWanted()) {
                stop()
                return
            }
            val now = AppGraph.watchSummary()
            if (now != shown) {
                shown = now
                show(now)
            }
            delay(LOOK_MS)
        }
    }

    private fun show(now: Pair<String, String>) {
        try {
            NotificationManagerCompat.from(this).notify(Notifier.WATCH_ID, Notifier.watching(this, now.first, now.second))
        } catch (_: SecurityException) {
            // Notifications are off: the service runs on without its line in the shade.
        }
    }

    /** Keeps the processor awake while the screen is off, so a stop loss is not missed in a doze. */
    private fun hold() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nesty:watch").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stop() {
        release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Not when a start came in since: that one runs on (and puts its notification back).
        stopSelf(lastStart)
    }

    private fun release() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** How often it looks whether it is still needed and what its line in the shade says. */
        private const val LOOK_MS = 5_000L

        private val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        /**
         * Starts the service when something needs it. Called while one of the app's screens is in
         * front, where Android allows it; it stops by itself.
         */
        fun sync(context: Context) {
            if (!AppGraph.watchWanted()) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
            } catch (_: Exception) {
                // Not allowed now: the next time a screen comes to the front starts it.
            }
        }
    }
}
