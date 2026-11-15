package dev.donrobo.ebike.ui.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.LevelRange
import dev.donrobo.ebike.range.MonthRange
import dev.donrobo.ebike.range.RangeDetail
import java.time.YearMonth

/**
 * Everything the Reichweite screen shows. [detail] is the engine's result (null = no bike); the other
 * fields are the settings behind the controls, read directly so the controls answer at once.
 */
data class RangeUiState(
    val detail: RangeDetail?,
    val window: Int,
    val marginPct: Int,
    val homeEnabled: Boolean,
    val home: GeoPoint?,
    val reserveKm: Double,
)

/** What the summary card (and the ring) can say. */
enum class RangeSummaryMode {
    /** No rate learned yet: "Wird aus deinen ersten Fahrten gelernt" with the progress. */
    LEARNING,
    /** A rate is learned but the battery level is unknown (not connected since install/update): full range only. */
    BATTERY_UNKNOWN,
    /** Remaining and full range. */
    KNOWN,
}

fun summaryMode(d: RangeDetail?): RangeSummaryMode = when {
    d?.estimate != null -> RangeSummaryMode.KNOWN
    d?.full != null -> RangeSummaryMode.BATTERY_UNKNOWN
    else -> RangeSummaryMode.LEARNING
}

/** One row of "Pro Unterstützungsstufe"; [fraction] relative to the longest known range. */
data class LevelBar(val level: Int, val km: Double?, val fraction: Float, val confidence: Confidence, val current: Boolean)

fun levelBars(levels: List<LevelRange>, current: Int?): List<LevelBar> {
    val max = levels.mapNotNull { it.km }.maxOrNull()?.takeIf { it > 0 }
    return levels.map { l ->
        LevelBar(l.level, l.km, if (max == null || l.km == null) 0f else (l.km / max).toFloat().coerceIn(0f, 1f), l.confidence, l.level == current)
    }
}

/** A month with an R6 value, at position [index] (0 = oldest of the six). */
data class HealthPoint(val index: Int, val month: YearMonth, val km: Double)

fun healthPoints(months: List<MonthRange>): List<HealthPoint> =
    months.mapIndexedNotNull { i, m -> m.fullKm?.let { HealthPoint(i, m.month, it) } }

/**
 * Vertical position of [km] between 0 (top of the plot) and 1 (bottom). The scale spans at least 20 % of
 * the largest value, so a change of a few km (season, temperature) does not look like a collapse.
 */
fun healthY(km: Double, points: List<HealthPoint>): Float {
    val max = points.maxOf { it.km }
    val min = points.minOf { it.km }
    val span = maxOf(max - min, 0.2 * max).takeIf { it > 0 } ?: return 0f
    return ((max - km) / span).toFloat().coerceIn(0f, 1f)
}


