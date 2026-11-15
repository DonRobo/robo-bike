package dev.donrobo.ebike.range

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Learned range of the active bike at the current battery level (R1, R2). Distances in km, consumption in
 * % per km: the current assist level's own rate when it is learned, else the rider's mix (see [RangeCalc.rateFor]),
 * averaged over a full charge (the battery need not drain linearly, see [RangeModel.bands]), so
 * 100 / [pctPerKm] is [fullKm] before the margin. The safety margin (R7) is already taken off the distances.
 */
data class RangeEstimate(val remainingKm: Double, val fullKm: Double, val pctPerKm: Double)

/**
 * Range on a full battery and the consumption behind it (R2, margin taken off). Known once a rate is learned,
 * also while the current battery level is not (bike not connected since install / update): then there is a
 * [FullRange] but no [RangeEstimate].
 */
data class FullRange(val fullKm: Double, val pctPerKm: Double)

/** "Komme ich heim?" (R5): road distance home (straight line x detour factor) and whether the remaining range covers it plus the reserve. */
data class HomeReach(val distanceKm: Double, val reachable: Boolean)

/** Everything the Reichweite screen shows, computed by [RangeEngine] in one place. */
data class RangeDetail(
    val learned: LearnedRange,
    val reading: RangeReading?,
    val marginPct: Int,
    val window: Int,
    /** Null while nothing is learned. */
    val full: FullRange?,
    /** Null while nothing is learned or the battery level is unknown. */
    val estimate: RangeEstimate?,
    /** Per level at the current battery (full battery when no reading is known). */
    val levels: List<LevelRange>,
    val months: List<MonthRange>,
    val position: BikePosition?,
    val home: HomeStatus,
)

/**
 * What the range feature publishes for other screens (Start, handlebar mode, widget). Null = not known
 * (nothing learned yet / no home set / no current position). Single writer: [RangeEngine].
 */
object RangeState {
    val estimate = MutableStateFlow<RangeEstimate?>(null)
    /** Full-battery range, also known while [estimate] is not (battery level unknown). */
    val full = MutableStateFlow<FullRange?>(null)
    val home = MutableStateFlow<HomeReach?>(null)
    /** Full picture for the Reichweite screen; null until the engine ran once (or no bike). */
    val detail = MutableStateFlow<RangeDetail?>(null)
}
