package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ride.RideSummary
import kotlin.math.roundToInt

/**
 * Detour factor (R9): km ridden per km of straight line. [auto] = computed from the ride log ([rides] = the rides
 * it was computed from; below [DetourCalc.MIN_RIDES] the [DetourCalc.DEFAULT] applies), else set by the rider.
 */
data class Detour(val factor: Double, val auto: Boolean, val rides: Int = 0) {
    /** Computed from enough rides (not the default stand-in). */
    val learned: Boolean get() = auto && rides >= DetourCalc.MIN_RIDES
}

/** Pure R9 arithmetic (unit-tested). */
object DetourCalc {
    /**
     * Until enough rides are known: a common circuity factor for road networks (typical measured values
     * 1.2-1.4). Also the start value of the rider's own setting.
     */
    const val DEFAULT = 1.3

    /** Rides with a shorter straight line (round trips) say nothing about detours and do not count. */
    const val MIN_STRAIGHT_KM = 1.0

    /** The median needs at least this many rides; one or two odd rides should not set the factor. */
    const val MIN_RIDES = 3

    /** Only the newest rides count, so the factor follows a move to other roads. */
    const val MAX_RIDES = 50

    /** Bounds of the factor, computed or set (a value outside says more about a faulty fix than about roads). */
    const val MIN = 1.0
    const val MAX = 2.0

    /** Step of the rider's −/+ setting. */
    const val STEP = 0.05

    /** km ridden per km straight line of each usable ride in [rides] (newest first), at most [MAX_RIDES]. */
    fun ratios(rides: List<RideSummary>): List<Double> = rides.asSequence().mapNotNull { r ->
        val straight = r.ends?.straightKm ?: return@mapNotNull null
        if (straight < MIN_STRAIGHT_KM || r.distanceKm <= 0) null else r.distanceKm / straight
    }.take(MAX_RIDES).toList()

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** The factor that applies: the rider's [manual] value, or the median of the ride log (default below [MIN_RIDES]). */
    fun effective(auto: Boolean, manual: Double, rides: List<RideSummary>): Detour {
        if (!auto) return Detour(manual.coerceIn(MIN, MAX), auto = false)
        val r = ratios(rides)
        val m = median(r)?.takeIf { r.size >= MIN_RIDES } ?: return Detour(DEFAULT, auto = true, rides = r.size)
        return Detour(m.coerceIn(MIN, MAX), auto = true, rides = r.size)
    }

    /** Next manual value after one −/+ step ([delta] = ±1), on the [STEP] grid within [MIN]..[MAX]. */
    fun step(value: Double, delta: Int): Double {
        val steps = (value / STEP).roundToInt() + delta
        return (steps * STEP).coerceIn(MIN, MAX).let { (it * 100).roundToInt() / 100.0 }
    }
}

/**
 * R4: every place the rider can ride to from [bike] and then still get [home] (or back to the bike without a home):
 * the points P with straight line bike→P plus P→home ≤ [sumKm], an ellipse with the bike and home as its foci
 * (a circle of [sumKm] / 2 when there is no home). [sumKm] = the remaining range minus the reserve, divided by the
 * detour factor ([RangeCalc.area]).
 */
data class ReachArea(val bike: GeoPoint, val home: GeoPoint?, val sumKm: Double) {
    /** Straight line from the bike to home (0 without a home). */
    val baseKm: Double get() = home?.let { bike.distanceM(it) / 1000 } ?: 0.0

    /** False when the battery no longer covers the way home plus the reserve: there is no area. */
    val reachable: Boolean get() = sumKm > baseKm
}
