package trade.nesty.app.i18n

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The app's languages, in the order the menu lists them: each by its own name, the names in Latin
 * letters from A to Z first (accents aside), then the other scripts in Unicode order.
 * The code is written in English; the others come from assets/i18n.
 */
enum class Lang(val tag: String, val label: String) {
    INDONESIAN("id", "Bahasa Indonesia"),
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    PORTUGUESE("pt-BR", "Português (Brasil)"),
    VIETNAMESE("vi", "Tiếng Việt"),
    RUSSIAN("ru", "Русский"),
    HINDI("hi", "हिन्दी"),
    JAPANESE("ja", "日本語"),
    CHINESE("zh-CN", "简体中文"),
    KOREAN("ko", "한국어");

    companion object {
        fun ofTag(tag: String?): Lang? = entries.firstOrNull { it.tag == tag }

        /** The first of the phone's languages the app has, else English. */
        fun ofLocales(locales: List<Locale>): Lang = locales.firstNotNullOfOrNull { ofLocale(it) } ?: ENGLISH

        /** Chinese in any script reads the simplified one, the only Chinese the app has. */
        fun ofLocale(l: Locale): Lang? = when (l.language) {
            "in", "id" -> INDONESIAN
            "de" -> GERMAN
            "en" -> ENGLISH
            "es" -> SPANISH
            "pt" -> PORTUGUESE
            "vi" -> VIETNAMESE
            "ru" -> RUSSIAN
            "hi" -> HINDI
            "ja" -> JAPANESE
            "zh" -> CHINESE
            "ko" -> KOREAN
            else -> null
        }
    }
}

/**
 * The language the app shows, and its texts. The menu's pick is kept on the phone; with none,
 * the phone's own language leads. Only the language in use is read from the app's files, so
 * English reads nothing. Compose screens that showed a text draw again when the language changes.
 */
object I18n {
    private const val PREFS = "nesty"
    private const val KEY = "language"

    /** The language picked in the menu; null follows the phone. */
    var picked by mutableStateOf<Lang?>(null)
        private set

    /** The language shown now. */
    var lang by mutableStateOf(Lang.ENGLISH)
        private set

    @Volatile
    private var look: (String) -> String? = { null }

    private var app: Context? = null
    private val changed = mutableListOf<(Lang) -> Unit>()

    fun init(context: Context) {
        app = context.applicationContext
        picked = Lang.ofTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        show()
    }

    /** The phone's languages may have changed: followed when the menu picks none. */
    fun phoneChanged() {
        if (picked == null && phoneLang() != lang) show()
    }

    fun pick(choice: Lang?) {
        val c = app ?: return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (choice == null) remove(KEY) else putString(KEY, choice.tag)
        }.apply()
        picked = choice
        show()
    }

    /** Called after each change of language, for what Android keeps apart (notification channels). */
    fun onChange(block: (Lang) -> Unit) {
        changed += block
    }

    fun phoneLang(): Lang = Lang.ofLocales(phoneLocales())

    private fun phoneLocales(): List<Locale> = runCatching {
        val list = Resources.getSystem().configuration.locales
        (0 until list.size()).map { list[it] }
    }.getOrDefault(emptyList())

    private fun show() {
        val next = picked ?: phoneLang()
        val c = app
        if (c != null) {
            val table = Catalog.read(c, next)
            look = { table[it] }
        }
        val was = lang
        lang = next
        if (next != was) changed.forEach { it(next) }
    }

    /** For tests: the texts of [lang] from [table], keyed by their English. */
    internal fun useTable(lang: Lang, table: Map<String, String>) {
        look = { table[it] }
        this.lang = lang
    }

    internal fun lookup(text: String): String {
        // Read for Compose, so a screen that showed the text draws again in a new language.
        val now = lang
        if (PseudoTexts.on) return PseudoTexts.of(text)
        if (now == Lang.ENGLISH) return text
        return look(text) ?: text
    }

    internal fun pluralForm(n: Long): Int = when (lang) {
        Lang.ENGLISH, Lang.GERMAN, Lang.SPANISH -> if (n == 1L) 0 else 1
        Lang.PORTUGUESE, Lang.HINDI -> if (n == 0L || n == 1L) 0 else 1
        Lang.RUSSIAN -> {
            val ten = n % 10
            val hundred = n % 100
            when {
                ten == 1L && hundred != 11L -> 0
                ten in 2L..4L && hundred !in 12L..14L -> 1
                else -> 2
            }
        }
        else -> 0
    }
}

