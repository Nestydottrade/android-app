package trade.nesty.app.ui.token

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import trade.nesty.app.data.Candle
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.NIcons
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

private const val DEFAULT_VIEW = 36f
private const val MIN_VIEW = 8f
private const val MAX_VIEW = 240f
private val LABEL_W = 48.dp

/**
 * The part of the candles in view. While scrolled back, the right edge is pinned to a candle's time,
 * so new candles coming in do not move what is on screen; at the newest it follows them.
 */
@Stable
class ChartView {
    /** The time of the candle at the right edge, or null to follow the newest. */
    var pin by mutableStateOf<Long?>(null)
        private set
    /** How far past that candle the edge sits, in candles. */
    private var frac by mutableFloatStateOf(0f)
    /** Candles across the view. */
    var count by mutableFloatStateOf(DEFAULT_VIEW)
        private set
    internal var fling: Job? = null

    /** Candles hidden to the right of the view, counted from the newest. */
    fun back(candles: List<Candle>): Float {
        val p = pin ?: return 0f
        if (candles.isEmpty()) return 0f
        var i = candles.binarySearchBy(p) { it.time }
        if (i < 0) i = (-i - 1).coerceAtMost(candles.size - 1)
        return (candles.size - 1 - i) + frac
    }

    private fun maxBack(candles: List<Candle>): Float = max(0f, candles.size - min(count, candles.size.toFloat()))

    fun setBack(candles: List<Candle>, value: Float) {
        val b = value.coerceIn(0f, maxBack(candles))
        if (b <= 0.001f || candles.isEmpty()) {
            pin = null
            frac = 0f
            return
        }
        val whole = floor(b).toInt().coerceAtMost(candles.size - 1)
        pin = candles[candles.size - 1 - whole].time
        frac = b - whole
    }

    fun newest() {
        fling?.cancel()
        pin = null
        frac = 0f
    }

    /** Drags by [dx] pixels of a plot [plotW] wide: to the right shows older candles. */
    fun pan(candles: List<Candle>, plotW: Float, dx: Float) {
        if (dx == 0f || plotW <= 0f) return
        setBack(candles, back(candles) + dx / (plotW / count))
    }

    /** Pinches by [zoom] around [x]: the candle under the fingers stays under them. */
    fun zoom(candles: List<Candle>, plotW: Float, zoom: Float, x: Float) {
        if (zoom == 1f || zoom <= 0f || plotW <= 0f) return
        val under = (plotW - x) / (plotW / count) + back(candles)
        count = (count / zoom).coerceIn(MIN_VIEW, max(DEFAULT_VIEW, min(candles.size.toFloat(), MAX_VIEW)))
        setBack(candles, under - (plotW - x) / (plotW / count))
    }
}

private enum class Touch { UNDECIDED, PAN, ZOOM, PAGE }

