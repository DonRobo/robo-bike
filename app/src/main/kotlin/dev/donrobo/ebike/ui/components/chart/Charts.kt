package dev.donrobo.ebike.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A data point of a [LineChart] (x and y in the caller's units, e.g. km and km/h). */
@Immutable
data class ChartPoint(val x: Float, val y: Float)

/** Axis tick: position in data units plus its label (formatted by the caller, units/locale). */
@Immutable
data class AxisTick(val value: Float, val label: String)

/** Pure axis helpers (unit-tested). */
object ChartScale {
    /**
     * "Nice" tick values covering [min]..[max] with at most [maxIntervals] intervals, steps of 1, 2, 2.5
     * or 5 times a power of ten (or whole numbers only with [integer]); the first tick is <= [min], the last >= [max].
     * E.g. 0..26 -> 0, 10, 20, 30.
     */
    fun ticks(min: Double, max: Double, maxIntervals: Int = 3, integer: Boolean = false): List<Double> {
        val lo = min(min, max); var hi = max(min, max)
        if (hi - lo < 1e-9) hi = lo + 1
        val raw = (hi - lo) / maxIntervals
        val mag = 10.0.pow(floor(log10(raw)))
        val steps = if (integer) listOf(1.0, 2.0, 5.0, 10.0) else listOf(1.0, 2.0, 2.5, 5.0, 10.0)
        var step = steps.map { it * mag }.first { it >= raw - 1e-12 }
        if (integer) step = max(1.0, ceil(step))
        val first = floor(lo / step + 1e-9) * step
        val out = ArrayList<Double>()
        var v = first
        while (v < hi + step - 1e-9 && out.size <= maxIntervals + 2) { out += v; if (v >= hi - 1e-9) break; v += step }
        return out
    }

    /** Evenly downsamples [points] to at most [max] points by averaging y within x buckets (keeps first and last). */
    fun downsample(points: List<ChartPoint>, max: Int = 200): List<ChartPoint> {
        if (points.size <= max) return points
        val x0 = points.first().x; val x1 = points.last().x
        val w = (x1 - x0) / (max - 2).coerceAtLeast(1)
        if (w <= 0f) return listOf(points.first(), points.last())
        val out = ArrayList<ChartPoint>(max)
        out += points.first()
        var i = 1
        for (b in 0 until max - 2) {
            val end = x0 + w * (b + 1)
            var sx = 0f; var sy = 0f; var n = 0
            while (i < points.size - 1 && points[i].x <= end) { sx += points[i].x; sy += points[i].y; n++; i++ }
            if (n > 0) out += ChartPoint(sx / n, sy / n)
        }
        out += points.last()
        return out
    }

    /** Largest of [ticks] at or below [max] but above 0 (the bar chart's reference line); null if none. */
    fun reference(max: Double): Double? {
        if (max <= 0) return null
        return ticks(0.0, max, 3).lastOrNull { it > 0 && it <= max + 1e-9 }
    }
}

private val axisStyle @Composable get() = TextStyle(fontFamily = AppTheme.type.caption.fontFamily, fontSize = 11.sp, color = AppTheme.colors.textMuted)

/**
 * Line chart in the Fahrtdetail look (A3/F4): y labels right-aligned in a 28 dp gutter, light grid lines
 * at the [yTicks], green line with a tinted area below, [xTicks] under the plot (first start-, last
 * end-aligned). [average] draws the dashed average line; [stepped] draws steps (assist level).
 * The y range is the first to the last y tick; the x range is [xRange].
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    xRange: ClosedFloatingPointRange<Float>,
    yTicks: List<AxisTick>,
    xTicks: List<AxisTick>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    stepped: Boolean = false,
    average: Float? = null,
    height: Dp = 150.dp,
    lineColor: Color = AppTheme.colors.primary,
    fillColor: Color = AppTheme.colors.primaryTint,
) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle
    val c = AppTheme.colors
    Canvas(modifier.fillMaxWidth().height(height).semantics { this.contentDescription = contentDescription }) {
        val left = 28.dp.toPx(); val right = size.width - 4.dp.toPx()
        val top = 10.dp.toPx(); val bottom = size.height - 30.dp.toPx()
        val y0 = yTicks.firstOrNull()?.value ?: 0f; val y1 = yTicks.lastOrNull()?.value ?: 1f
        fun px(x: Float) = left + (x - xRange.start) / (xRange.endInclusive - xRange.start).coerceAtLeast(1e-6f) * (right - left)
        fun py(y: Float) = bottom - (y.coerceIn(y0, y1) - y0) / (y1 - y0).coerceAtLeast(1e-6f) * (bottom - top)

        for (t in yTicks) {
            val y = py(t.value)
            drawLine(c.border, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            label(measurer, t.label, style, Offset(22.dp.toPx(), y), Align.END, centerY = true)
        }
        if (points.size >= 2) {
            val line = Path()
            points.forEachIndexed { i, p ->
                val x = px(p.x); val y = py(p.y)
                when {
                    i == 0 -> line.moveTo(x, y)
                    stepped -> { line.lineTo(x, py(points[i - 1].y)); line.lineTo(x, y) }
                    else -> line.lineTo(x, y)
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(px(points.last().x), bottom); lineTo(px(points.first().x), bottom); close()
            }
            // points outside the x range (e.g. odometer beyond the summary's distance) stay inside the plot
            clipRect(left - 2.dp.toPx(), 0f, right + 2.dp.toPx(), size.height) {
                drawPath(area, fillColor)
                drawPath(line, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (average != null) {
            val y = py(average)
            drawLine(
                c.text, Offset(left, y), Offset(right, y), strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        val ly = size.height - 8.dp.toPx()
        xTicks.forEachIndexed { i, t ->
            val align = when (i) { 0 -> Align.START; xTicks.lastIndex -> Align.END; else -> Align.CENTER }
            label(measurer, t.label, style, Offset(px(t.value), ly), align, baseline = true)
        }
    }
}

/**
 * Bar chart in the Statistik look (F6): one rounded bar per value, a solid baseline, a dashed
 * [reference] line labelled at the right, and [xTicks] by bar index under the plot. Zero values draw no bar.
 */
@Composable
fun BarChart(
    values: List<Float>,
    xTicks: List<AxisTick>,
    reference: AxisTick?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 136.dp,
    barColor: Color = AppTheme.colors.primary,
) {
    val measurer = rememberTextMeasurer()
    val style = axisStyle
    val c = AppTheme.colors
    Canvas(modifier.fillMaxWidth().height(height).semantics { this.contentDescription = contentDescription }) {
        val bottom = size.height - 26.dp.toPx(); val top = 13.dp.toPx()
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = min(slot * 0.62f, if (n > 14) 7.dp.toPx() else 24.dp.toPx())
        val maxV = max(values.maxOrNull() ?: 0f, reference?.value ?: 0f).coerceAtLeast(1e-6f)
        fun py(v: Float) = bottom - v / maxV * (bottom - top)
        drawLine(c.outline, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        if (reference != null) {
            val y = py(reference.value)
            drawLine(
                c.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            )
        }
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val cx = slot * (i + 0.5f)
            val y = py(v)
            drawRoundRect(
                barColor, Offset(cx - barW / 2, y), Size(barW, bottom - y),
                cornerRadius = CornerRadius(min(barW / 2, 6.dp.toPx())),
            )
        }
        if (reference != null) {
            // drawn last on a surface-coloured plate, so a tall bar at the right edge cannot hide it
            val r = measurer.measure(reference.label, style)
            val pad = 3.dp.toPx()
            val x = size.width - r.size.width
            val top = py(reference.value) - 6.dp.toPx() - r.firstBaseline
            drawRoundRect(c.surface, Offset(x - pad, top), Size(r.size.width + pad, r.size.height.toFloat()), CornerRadius(pad))
            drawText(r, topLeft = Offset(x, top))
        }
        val ly = size.height - 8.dp.toPx()
        for (t in xTicks) label(measurer, t.label, style, Offset(slot * (t.value + 0.5f), ly), Align.CENTER, baseline = true, clampWidth = size.width)
    }
}

private enum class Align { START, CENTER, END }

private fun DrawScope.label(
    m: TextMeasurer, text: String, style: TextStyle, at: Offset, align: Align,
    centerY: Boolean = false, baseline: Boolean = false, clampWidth: Float? = null,
) {
    val r = m.measure(text, style)
    var x = when (align) { Align.START -> at.x; Align.CENTER -> at.x - r.size.width / 2f; Align.END -> at.x - r.size.width }
    if (clampWidth != null) x = x.coerceIn(0f, clampWidth - r.size.width)
    val y = when {
        centerY -> at.y - r.size.height / 2f
        baseline -> at.y - r.firstBaseline
        else -> at.y
    }
    drawText(r, topLeft = Offset(x, y))
}
