package trade.nesty.app.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import trade.nesty.app.data.AppRelease
import trade.nesty.app.data.ReleaseNote
import java.util.Locale

/**
 * Which release the phone takes: a newer version only, served by nesty.trade itself, with a
 * hash and a size that make sense. The host is built into the app; the server only says what
 * is there.
 */
object Releases {
    /** Where updates come from. A debug build may name a test server instead (UpdateHost). */
    const val HOST = "nesty.trade"

    /** An APK smaller or larger than this is not one of Nesty's. */
    const val MIN_BYTES = 1_000_000L
    const val MAX_BYTES = 300_000_000L

    const val MAX_NOTES = 6

    private val VERSION = Regex("""^\d{1,4}\.\d{1,4}\.\d{1,4}$""")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /**
     * `r` when it is newer than the installed `code` and every field is as it must be, else
     * null. `host` is nesty.trade, which must be reached over https; a debug build's test
     * server is the only other host taken.
     */
    fun usable(r: AppRelease?, installedCode: Int, host: String = HOST): AppRelease? {
        if (r == null || r.code <= installedCode) return null
        if (!VERSION.matches(r.version) || !SHA256.matches(r.sha256) || r.size !in MIN_BYTES..MAX_BYTES) return null
        val url = r.url.toHttpUrlOrNull() ?: return null
        if (url.host != host || (host == HOST && !url.isHttps)) return null
        val notes = r.notes
            .filter { it.title.isNotBlank() }
            .take(MAX_NOTES)
            .map { ReleaseNote(it.title.trim().take(60), it.text.trim().take(120)) }
        return r.copy(notes = notes)
    }

    /** A hash as the Update screen shows it: "9f2c 81d0 … 4be7 e41a". */
    fun shortHash(sha256: String): String {
        if (sha256.length < 16) return sha256
        val end = sha256.takeLast(8)
        return "${sha256.substring(0, 4)} ${sha256.substring(4, 8)} … ${end.substring(0, 4)} ${end.substring(4)}"
    }

    /** "21.4 MB". */
    fun sizeText(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}
