package trade.nesty.app.i18n

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import trade.nesty.app.ui.appendTr
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * The translations against the code: every text the code shows has a line in each language, with
 * the same {n} places and that language's number of plural forms, and nothing the code dropped
 * stays behind. Also the menu's order and the small functions that put a text together.
 */
class TranslationsTest {
    @After
    fun backToEnglish() = I18n.useTable(Lang.ENGLISH, emptyMap())

    @Test
    fun everyLanguageButEnglishHasItsFile() {
        val langs = Lang.entries.filter { it != Lang.ENGLISH }.map { it.tag }.toSet()
        assertEquals(langs, catalogs.keys)
    }

    @Test
    fun theCodeShowsWholeTextsOnly() {
        val bad = code.problems + code.keys.filter { !it.value && '|' in it.key }.map { "a | in tr text: ${it.key}" }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun everyTextTheCodeShowsIsTranslated() {
        for ((tag, table) in catalogs) {
            val missing = code.keys.keys - table.keys
            assertTrue("$tag misses ${missing.size}: ${missing.take(8)}", missing.isEmpty())
        }
    }

    @Test
    fun noTranslationOutlivesItsText() {
        for ((tag, table) in catalogs) {
            val stale = table.keys - code.keys.keys
            assertTrue("$tag keeps ${stale.size} texts the code no longer shows: ${stale.take(8)}", stale.isEmpty())
        }
    }

    @Test
    fun translationsKeepTheirPlacesFormsAndEdges() {
        val bad = mutableListOf<String>()
        for ((tag, table) in catalogs) {
            val forms = formsOf(Lang.ofTag(tag)!!)
            for ((key, t) in table) {
                if (t.isBlank()) { bad += "$tag empty: $key"; continue }
                if ('\t' in t) bad += "$tag tab: $key"
                if (key.first().isWhitespace() != t.first().isWhitespace()) bad += "$tag leading space: [$key] [$t]"
                if (key.last().isWhitespace() != t.last().isWhitespace()) bad += "$tag trailing space: [$key] [$t]"
                if (code.keys[key] == true) {
                    val got = t.split('|')
                    if (got.size != forms) bad += "$tag has ${got.size} plural forms, wants $forms: $t"
                    val want = places(key.substringAfter('|'))
                    got.filter { places(it) != want }.forEach { bad += "$tag form places: $key => $it" }
                } else {
                    if (places(t) != places(key)) bad += "$tag places: $key => $t"
                    if ('|' in t) bad += "$tag stray |: $t"
                }
            }
        }
        assertEquals(emptyList<String>(), bad.take(20))
    }

    @Test
    fun everyTextSurvivesThePackedLines() {
        // As build.gradle.kts's PackI18n writes a line.
        fun line(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
        for ((tag, table) in catalogs) {
            for ((key, t) in table) {
                assertEquals(key, Catalog.unescape(line(key)))
                assertEquals("$tag: $key", t, Catalog.unescape(line(t)))
            }
        }
    }

    @Test
    fun theMenuListsLatinNamesAToZThenOtherScriptsInUnicodeOrder() {
        val labels = Lang.entries.map { it.label }
        fun latin(s: String) = Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.LATIN
        fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        val want = labels.filter(::latin).sortedBy(::plain) + labels.filterNot(::latin).sorted()
        assertEquals(want, labels)
    }

    @Test
    fun thePhonesLanguageFindsItsLang() {
        assertEquals(Lang.INDONESIAN, Lang.ofLocale(Locale("in")))
        assertEquals(Lang.INDONESIAN, Lang.ofLocale(Locale.forLanguageTag("id-ID")))
        assertEquals(Lang.PORTUGUESE, Lang.ofLocale(Locale.forLanguageTag("pt-PT")))
        assertEquals(Lang.CHINESE, Lang.ofLocale(Locale.forLanguageTag("zh-TW")))
        assertNull(Lang.ofLocale(Locale.FRENCH))
        assertEquals(Lang.GERMAN, Lang.ofLocales(listOf(Locale.FRENCH, Locale.GERMANY, Locale.JAPAN)))
        assertEquals(Lang.ENGLISH, Lang.ofLocales(listOf(Locale.FRENCH)))
        assertEquals(Lang.ENGLISH, Lang.ofLocales(emptyList()))
    }

    @Test
    fun fillPutsEachArgumentWhereTheTextHasIt() {
        assertEquals("b, then a", fill("{2}, then {1}", arrayOf("a", "b")))
        assertEquals("a and a", fill("{1} and {1}", arrayOf("a")))
        assertEquals("{3} stays", fill("{3} stays", arrayOf("a", "b")))
        assertEquals("{x} {} {0} {", fill("{x} {} {0} {", arrayOf("a")))
        assertEquals("kept {1}", fill("kept {1}", emptyArray<Any?>()))
    }

    @Test
    fun unescapeReadsBackslashLines() {
        assertEquals("a\nb", Catalog.unescape("a\\nb"))
        assertEquals("a\\b", Catalog.unescape("a\\\\b"))
        assertEquals("a\\tb", Catalog.unescape("a\\tb"))
        assertEquals("end\\", Catalog.unescape("end\\"))
        assertEquals("plain", Catalog.unescape("plain"))
    }

    @Test
    fun aHyphenInsideAWordIsDrawnNarrow() {
        assertEquals("Exit‐Plan", Catalog.shown("Exit-Plan"))
        assertEquals("MEV‐защита и топ‐10", Catalog.shown("MEV-защита и топ-10"))
        assertEquals("कॉइन‐बुक", Catalog.shown("कॉइन-बुक"))
        assertEquals("24‐Stunden", Catalog.shown("24-Stunden"))
        assertEquals("-5% · 1-2 · {1}-{2} · a - b", Catalog.shown("-5% · 1-2 · {1}-{2} · a - b"))
        assertEquals("plain", Catalog.shown("plain"))
    }

    @Test
    fun trFallsBackToEnglish() {
        assumeFalse(PseudoTexts.on)
        I18n.useTable(Lang.GERMAN, mapOf("Wallets" to "Wallets", "Done" to "Fertig"))
        assertEquals("Fertig", tr("Done"))
        assertEquals("Not in the table", tr("Not in the table"))
        assertEquals("2 coins", trCount(2, "{1} coin", "{1} coins", 2))
    }

    @Test
    fun trCountTakesTheLanguagesForm() {
        assumeFalse(PseudoTexts.on)
        val key = "{1} coin|{1} coins"
        fun coins(n: Long) = trCount(n, "{1} coin", "{1} coins", n)
        assertEquals("1 coin", coins(1))
        assertEquals("0 coins", coins(0))
        I18n.useTable(Lang.RUSSIAN, mapOf(key to "{1} монета|{1} монеты|{1} монет"))
        assertEquals(
            listOf("1 монета", "21 монета", "3 монеты", "22 монеты", "5 монет", "11 монет", "12 монет", "111 монет", "0 монет"),
            listOf<Long>(1, 21, 3, 22, 5, 11, 12, 111, 0).map(::coins),
        )
        I18n.useTable(Lang.PORTUGUESE, mapOf(key to "{1} moeda|{1} moedas"))
        assertEquals(listOf("0 moeda", "1 moeda", "2 moedas"), listOf<Long>(0, 1, 2).map(::coins))
        I18n.useTable(Lang.GERMAN, mapOf(key to "{1} Coin|{1} Coins"))
        assertEquals(listOf("0 Coins", "1 Coin", "2 Coins"), listOf<Long>(0, 1, 2).map(::coins))
        I18n.useTable(Lang.JAPANESE, mapOf(key to "コイン{1}個"))
        assertEquals(listOf("コイン1個", "コイン5個"), listOf<Long>(1, 5).map(::coins))
    }

    @Test
    fun appendTrStylesTheArgumentWhereverTheLanguagePutsIt() {
        assumeFalse(PseudoTexts.on)
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        val key = "{1} bought {2}, {3} ago"
        fun built() = buildAnnotatedString { appendTr(key, 1, bold, null, "Ann", "\$5", "3m") }
        fun boldAt(text: AnnotatedString) = text.spanStyles.filter { it.item == bold }.map { it.start until it.end }

        val en = built()
        assertEquals("Ann bought \$5, 3m ago", en.text)
        assertEquals(listOf(0 until 3), boldAt(en))

        I18n.useTable(Lang.JAPANESE, mapOf(key to "{3}前に{1}が{2}を購入"))
        val ja = built()
        assertEquals("3m前にAnnが\$5を購入", ja.text)
        val at = ja.text.indexOf("Ann")
        assertEquals(listOf(at until at + 3), boldAt(ja))
    }

    private fun places(s: String) = PLACE.findAll(s).map { it.value }.sorted().toList()

    /** How many plural forms [lang] has, as I18n picks them. */
    private fun formsOf(lang: Lang): Int {
        I18n.useTable(lang, emptyMap())
        return (0L..1000L).maxOf { I18n.pluralForm(it) } + 1
    }

    companion object {
        private val PLACE = Regex("""\{\d+\}""")

        private fun moduleFile(path: String): File = listOf(File(path), File("app", path)).first { it.exists() }

        /** Each language's file: a text's English to its translation. */
        val catalogs: Map<String, Map<String, String>> by lazy {
            moduleFile("src/main/i18n").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.associate { f ->
                f.nameWithoutExtension to Json.parseToJsonElement(f.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
        }

        val code: CodeTexts by lazy { CodeTexts(moduleFile("src/main/kotlin")) }
    }
}

/**
 * The texts the code shows, read from its source: tr("…") and trKey("…") by their text, and
 * trCount(n, "one", "other") and Fmt.counted(n, "one", "other") as "one|other" (true: plural).
 * A tr() whose text is a template or is joined to more text is a problem: it can never match.
 */
class CodeTexts(root: File) {
    val keys = sortedMapOf<String, Boolean>()
    val problems = mutableListOf<String>()

    init {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (!rel.endsWith("i18n/I18n.kt")) scan(rel, f.readText().replace("\r\n", "\n"))
        }
    }

    private class Lit(val value: String, val end: Int, val template: Boolean)

    private fun scan(rel: String, s: String) {
        for (m in CALL.findAll(s)) {
            val fn = m.groupValues[1]
            val where = { "$rel:${s.substring(0, m.range.first).count { it == '\n' } + 1}" }
            var i = skipSpace(s, m.range.last + 1)
            try {
                if (fn == "trCount" || fn == "counted") {
                    if (fn == "counted" && Regex("""fun\s+$""").containsMatchIn(s.substring(maxOf(0, m.range.first - 8), m.range.first))) continue
                    if (fn == "trCount" && rel.endsWith("ui/Format.kt") && s.startsWith("n, one, other", i)) continue
                    i = skipArg(s, i)
                    if (s.getOrNull(i) != ',') { problems += "${where()} $fn without forms"; continue }
                    i = skipSpace(s, i + 1)
                    val one = readString(s, i)
                    if (one == null) { problems += "${where()} $fn one is not a literal"; continue }
                    i = skipSpace(s, one.end)
                    if (s.getOrNull(i) != ',') { problems += "${where()} $fn without other"; continue }
                    i = skipSpace(s, i + 1)
                    val other = readString(s, i)
                    if (other == null) { problems += "${where()} $fn other is not a literal"; continue }
                    if (one.template || other.template) problems += "${where()} template in $fn: ${one.value}"
                    keys[one.value + "|" + other.value] = true
                } else {
                    // tr(value) shows a text kept by trKey("…"), which is read where it is written.
                    if (s.getOrNull(i) != '"') continue
                    val r = readString(s, i)
                    if (r == null) { problems += "${where()} raw string in $fn"; continue }
                    if (r.template) problems += "${where()} template in $fn: ${r.value}"
                    if (s.getOrNull(skipSpace(s, r.end)) == '+') problems += "${where()} joined after: ${r.value}"
                    if (r.value.isEmpty()) problems += "${where()} empty text"
                    if (keys[r.value] != true) keys[r.value] = false
                }
            } catch (e: IllegalStateException) {
                problems += "${where()} ${e.message}"
            }
        }
    }

    /** A Kotlin "…" literal starting at [start]; null for a raw """…""" one. */
    private fun readString(s: String, start: Int): Lit? {
        if (s.getOrNull(start) != '"' || s.startsWith("\"\"\"", start)) return null
        val b = StringBuilder()
        var template = false
        var j = start + 1
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') {
                val n = s.getOrNull(j + 1)
                if (n == 'u') {
                    b.append(s.substring(j + 2, j + 6).toInt(16).toChar())
                    j += 6
                    continue
                }
                b.append(
                    when (n) {
                        't' -> '\t'
                        'b' -> '\b'
                        'n' -> '\n'
                        'r' -> '\r'
                        '\'' -> '\''
                        '"' -> '"'
                        '\\' -> '\\'
                        '$' -> '$'
                        else -> error("bad escape \\$n")
                    },
                )
                j += 2
                continue
            }
            if (c == '$' && s.getOrNull(j + 1).let { it == '{' || it == '_' || it in 'A'..'Z' || it in 'a'..'z' }) template = true
            if (c == '"') return Lit(b.toString(), j + 1, template)
            if (c == '\n') error("new line in a string")
            b.append(c)
            j++
        }
        error("unclosed string")
    }

    private fun skipSpace(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Skips one argument up to its top-level comma or the call's close; returns that index. */
    private fun skipArg(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '"') {
                i = (readString(s, i) ?: error("raw string in an argument")).end
                continue
            }
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return i else depth--
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return i
    }

    private companion object {
        val CALL = Regex("""(?<![A-Za-z0-9_])(tr|trKey|trCount|counted)\s*\(""")
    }
}
