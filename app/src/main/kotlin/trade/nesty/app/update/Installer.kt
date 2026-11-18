package trade.nesty.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.alerts.Notifier
import trade.nesty.app.data.AppGraph
import java.io.File

/** Hands a checked APK to Android's package installer, which replaces Nesty with it. */
object Installer {
    private const val ACTION_STATUS = "trade.nesty.app.update.STATUS"

    /** Android asks "Update Nesty?" before every install; the user's answer decides. */
    fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("nesty.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                // Android fills in the answer, so the intent is mutable; it names this app's own receiver.
                val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    fun endOf(status: Int): InstallEnd = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEnd.ABORTED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallEnd.BLOCKED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallEnd.CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallEnd.INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallEnd.INVALID
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallEnd.STORAGE
        else -> InstallEnd.OTHER
    }
}

/** Android's answers to an install: its own question to show, or how it ended. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Update Nesty?" question. The user just tapped Install update, so Nesty is in front.
                val ask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (ask == null) {
                    AppGraph.updater.installEnded(InstallEnd.OTHER)
                    return
                }
                runCatching { context.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { AppGraph.updater.installEnded(InstallEnd.OTHER) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android ends this version; the new one starts on its own.
            else -> AppGraph.updater.installEnded(Installer.endOf(status))
        }
    }
}

/**
 * The new version's first word after an update: the old files go, the watch service starts
 * again for exit plans and alerts, and a notification says it is installed.
 */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppGraph.syncWatch()
        Notifier.updated(context, BuildConfigLite.VERSION)
    }
}

/** The phone as the updater sees it. */
class AndroidUpdatePhone(private val context: Context) : UpdatePhone {
    override val pkg: String = context.packageName
    override val code: Int = BuildConfigLite.CODE
    override val version: String = BuildConfigLite.VERSION

    /** Mobile data, no network, or Android would not say: all count as costing the user. */
    override fun metered(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }.getOrNull() ?: true

    override fun factsOf(file: File): ApkFacts? = ApkReader.ofFile(context, file)

    override fun installed(): ApkFacts? = ApkReader.installed(context)
}
