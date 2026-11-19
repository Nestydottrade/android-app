package trade.nesty.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import trade.nesty.app.i18n.tr

private val PLACE = Regex("""\{(\d+)\}""")

/**
 * Appends [key] in the app's language with its `{n}` places filled from [args]; the argument
 * numbered [styled] gets [style] and the rest of the text gets [rest]. Each language puts that
 * argument where its own sentence has it.
 */
fun AnnotatedString.Builder.appendTr(key: String, styled: Int, style: SpanStyle, rest: SpanStyle?, vararg args: Any?) {
    val pattern = tr(key)
    fun put(text: String, with: SpanStyle?) {
        if (text.isEmpty()) return
        if (with == null) append(text) else withStyle(with) { append(text) }
    }
    var at = 0
    for (m in PLACE.findAll(pattern)) {
        put(pattern.substring(at, m.range.first), rest)
        val n = m.groupValues[1].toInt()
        put(args.getOrNull(n - 1)?.toString() ?: m.value, if (n == styled) style else rest)
        at = m.range.last + 1
    }
    put(pattern.substring(at), rest)
}
