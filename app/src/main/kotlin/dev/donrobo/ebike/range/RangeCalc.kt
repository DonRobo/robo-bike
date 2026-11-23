package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import java.time.YearMonth

/**
 * Battery and assist level the range is computed for. Live: the estimated charge
 * ([dev.donrobo.ebike.battery.LiveCharge]), so the range does not follow the sag on a climb; else the bike's last
 * known reading.
 */
data class RangeReading(val batteryPct: Int, val level: Int, val levelCount: Int, val live: Boolean)

/** Range of one assist level at [RangeCalc.levels]' battery; [km] null = unknown. */
data class LevelRange(val level: Int, val km: Double?, val confidence: Confidence)

/** R6 point: full-battery range learned in [month]; null = not enough rides that month. */
data class MonthRange(val month: YearMonth, val fullKm: Double?)

/** Pure range arithmetic on top of a [LearnedRange] (unit-tested). All distances in km. */
object RangeCalc {
    fun marginFactor(marginPct: Int) = 1.0 - marginPct.coerceIn(0, 90) / 100.0

    /**
     * Consumption used for "now" and "full": the current level's own rate when that level is LEARNED
     * (it tracks level changes while riding), else the overall mix. Level 0 (no assist) uses the mix.
     */
    fun rateFor(learned: LearnedRange, level: Int?): Double? = ownLevelRate(learned, level) ?: learned.pctPerKm

    /** The level's own learned rate, or null when [rateFor] falls back to the mix. */
    fun ownLevelRate(learned: LearnedRange, level: Int?): Double? =
        level?.takeIf { it > 0 }?.let(learned::level)?.takeIf { it.confidence == Confidence.LEARNED }?.pctPerKm?.takeIf { it > 0 }

    /**
     * R2 on its own: range on a full battery. Known as soon as a rate is learned, whether or not the current
     * battery level is ([level] = the reading's assist level; null = the mix). Null while nothing is learned.
     */
    fun full(learned: LearnedRange, level: Int?, marginPct: Int): FullRange? {
        val rate = rateFor(learned, level)?.takeIf { it > 0 } ?: return null
        val km = learned.averagePct(100) / rate
        // the consumption shown with it is the average over the whole charge, so "100 % / x km" holds
        return FullRange(fullKm = km * marginFactor(marginPct), pctPerKm = 100 / km)
    }

    /**
     * R1/R2; null while nothing is learned or no battery reading is known. The remaining range follows the
     * learned discharge curve ([LearnedRange.averagePct]): on a battery whose low percents go less far, 20 %
     * are less than a fifth of the full range.
     */
    fun estimate(learned: LearnedRange, reading: RangeReading?, marginPct: Int): RangeEstimate? {
        val r = reading ?: return null
        val full = full(learned, r.level, marginPct) ?: return null
        val rate = rateFor(learned, r.level)!!
        val remaining = learned.averagePct(r.batteryPct) / rate * marginFactor(marginPct)
        return RangeEstimate(remainingKm = remaining, fullKm = full.fullKm, pctPerKm = full.pctPerKm)
    }

    /**
     * R3: range per assist level 1..[levelCount] at [batteryPct] (unknown levels included, so the list
     * matches the display's levels). Without a level count, the levels seen in the data.
     */
    fun levels(learned: LearnedRange, batteryPct: Int, levelCount: Int?, marginPct: Int): List<LevelRange> {
        val f = marginFactor(marginPct)
        val ids = if (levelCount != null && levelCount > 0) (1..levelCount).toList() else learned.levels.map { it.level }
        return ids.map { l ->
            val lr = learned.level(l)
            val rate = lr?.pctPerKm?.takeIf { it > 0 }
            if (lr == null || rate == null) LevelRange(l, null, Confidence.UNKNOWN)
            else LevelRange(l, learned.averagePct(batteryPct) / rate * f, lr.confidence)
        }
    }

    /**
     * R4: the area reachable from [bike] with the way to [home] still covered: the
     * remaining range minus the reserve the rider wants left on arrival (R5's reserve, so the area ends exactly
     * where "Komme ich heim?" stops being OK), in straight-line km via the [detour] factor (R9).
     */
    fun area(bike: GeoPoint, home: GeoPoint, remainingKm: Double, reserveKm: Double, detour: Double): ReachArea =
        ReachArea(bike, home, ((remainingKm - reserveKm) / detour).coerceAtLeast(0.0))

    /** R6: full-battery range per month, with the same margin as "Voll geladen" so the numbers compare. */
    fun months(learned: LearnedRange, marginPct: Int): List<MonthRange> {
        val f = marginFactor(marginPct)
        val full = learned.averagePct(100)
        return learned.months.map { m -> MonthRange(m.month, m.pctPerKm?.takeIf { it > 0 }?.let { full / it * f }) }
    }
}
