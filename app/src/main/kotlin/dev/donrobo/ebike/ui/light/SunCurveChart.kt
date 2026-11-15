package dev.donrobo.ebike.ui.light

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.donrobo.ebike.R
import dev.donrobo.ebike.light.SunDay
import dev.donrobo.ebike.light.SunSchedule
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Which switching point a gesture moves. */
enum class CurvePoint { MORNING, EVENING }

/**
 * Geometry of the artboard's SVG (viewBox 350×220): plot x 8..342 for 0..24 h, y 14..190. The elevation
 * range adapts to the day (the artboard's 30°..−65° is the minimum span) so a summer noon is never clipped.
 */
class CurveGeometry(val day: SunDay) {
    // 15° headroom keeps the noon peak below the "Jetzt" label at the top
    val top: Double = maxOf(30.0, ceil((day.maxDeg + 15) / 5) * 5)
    val bottom: Double = minOf(-30.0, floor((day.minDeg - 5) / 5) * 5)

    fun x(minute: Int): Float = (L + minute / SunDay.MINUTES.toFloat() * (R - L))
    fun y(deg: Double): Float = (T + (top - deg) / (top - bottom) * (B - T)).toFloat()
    fun minuteAt(x: Float): Int = ((x - L) / (R - L) * SunDay.MINUTES).roundToInt().coerceIn(0, SunDay.MINUTES)

    companion object {
        const val W = 350f; const val H = 220f
        const val L = 8f; const val R = 342f; const val T = 14f; const val B = 190f
    }
}

/**
 * The sun curve (L3, S2): today's elevation, horizon, "Jetzt", tinted on-areas and the two draggable
 * points. A drag only starts on a point (touch target ±24 units horizontally, like the artboard's r=24
 * hit circle, over the full height), and it consumes the pointer so the page does not scroll meanwhile.
 * [onDrag] gets the minute the finger is at; [onDragEnd] persists.
 */
@Composable
fun SunCurveChart(
    day: SunDay,
    schedule: SunSchedule,
    nowMinute: Int,
    morningMinute: Int,
    eveningMinute: Int,
    onDrag: (CurvePoint, Int) -> Unit,
    onDragEnd: (CurvePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val g = remember(day) { CurveGeometry(day) }
    val curvePath = remember(day) {
        Path().apply {
            for (i in 0..96) {
                val m = i * 15
                val x = g.x(m); val y = g.y(day.elev[m])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val horizon = stringResource(R.string.light_horizon)
    val now = stringResource(R.string.light_now)
    val axisStart = stringResource(R.string.light_axis_start)
    val axisEnd = stringResource(R.string.light_axis_end)
    val a11y = stringResource(R.string.light_curve_a11y)
    val density = LocalDensity.current
    val m by rememberUpdatedState(morningMinute)
    val e by rememberUpdatedState(eveningMinute)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)

    Canvas(
        modifier.fillMaxWidth().aspectRatio(CurveGeometry.W / CurveGeometry.H)
            .semantics { contentDescription = a11y }
            .pointerInput(g) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = size.width / CurveGeometry.W
                    val ux = down.position.x / s
                    val dm = abs(ux - g.x(m)); val de = abs(ux - g.x(e))
                    val which = when {
                        minOf(dm, de) > HIT -> null
                        dm < de -> CurvePoint.MORNING
                        de < dm -> CurvePoint.EVENING
                        else -> if (g.minuteAt(ux) <= day.noon) CurvePoint.MORNING else CurvePoint.EVENING
                    } ?: return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        if (ch.position != ch.previousPosition) drag(which, g.minuteAt(ch.position.x / s))
                        ch.consume()
                    }
                    dragEnd(which)
                }
            },
    ) {
        val s = size.width / CurveGeometry.W
        fun px(v: Float) = v * s
        val label = TextStyle(fontSize = with(density) { (11 * s).toSp() })

        // tinted areas where the curve wants the light on
        for ((a, b) in schedule.onIntervals()) {
            drawRect(c.accentTint, Offset(px(g.x(a)), px(CurveGeometry.T)), Size(px(g.x(b) - g.x(a)), px(CurveGeometry.B - CurveGeometry.T)))
        }
        val hy = g.y(0.0)
        drawLine(c.neutral, Offset(px(CurveGeometry.L), px(hy)), Offset(px(CurveGeometry.R), px(hy)), px(1f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(4f), px(4f))))
        text(measurer, horizon, 12f, hy - 6, label.copy(fontWeight = FontWeight.SemiBold, color = c.textMuted), s, Anchor.START)

        withTransform({ scale(s, s, Offset.Zero) }) {
            drawPath(curvePath, c.text, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val nx = g.x(nowMinute)
        drawLine(c.primary, Offset(px(nx), px(CurveGeometry.T)), Offset(px(nx), px(CurveGeometry.B)), px(1.5f))
        val nowStyle = label.copy(fontWeight = FontWeight.Bold, color = c.primaryStrong)
        // label left of the line like the artboard, flipped to the right where it would leave the plot
        val nowWidth = measurer.measure(now, nowStyle).size.width / s
        if (nx - 5 - nowWidth >= CurveGeometry.L) text(measurer, now, nx - 5, 28f, nowStyle, s, Anchor.END)
        else text(measurer, now, nx + 5, 28f, nowStyle, s, Anchor.START)

        for (minute in listOf(morningMinute, eveningMinute)) {
            val x = g.x(minute)
            drawLine(c.accentLine, Offset(px(x), px(CurveGeometry.T)), Offset(px(x), px(CurveGeometry.B)), px(1.5f))
        }
        for (minute in listOf(morningMinute, eveningMinute)) {
            val center = Offset(px(g.x(minute)), px(g.y(day.elev[minute])))
            drawCircle(c.surface, px(11.5f), center)
            drawCircle(c.accent, px(8.5f), center)
        }

        val axis = label.copy(color = c.textMuted)
        text(measurer, axisStart, 8f, 210f, axis, s, Anchor.START)
        text(measurer, "6", 91.5f, 210f, axis, s, Anchor.MIDDLE)
        text(measurer, "12", 175f, 210f, axis, s, Anchor.MIDDLE)
        text(measurer, "18", 258.5f, 210f, axis, s, Anchor.MIDDLE)
        text(measurer, axisEnd, 342f, 210f, axis, s, Anchor.END)
    }
}

/** Half width of a point's touch strip in viewBox units (the artboard's hit circle has r = 24). */
private const val HIT = 24f

private enum class Anchor { START, MIDDLE, END }

/** SVG-like text: (x, y) in viewBox units, y = baseline. */
private fun DrawScope.text(measurer: TextMeasurer, s: String, x: Float, y: Float, style: TextStyle, scale: Float, anchor: Anchor) {
    val layout = measurer.measure(s, style)
    val left = x * scale - when (anchor) { Anchor.START -> 0f; Anchor.MIDDLE -> layout.size.width / 2f; Anchor.END -> layout.size.width.toFloat() }
    drawText(layout, topLeft = Offset(left, y * scale - layout.firstBaseline))
}