/**
 * Market cap candles: each price times [supply]. The newest at the right, about thirty in view,
 * three round levels and a tag at the last close. [ath], the all-time high in dollars per token,
 * is a dotted line where it falls in view, as on the site. A sideways drag scrolls back through
 * the candles, two fingers zoom; an up or down drag is left to the page.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    supply: Double,
    description: String,
    view: ChartView,
    modifier: Modifier = Modifier,
    ath: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink.muted) }
    val tagStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.greenInk) }
    val athStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Ink.text2) }
    val athTagStyle = remember { TextStyle(fontFamily = Barlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.ground) }
    val latest by rememberUpdatedState(candles)
    val scope = rememberCoroutineScope()
    val scrolledBack by remember(view) { derivedStateOf { view.pin != null } }

    Box(modifier.fillMaxWidth().height(176.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clipToBounds()
                .semantics {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction("Show older candles") {
                            view.setBack(latest, view.back(latest) + view.count / 2)
                            true
                        },
                        CustomAccessibilityAction("Show newer candles") {
                            view.setBack(latest, view.back(latest) - view.count / 2)
                            true
                        },
                    )
                }
                .pointerInput(view) {
                    val labelW = LABEL_W.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.fling?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var touch = Touch.UNDECIDED
                        var travelX = 0f
                        var travelY = 0f
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val plotW = size.width - labelW
                            if (pressed.size >= 2 && touch != Touch.PAGE) {
                                touch = Touch.ZOOM
                                val c = event.calculateCentroid(useCurrent = true)
                                view.zoom(latest, plotW, event.calculateZoom(), c.x)
                                view.pan(latest, plotW, event.calculatePan().x)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // After a pinch the finger left behind does not drag; an up or down drag is the page's.
                            if (touch == Touch.ZOOM || touch == Touch.PAGE) continue
                            val ch = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val d = ch.positionChange()
                            if (touch == Touch.UNDECIDED) {
                                travelX += d.x
                                travelY += d.y
                                if (abs(travelX) > slop && abs(travelX) > abs(travelY)) {
                                    touch = Touch.PAN
                                    ch.consume()
                                } else if (abs(travelY) > slop) {
                                    touch = Touch.PAGE
                                }
                            } else {
                                view.pan(latest, plotW, d.x)
                                ch.consume()
                            }
                        }
                        if (touch == Touch.PAN) {
                            val plotW = size.width - labelW
                            val perCandle = plotW / view.count
                            val v = tracker.calculateVelocity().x / perCandle
                            if (abs(v) > 1f) {
                                view.fling = scope.launch {
                                    animateDecay(view.back(latest), v, FloatExponentialDecaySpec()) { value, _ ->
                                        view.setBack(latest, value)
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            val n = candles.size
            if (n == 0) return@Canvas
            val labelW = LABEL_W.toPx()
            val plotW = size.width - labelW
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val count = view.count
            val back = view.back(candles)
            val slot = plotW / count
            // The candle d places from the newest has its centre at plotW - (d - back + 0.5) * slot.
            fun x(d: Int) = plotW - (d - back + 0.5f) * slot
            val firstD = ceil(back - 0.5f).toInt().coerceIn(0, n - 1)
            val lastD = floor(back + count - 0.5f).toInt().coerceIn(firstD, n - 1)
            val inView = (firstD..lastD).map { candles[n - 1 - it] }

            var lo = inView.minOf { it.low } * supply
            var hi = inView.maxOf { it.high } * supply
            if (hi <= lo) {
                val pad = if (hi == 0.0) 1.0 else abs(hi) * 0.05
                lo -= pad
                hi += pad
            }
            val pad = (hi - lo) * 0.08
            lo -= pad
            hi += pad
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

            // The all-time high's market cap, and its place where it falls in view.
            val athMc = ath?.let { it * supply }
            val athY = athMc?.let { y(it) }?.takeIf { it in top..bottom }

            // Three round levels; a label the last-price tag or the all-time high's would cover is left out.
            val last = candles.last().close * supply
            val tagBandY = y(last).coerceIn(0f, size.height)
            val step = CandleMath.niceStep(hi - lo, 3)
            var level = ceil(lo / step) * step
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
            while (level < hi) {
                val ly = y(level)
                if (ly > top + 6.dp.toPx() && ly < bottom - 2.dp.toPx()) {
                    drawLine(Ink.line, Offset(0f, ly), Offset(plotW - 4.dp.toPx(), ly), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                    if (abs(ly - tagBandY) > 18.dp.toPx() && (athY == null || abs(ly - athY) > 18.dp.toPx())) {
                        val t = measurer.measure(Fmt.usd(level), labelStyle)
                        drawText(t, topLeft = Offset(size.width - t.size.width - 2.dp.toPx(), ly - t.size.height / 2f))
                    }
                }
                level += step
            }

            // The candles, with the ones half in view at either edge cut by the plot's edge.
            val body = max(slot * 0.65f, 1.5f)
            clipRect(right = plotW) {
                for (d in (firstD - 1).coerceAtLeast(0)..(lastD + 1).coerceAtMost(n - 1)) {
                    val c = candles[n - 1 - d]
                    val color = if (c.close >= c.open) Ink.green else Ink.pink
                    val cx = x(d)
                    drawLine(color, Offset(cx, y(c.high * supply)), Offset(cx, y(c.low * supply)), strokeWidth = 1.2.dp.toPx())
                    val yo = y(c.open * supply)
                    val yc = y(c.close * supply)
                    val bh = max(abs(yo - yc), 1.dp.toPx())
                    drawRect(color, topLeft = Offset(cx - body / 2, min(yo, yc)), size = Size(body, bh))
                }
            }

            // The last close's tag; scrolled back, it waits at the edge it is past.
            val ly = y(last)
            val t = measurer.measure(Fmt.usd(last), tagStyle)
            val tagW = max(t.size.width + 12.dp.toPx(), 46.dp.toPx())
            val tagH = 18.dp.toPx()
            val tagY = (ly - tagH / 2).coerceIn(0f, size.height - tagH)

            // The all-time high: a dotted line, its name over the line's start (under it at the top
            // edge) and its value at the axis, unless the last close's tag sits there.
            if (athMc != null && athY != null) {
                drawLine(
                    Ink.text2.copy(alpha = 0.8f), Offset(0f, athY), Offset(plotW, athY),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
                )
                val name = measurer.measure("All-time high", athStyle)
                val gap = 2.dp.toPx()
                val nameY = if (athY - gap - name.size.height >= 0f) athY - gap - name.size.height else athY + gap
                drawText(name, topLeft = Offset(4.dp.toPx(), nameY))
                val athTagY = (athY - tagH / 2).coerceIn(0f, size.height - tagH)
                if (abs(athTagY - tagY) >= tagH) {
                    val a = measurer.measure(Fmt.usd(athMc), athTagStyle)
                    val aW = max(a.size.width + 12.dp.toPx(), 46.dp.toPx())
                    drawRoundRect(Ink.text2, topLeft = Offset(size.width - aW, athTagY), size = Size(aW, tagH), cornerRadius = CornerRadius(tagH / 2))
                    drawText(a, topLeft = Offset(size.width - aW / 2 - a.size.width / 2f, athTagY + (tagH - a.size.height) / 2f))
                }
            }

            // The last close, over everything else.
            if (ly in 0f..size.height) {
                drawLine(
                    Ink.green.copy(alpha = 0.45f), Offset(0f, ly), Offset(plotW, ly),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                )
            }
            drawRoundRect(Ink.green, topLeft = Offset(size.width - tagW, tagY), size = Size(tagW, tagH), cornerRadius = CornerRadius(tagH / 2))
            drawText(t, topLeft = Offset(size.width - tagW / 2 - t.size.width / 2f, tagY + (tagH - t.size.height) / 2f))
        }

        // Scrolled back: one tap returns to the newest candle.
        if (scrolledBack) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = LABEL_W, bottom = 2.dp)
                    .size(44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Back to the newest candle") { view.newest() }
                    .semantics { contentDescription = "Back to the newest candle" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Ink.raised)
                        .border(1.dp, Ink.line3, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.text2, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
