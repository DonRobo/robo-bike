package dev.donrobo.ebike.ride

import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Summary of one ride file, derived from its samples by [RideCsv.summarize] (and cached by [RideIndex]).
 *
 * - [durationS]: start to the last moving sample (the 5-minute standing tail is not counted).
 * - [movingS]: only intervals while moving (stops at lights excluded; gaps > 10 s ignored).
 * - [batteryStartPct], [batteryEndPct]: the battery's rest level at the start and the end of the ride, not the
 *   momentary readings (the display's percent sags under load, see [dev.donrobo.ebike.battery.BatteryReadings]).
 * - [batteryUsedPct]: start minus end rest level. The only measure of a ride's battery use in the app; the
 *   range model, statistics and route comparison all read it.
 * - [climbM]: metres climbed (null when the ride has no altitude, e.g. no GPS track or an old file).
 * - [shape]: outline of the GPS track for thumbnails and route recognition (null without a usable track).
 */
data class RideSummary(
    val file: File, val startMs: Long, val durationS: Long, val distanceKm: Double,
    val batteryStartPct: Int, val batteryEndPct: Int,
    val endMs: Long = startMs + durationS * 1000,
    val movingS: Long = durationS,
    val maxSpeedKmh: Double = 0.0,
    val hasTrack: Boolean = false,
    val climbM: Double? = null,
    val levels: List<LevelStats> = emptyList(),
    val shape: RideShape? = null,
) {
    val batteryUsedPct: Double get() = (batteryStartPct - batteryEndPct).coerceAtLeast(0).toDouble()

    /** Stable id within a bike: the file name without extension (start time, e.g. "2026-11-11_215300"). */
    val id: String get() = file.nameWithoutExtension
}

/**
 * Distance and riding time in one assist level (0 = no assist) during one ride (R3). No battery share: one
 * ride's use cannot be split over its levels from whole-percent, sagging readings; the range model learns
 * per-level consumption across rides from the ride totals instead ([dev.donrobo.ebike.range.RangeModel]).
 */
data class LevelStats(val level: Int, val distanceKm: Double, val timeS: Long)

enum class StatsPeriod { WEEK, MONTH, YEAR }

/** Totals of the rides that started in [[start], [endExclusive]) (local dates). */
data class StatsBucket(
    val start: LocalDate, val endExclusive: LocalDate,
    val rides: Int = 0, val distanceKm: Double = 0.0, val durationS: Long = 0, val movingS: Long = 0, val batteryPct: Double = 0.0,
) {
    /** Battery charges used (Statistik "Akkuladungen"). */
    val charges: Double get() = batteryPct / 100.0
}

/** One week/month/year: [total] plus [parts] for the bar chart (days for week and month, months for a year). */
data class PeriodStats(val period: StatsPeriod, val total: StatsBucket, val parts: List<StatsBucket>)

/** Pure ride calculations (unit-tested). */
object RideStats {
    /**
     * Per-level breakdown of one ride: each interval between two samples counts for the level of its first
     * sample, odometer distance always, riding time while moving (gap <= 10 s).
     */
    fun levelBreakdown(s: List<RideSample>): List<LevelStats> {
        if (s.size < 2) return emptyList()
        val km = HashMap<Int, Double>(); val time = HashMap<Int, Double>()
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val d = b.odoKm - a.odoKm
            if (d > 0) km.merge(a.gear, d, Double::plus)
            val dt = (b.epochMs - a.epochMs) / 1000.0
            if (a.speedKmh > 0 && dt in 0.0..RideCsv.MAX_STEP_S) time.merge(a.gear, dt, Double::plus)
        }
        return (km.keys + time.keys).sorted().map { LevelStats(it, km[it] ?: 0.0, (time[it] ?: 0.0).toLong()) }
    }

    /** First day of the period containing [date]. */
    fun periodStart(date: LocalDate, period: StatsPeriod, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): LocalDate = when (period) {
        StatsPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        StatsPeriod.MONTH -> date.withDayOfMonth(1)
        StatsPeriod.YEAR -> date.withDayOfYear(1)
    }

    fun periodEnd(start: LocalDate, period: StatsPeriod): LocalDate = when (period) {
        StatsPeriod.WEEK -> start.plusWeeks(1)
        StatsPeriod.MONTH -> start.plusMonths(1)
        StatsPeriod.YEAR -> start.plusYears(1)
    }

    /**
     * Totals of the week/month/year containing [date] (F6). A ride counts on the local date it started.
     */
    fun aggregate(
        rides: List<RideSummary>, period: StatsPeriod, date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(), firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): PeriodStats {
        val start = periodStart(date, period, firstDayOfWeek)
        val end = periodEnd(start, period)
        val inPeriod = rides.map { it to Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .filter { (_, d) -> !d.isBefore(start) && d.isBefore(end) }
        val parts = generateSequence(start) { if (period == StatsPeriod.YEAR) it.plusMonths(1) else it.plusDays(1) }
            .takeWhile { it.isBefore(end) }
            .map { s ->
                val e = if (period == StatsPeriod.YEAR) s.plusMonths(1) else s.plusDays(1)
                bucket(s, e, inPeriod.filter { (_, d) -> !d.isBefore(s) && d.isBefore(e) }.map { it.first })
            }.toList()
        return PeriodStats(period, bucket(start, end, inPeriod.map { it.first }), parts)
    }

    private fun bucket(start: LocalDate, end: LocalDate, rides: List<RideSummary>) = StatsBucket(
        start, end, rides.size, rides.sumOf { it.distanceKm }, rides.sumOf { it.durationS }, rides.sumOf { it.movingS },
        rides.sumOf { it.batteryUsedPct },
    )
}
