package trade.nesty.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest
import trade.nesty.app.i18n.tr

/** What Android reads of an APK, or of the installed app: its package, version and signers. */
data class ApkFacts(
    val pkg: String,
    val code: Long,
    /** SHA-256 of each certificate that signs it now. */
    val signers: List<String>,
    /** Every certificate it has rotated through, oldest first (APK Signature Scheme v3); else its signers. */
    val history: List<String>,
)

object ApkCheck {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Whether an APK may update the installed app: signed by the same certificates, or, after a
     * key rotation, by one whose history holds the installed key. Android checks this itself on
     * install and refuses a mismatch; this says so before the user is asked anything.
     */
    fun sameSigner(installed: List<String>, apk: ApkFacts): Boolean {
        if (installed.isEmpty() || apk.signers.isEmpty()) return false
        if (installed.toSet() == apk.signers.toSet()) return true
        return installed.size == 1 && apk.signers.size == 1 && installed[0] in apk.history
    }

    /** Why a fetched APK cannot be the update, or null when it can. `facts` null: Android could not read it. */
    fun refusal(facts: ApkFacts?, pkg: String, code: Int, installed: ApkFacts?): String? {
        if (facts == null) return tr("Android could not read the file.")
        if (facts.pkg != pkg) return tr("The file is not Nesty.")
        if (facts.code != code.toLong()) return tr("The file is not the version published.")
        if (installed != null && installed.signers.isNotEmpty() && facts.signers.isNotEmpty() && !sameSigner(installed.signers, facts)) {
            return tr("The file is not signed with Nesty's key.")
        }
        return null
    }
}

/** Reads ApkFacts through Android's package manager. */
object ApkReader {
    private val FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    fun ofFile(context: Context, file: File): ApkFacts? {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, FLAGS)
            }
        }.getOrNull() ?: return null
        return factsOf(info)
    }

    fun installed(context: Context): ApkFacts? {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, FLAGS)
            }
        }.getOrNull() ?: return null
        return factsOf(info)
    }

    private fun factsOf(info: PackageInfo): ApkFacts {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val (signers, history) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo
            when {
                s == null -> emptyList<String>() to emptyList()
                s.hasMultipleSigners() -> digests(s.apkContentsSigners).let { it to it }
                else -> digests(s.signingCertificateHistory).let { all -> listOfNotNull(all.lastOrNull()) to all }
            }
        } else {
            @Suppress("DEPRECATION") digests(info.signatures).let { it to it }
        }
        return ApkFacts(info.packageName, code, signers, history)
    }

    private fun digests(signatures: Array<Signature>?): List<String> {
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { ApkCheck.hex(md.digest(it.toByteArray())) }
    }
}
