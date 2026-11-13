package trade.nesty.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The boards' line icons, from their SVG paths. Tinted where they are drawn. */
object NIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun line(vararg d: String, width: Float = 2f, box: Float = 24f, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(defaultWidth = box.dp, defaultHeight = box.dp, viewportWidth = box, viewportHeight = box)
        for (p in d) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val search = line(circle(11f, 11f, 7f), "M20 20l-4.2-4.2")
    val bell = line("M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0")
    val arrowBack = line("M19 12H5", "M12 19l-7-7 7-7", width = 2.2f)
    val chevronLeft = line("M15 5l-7 7 7 7", width = 2.2f)
    val chevronRight = line("M9 6l6 6-6 6", width = 2.2f)
    val chevronDown = line("M6 9l6 6 6-6", width = 2.6f)
    val chevronUp = line("M6 15l6-6 6 6", width = 2.4f)
    val star = line("M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z")
    val starFilled = line("M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z", filled = true)
    val more = line(circle(12f, 5f, 1.8f), circle(12f, 12f, 1.8f), circle(12f, 19f, 1.8f), filled = true)
    val funnel = line("M4 5h16l-6 7.5V19l-4 1.5v-8z")
    val copy = line(
        "M10.5 8h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-7a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z",
        "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2",
        width = 2.2f,
    )
    val plus = line("M12 5v14M5 12h14", width = 2.4f)
    val check = line("M5 12.5l4.5 4.5L19 7.5", width = 2.4f)
    val close = line("M6 6l12 12M18 6L6 18", width = 2.2f)
    val external = line("M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val compass = line(circle(12f, 12f, 9f), "M15.5 8.5l-2 5-5 2 2-5z")
    val pulse = line("M3 12h4l2.5-6 4 12 2.5-6H21")
    val eye = line("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val pie = line("M21 12a9 9 0 1 1-9-9v9z", "M15 3.6A9 9 0 0 1 20.4 9H15z")
    val doneCircle = line(circle(12f, 12f, 9f), "M8 12.5l2.5 2.5 5.5-5.5", width = 2.2f)

    // The small round marks beside a figure, drawn on an 18 unit circle.
    val toneBad = line("M6 6l6 6M12 6l-6 6", box = 18f)
    val toneWarn = line("M9 5v5M9 13v.01", box = 18f)
    val toneWallets = line("M9 9a2.2 2.2 0 1 0 0-4.4A2.2 2.2 0 0 0 9 9zM5.2 13.4c.7-1.7 2.1-2.5 3.8-2.5s3.1.8 3.8 2.5", box = 18f)
    val toneInfo = line("M6 9h6", box = 18f)
    val toneOk = line("M5.5 9.3l2.3 2.3 4.7-5", box = 18f)
    val toneNone = line("M7 7.2a2 2 0 1 1 2.8 1.8c-.5.2-.8.6-.8 1.1v.3M9 12.8v.01", box = 18f)
}
