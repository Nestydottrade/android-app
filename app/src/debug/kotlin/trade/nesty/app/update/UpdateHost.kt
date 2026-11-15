package trade.nesty.app.update

import android.content.Context
import java.io.File

/**
 * Debug builds only: a test server on this computer in place of nesty.trade, so the updater can
 * be tried in the emulator without publishing anything. It is named in the app's own files
 * (`adb exec-in run-as`), and only the emulator's address for this computer is taken. The
 * release build has no such file (src/release) and always asks nesty.trade.
 */
object UpdateHost {
    private const val FILE = "update-test-base"
    private val TEST = Regex("""^http://10\.0\.2\.2:\d{2,5}/$""")

    fun testBase(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { TEST.matches(it) }
}
