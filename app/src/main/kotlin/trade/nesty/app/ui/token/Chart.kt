package trade.nesty.app.ui.token

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.data.Candle
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.theme.Barlow
import trade.nesty.app.ui.theme.Ink
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The lengths a chart's candles can have. The server has no 15 second candle: it is summed from 1 second ones. */
enum class Frame(val label: String, val interval: String, val seconds: Long, val limit: Int, val words: String) {
    S1("1s", "1s", 1, 300, "1 second"),
    S15("15s", "1s", 15, 1_500, "15 second"),
    M1("1m", "1m", 60, 500, "1 minute"),
    M5("5m", "5m", 300, 500, "5 minute"),
    H1("1h", "1h", 3_600, 500, "1 hour"),
}

object CandleMath {
    /** Candles summed into longer ones of [seconds], oldest first. */
    fun aggregate(candles: List<Candle>, seconds: Long): List<Candle> {
        if (seconds <= 1 || candles.isEmpty()) return candles
        val out = ArrayList<Candle>(candles.size / seconds.toInt() + 1)
        var cur: Candle? = null
        for (c in candles) {
            val bucket = c.time - Math.floorMod(c.time, seconds)
            val k = cur
            cur = if (k == null || k.time != bucket) {
                if (k != null) out += k
                Candle(bucket, c.open, c.high, c.low, c.close, c.volume)
            } else {
                Candle(bucket, k.open, max(k.high, c.high), min(k.low, c.low), c.close, k.volume + c.volume)
            }
        }
        cur?.let { out += it }
        return out
    }

    /** The list with [c] put in: a candle of the same time replaced, a newer one added, at most [max] kept. */
    fun upsert(list: List<Candle>, c: Candle, max: Int): List<Candle> {
        if (list.isEmpty()) return listOf(c)
        val last = list.last()
        val out = when {
            c.time == last.time -> list.dropLast(1) + c
            c.time > last.time -> list + c
            else -> {
                val i = list.indexOfFirst { it.time >= c.time }
                if (list[i].time == c.time) list.toMutableList().also { it[i] = c } else list.toMutableList().also { it.add(i, c) }
            }
        }
        return if (out.size > max) out.takeLast(max) else out
    }

    /** A round step of about [range] / [count]: 1, 2 or 5 times a power of ten. */
    fun niceStep(range: Double, count: Int): Double {
        if (range <= 0 || count <= 0) return 1.0
        val raw = range / count
        val p = 10.0.pow(floor(log10(raw)))
        val f = raw / p
        return p * when {
            f <= 1 -> 1.0
            f <= 2 -> 2.0
            f <= 5 -> 5.0
            else -> 10.0
        }
    }
}

/**
 * Market cap candles: each price times [supply]. The newest at the right, about thirty in view,
 * three round levels and a tag at the last close.
 */
@Composable
fun CandleChart(candles: List<Candle>, supply: Double, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink.muted) }
    val tagStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.greenInk) }
    val shown = candles.takeLast(36)
    Canvas(
        modifier.fillMaxWidth().height(176.dp).semantics { contentDescription = description },
    ) {
        if (shown.isEmpty()) return@Canvas
        val labelW = 48.dp.toPx()
        val plotW = size.width - labelW
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        var lo = shown.minOf { it.low } * supply
        var hi = shown.maxOf { it.high } * supply
        if (hi <= lo) {
            val pad = if (hi == 0.0) 1.0 else abs(hi) * 0.05
            lo -= pad
            hi += pad
        }
        val pad = (hi - lo) * 0.08
        lo -= pad
        hi += pad
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // Three round levels; a label the last-price tag would cover is left out.
        val tagBandY = y(shown.last().close * supply)
        val step = CandleMath.niceStep(hi - lo, 3)
        var level = ceil(lo / step) * step
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        while (level < hi) {
            val ly = y(level)
            if (ly > top + 6.dp.toPx() && ly < bottom - 2.dp.toPx()) {
                drawLine(Ink.line, Offset(0f, ly), Offset(plotW - 4.dp.toPx(), ly), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                if (abs(ly - tagBandY) > 18.dp.toPx()) {
                    val t = measurer.measure(Fmt.usd(level), labelStyle)
                    drawText(t, topLeft = Offset(size.width - t.size.width - 2.dp.toPx(), ly - t.size.height / 2f))
                }
            }
            level += step
        }

        val slot = plotW / max(shown.size, 30)
        val body = max(slot * 0.65f, 1.5f)
        val startX = plotW - slot * shown.size
        for ((i, c) in shown.withIndex()) {
            val up = c.close >= c.open
            val color = if (up) Ink.green else Ink.pink
            val cx = startX + slot * i + slot / 2
            drawLine(color, Offset(cx, y(c.high * supply)), Offset(cx, y(c.low * supply)), strokeWidth = 1.2.dp.toPx())
            val yo = y(c.open * supply)
            val yc = y(c.close * supply)
            val bt = min(yo, yc)
            val bh = max(abs(yo - yc), 1.dp.toPx())
            drawRect(color, topLeft = Offset(cx - body / 2, bt), size = Size(body, bh))
        }

        // The last close, with its tag.
        val last = shown.last().close * supply
        val ly = y(last)
        drawLine(
            Ink.green.copy(alpha = 0.45f), Offset(0f, ly), Offset(plotW, ly),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
        )
        val t = measurer.measure(Fmt.usd(last), tagStyle)
        val tagW = max(t.size.width + 12.dp.toPx(), 46.dp.toPx())
        val tagH = 18.dp.toPx()
        val tagY = (ly - tagH / 2).coerceIn(0f, size.height - tagH)
        drawRoundRect(Ink.green, topLeft = Offset(size.width - tagW, tagY), size = Size(tagW, tagH), cornerRadius = CornerRadius(tagH / 2))
        drawText(t, topLeft = Offset(size.width - tagW / 2 - t.size.width / 2f, tagY + (tagH - t.size.height) / 2f))
    }
}