/** The text in the app's language: [text] is its English, as the code writes it. */
fun tr(text: String): String = I18n.lookup(text)

/** The same, with `{1}`, `{2}`… in the text taking [args] in order, wherever the language puts them. */
fun tr(text: String, vararg args: Any?): String = fill(I18n.lookup(text), args)

/**
 * A count with its noun: "{1} wallet" when [n] is 1, else "{1} wallets", in the app's language
 * and its own plural forms. `{1}`, `{2}`… take [args].
 */
fun trCount(n: Long, one: String, other: String, vararg args: Any?): String {
    val key = "$one|$other"
    val found = I18n.lookup(key)
    if (found == key) return fill(if (n == 1L) one else other, args)
    val forms = found.split('|')
    return fill(forms[I18n.pluralForm(n).coerceAtMost(forms.lastIndex)], args)
}

/** Marks [text] as one of the app's texts, for a list or label shown later through [tr]. */
fun trKey(text: String): String = text

internal fun fill(pattern: String, args: Array<out Any?>): String {
    if (args.isEmpty() || pattern.indexOf('{') < 0) return pattern
    val b = StringBuilder(pattern.length + 16)
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '{') {
            val close = pattern.indexOf('}', i + 1)
            val n = if (close > i + 1) pattern.substring(i + 1, close).toIntOrNull() else null
            if (n != null && n in 1..args.size) {
                b.append(args[n - 1])
                i = close + 1
                continue
            }
        }
        b.append(c)
        i++
    }
    return b.toString()
}

/** The translations in the app's files: the English of every text once, then each language's lines in the same order. */
internal object Catalog {
    fun read(context: Context, lang: Lang): Map<String, String> {
        if (lang == Lang.ENGLISH) return emptyMap()
        return runCatching {
            val keys = lines(context, "i18n/keys.txt")
            val texts = lines(context, "i18n/${lang.tag}.txt")
            val map = HashMap<String, String>(keys.size * 2)
            for (i in keys.indices) {
                val t = texts.getOrNull(i)
                if (!t.isNullOrEmpty()) map[keys[i]] = shown(t)
            }
            map
        }.getOrElse { emptyMap() }
    }

    private fun lines(context: Context, path: String): List<String> =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.split('\n').map(::unescape)

    private val WORD_HYPHEN = Regex("""(?<=[\p{L}\p{M}])-(?=[\p{L}\p{N}])|(?<=\p{N})-(?=\p{L})""")

    /**
     * A translation as the app draws it: a hyphen inside a word ("Exit-Plan") becomes U+2010, since
     * the app's text asks Inter for tabular figures, which draw the plain hyphen as wide as a digit.
     */
    fun shown(s: String): String = if (s.indexOf('-') < 0) s else WORD_HYPHEN.replace(s, "‐")

    /** A line back to its text: `\n` is a new line and `\\` a backslash. */
    fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> b.append('\n')
                    '\\' -> b.append('\\')
                    else -> b.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }
}

/**
 * For a test run only (`-Pnesty.pseudo=1`): every text comes back in brackets, so logic that
 * wrongly reads a shown text fails its tests.
 */
internal object PseudoTexts {
    val on = System.getProperty("nesty.pseudo") == "1"

    fun of(key: String): String = key.split('|').joinToString("|") { "⟦$it⟧" }
}
