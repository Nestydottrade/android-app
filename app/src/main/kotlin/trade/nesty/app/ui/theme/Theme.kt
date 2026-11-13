package trade.nesty.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import trade.nesty.app.R
import trade.nesty.app.data.Chain

/** The look of the Redesign boards (docs/android-brief.md). No yellow but chain colours. */
object Ink {
    val ground = Color(0xFF08070F)
    val card = Color(0xFF0D0B18)
    val raised = Color(0xFF131124)
    val line = Color(0xFF1C1934)
    val line2 = Color(0xFF272341)
    val line3 = Color(0xFF363060)
    val track = Color(0xFF1B182F)

    val text = Color(0xFFF3F1FF)
    val text2 = Color(0xFFCDC8EE)
    val muted = Color(0xFF9C96C6)

    /** Egg green: the action colour. */
    val green = Color(0xFF2EE6A8)
    val greenInk = Color(0xFF04150D)
    val greenSoft = Color(0xFF6AF0C3)
    val greenDeep = Color(0xFF0D2A24)
    val greenLine = Color(0xFF1B4D3E)
    val greenOk = Color(0xFF14392F)
    val greenDim = Color(0xFF1B6B54)

    /** Violet marks what is selected. */
    val violet = Color(0xFF9B8CFF)
    val violetSoft = Color(0xFFB7ACFF)
    val violetBg = Color(0xFF201C35)
    val violetDeep = Color(0xFF2A2550)

    /** Pink is a loss or a danger. */
    val pink = Color(0xFFFF5C93)
    val pinkSoft = Color(0xFFFF7DA8)
    val pinkBg = Color(0xFF2A1222)
    val pinkInk = Color(0xFF2A0716)

    /** Amber: worth a look. */
    val amber = Color(0xFFFFB547)
    val amberBg = Color(0xFF2B2213)

    val sniper = Color(0xFF7DBBF7)
    val teal = Color(0xFF9FE2E8)

    /** The record's two bars: signals and the yardstick. */
    val barSignals = Color(0xFF8A7AF0)
    val barAny = Color(0xFF2F8FB3)

    fun chain(c: Chain): Brush =
        if (c.dot2 != null) Brush.linearGradient(listOf(Color(c.dot), Color(c.dot2)))
        else Brush.linearGradient(listOf(Color(c.dot), Color(c.dot)))

    fun chainSolid(c: Chain): Color = Color(c.dot)
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

/** Barlow Semi Condensed, for figures. */
val Figures = FontFamily(
    Font(R.font.barlow_sc_medium, FontWeight.Medium),
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_sc_bold, FontWeight.Bold),
)

fun text(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Ink.text, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = Barlow,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        lineHeight = lineHeight,
        fontFeatureSettings = "tnum",
    )

fun figure(size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = Ink.text) =
    TextStyle(fontFamily = Figures, fontSize = size.sp, fontWeight = weight, color = color, fontFeatureSettings = "tnum")

@Composable
fun NestyTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ink.green,
        onPrimary = Ink.greenInk,
        secondary = Ink.violet,
        background = Ink.ground,
        onBackground = Ink.text,
        surface = Ink.card,
        onSurface = Ink.text,
        surfaceVariant = Ink.raised,
        onSurfaceVariant = Ink.text2,
        outline = Ink.line2,
        error = Ink.pink,
    )
    val base = Typography()
    val type = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = Barlow),
        bodyMedium = base.bodyMedium.copy(fontFamily = Barlow),
        bodySmall = base.bodySmall.copy(fontFamily = Barlow),
        labelLarge = base.labelLarge.copy(fontFamily = Barlow),
        titleMedium = base.titleMedium.copy(fontFamily = Barlow),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
