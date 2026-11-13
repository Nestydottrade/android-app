package trade.nesty.app.evm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Token names and node errors as the extension's sanitizeLabel (src/shared/sanitize.ts) cleans
 * them, and the guard that keeps JVM-only regex syntax out of the app: Android's regex engine
 * (ICU) refuses it, which a test on the JVM never sees (a Solana coin's name once broke every
 * quote on the phone that way).
 */
class SanitizeLabelTest {
    @Test
    fun spacesBecomeOneAsInTheExtension() {
        assertEquals("Dog wif hat", sanitizeLabel("  Dog 　wif   hat  "))
        assertEquals("", sanitizeLabel("    "))
    }

    @Test
    fun hiddenCharactersGoBeforeSpacesAreJoined() {
        // Controls (a newline too), zero-width and bidi characters are dropped first.
        assertEquals("ab", sanitizeLabel("a​b"))
        assertEquals("ab", sanitizeLabel("a\nb"))
        assertEquals("a b", sanitizeLabel("a ​ b"))
        assertEquals("abc", sanitizeLabel("‮abc⁦"))
    }

    @Test
    fun theCapCountsCodePointsAfterCleaning() {
        assertEquals("abc ", sanitizeLabel("abc   def", 4))
        assertEquals("😀😀", sanitizeLabel("😀😀😀", 2))
    }

    @Test
    fun noRegexAndroidRefuses() {
        val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val found = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("\"(?U") || line.contains("UNICODE_CHARACTER_CLASS")) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("JVM-only regex flags: $found", found.isEmpty())
    }
}
