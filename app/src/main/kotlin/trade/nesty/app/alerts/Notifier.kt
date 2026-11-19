package trade.nesty.app.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import trade.nesty.app.MainActivity
import trade.nesty.app.R
import trade.nesty.app.i18n.tr

/**
 * Android's side of the alerts: the channels the user can tune in the phone's settings, the
 * notifications, and where a tap on one goes. A notification says only what the app's own
 * Notifications screen says.
 */
object Notifier {
    const val WATCH = "watch"
    private const val PLANS = "plans"
    private const val HELD = "held"
    private const val YOURS = "yours"
    private const val TRACKER = "tracker"
    private const val SIGNALS = "signals"
    private const val UPDATES = "updates"

    /** What a notification opens, as a route the app's screens read. */
    const val EXTRA_LINK = "trade.nesty.app.link"

    /** The alert a notification is, marked read when it is opened. */
    const val EXTRA_ID = "trade.nesty.app.alert"

    const val WATCH_ID = 1

    /** Nesty's alerts sit in one group with a summary of its own (see summary). */
    private const val GROUP = "trade.nesty.app.alerts"
    private const val SUMMARY_ID = 2
    private const val SUMMARY_REQUEST = 1

    /** The app's own updates: one line, replaced by the next. */
    private const val UPDATE_ID = 3
    private const val UPDATE_REQUEST = 2

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(PLANS, tr("Exit plans"), NotificationManager.IMPORTANCE_HIGH).apply { description = tr("Each step that sells, with what you got") },
                NotificationChannel(HELD, tr("Coins you hold"), NotificationManager.IMPORTANCE_HIGH).apply { description = tr("A followed wallet sells a coin you hold") },
                NotificationChannel(YOURS, tr("Your trades"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr("Your buys, sells and moves that end while Nesty is closed") },
                NotificationChannel(TRACKER, tr("Tracked wallets"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr("Buys and sells by the wallets you follow") },
                NotificationChannel(SIGNALS, tr("Signals"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr("When proven wallets buy the same coin") },
                NotificationChannel(UPDATES, tr("App updates"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr("A new version of Nesty is ready to install") },
                NotificationChannel(WATCH, tr("Watching"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr("Shown while Nesty watches your exit plans and alerts")
                    setShowBadge(false)
                },
            ),
        )
    }

    /** Whether Android shows Nesty's notifications: asked on Android 13 and later. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun channelOf(kind: AlertKind) = when (kind) {
        AlertKind.PLAN -> PLANS
        AlertKind.HELD -> HELD
        AlertKind.YOURS -> YOURS
        AlertKind.TRACKER -> TRACKER
        AlertKind.SIGNAL -> SIGNALS
    }

    /** Opens the app at `link` (or at the Notifications screen). */
    fun open(context: Context, link: AlertLink?, request: Int, id: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_LINK, linkText(link ?: AlertLink(AlertLink.ALERTS)))
        if (id != null) intent.putExtra(EXTRA_ID, id)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun linkText(link: AlertLink): String = listOfNotNull(link.to, link.feed, link.token).joinToString("|")

    fun linkOf(text: String?): AlertLink? {
        val parts = text?.split('|') ?: return null
        val to = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return AlertLink(to, parts.getOrNull(1), parts.getOrNull(2))
    }

    @SuppressLint("MissingPermission") // allowed() asks first.
    fun post(context: Context, a: Alert) {
        if (!allowed(context)) return
        val request = a.id.hashCode()
        val n = NotificationCompat.Builder(context, channelOf(a.kind))
            .setSmallIcon(R.drawable.nesty_egg)
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setWhen(a.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setPriority(if (a.kind == AlertKind.PLAN || a.kind == AlertKind.HELD) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (a.kind == AlertKind.PLAN) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open(context, a.link, request, a.id))
            .setGroup(GROUP)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(a.id, 0, n)
            NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary(context, channelOf(a.kind)))
        } catch (_: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }

    /**
     * The alerts' own summary. Without one Android bundles two or more alerts itself, and a tap on
     * its bundle only opens the app, clearing them all; this one opens the Notifications screen.
     * The alerts make the sound, never the summary.
     */
    private fun summary(context: Context, channel: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.nesty_egg)
            .setContentTitle(tr("Alerts"))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open(context, AlertLink(AlertLink.ALERTS), SUMMARY_REQUEST))
            .build()

    /** A checked update is ready to install: a tap opens the Update screen. */
    fun updateReady(context: Context, version: String) =
        postUpdate(context, tr("Nesty {1} is ready to install", version), tr("Downloaded and checked. Tap to install it."), AlertLink(AlertLink.UPDATE))

    /** The new version's first start after an update. */
    fun updated(context: Context, version: String) =
        postUpdate(context, tr("Nesty {1} is installed", version), tr("Your wallets, positions and exit plans are as you left them."), null)

    /** Nesty is open: its own screen says what the update line said. */
    fun clearUpdate(context: Context) = NotificationManagerCompat.from(context).cancel(UPDATE_ID)

    @SuppressLint("MissingPermission") // allowed() asks first.
    private fun postUpdate(context: Context, title: String, text: String, link: AlertLink?) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (link != null) intent.putExtra(EXTRA_LINK, linkText(link))
        val open = PendingIntent.getActivity(context, UPDATE_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.nesty_egg)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_ID, n)
        } catch (_: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }

    /** The watch service's own notification, which Android shows while it runs. */
    fun watching(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, WATCH)
            .setSmallIcon(R.drawable.nesty_egg)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Shown at once: Android otherwise holds a service's line back for up to 10 seconds.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, AlertLink(AlertLink.PORTFOLIO), 0))
            .build()

    /** Whether Android may stop Nesty in the background to save battery. */
    fun batteryLimited(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Android's own question to let Nesty run in the background. */
    fun askBackgroundUse(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(ask)
        } catch (_: Exception) {
            // Some phones have no such screen: their battery list instead.
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** The phone's notification settings for Nesty, when they were turned off. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
