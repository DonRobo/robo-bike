package dev.donrobo.ebike.battery

import dev.donrobo.ebike.ride.RideSample

/**
 * What the display's battery percent means, and how to read the true charge from it. One place for this
 * physics; ride summaries, the range model, the live range and the charge reminder all build on it.
 *
 * The display derives its percent from the pack **voltage**. Under load the voltage sags (internal
 * resistance), so the percent drops while climbing or accelerating and comes back once the load drops.
 * On the owner's real rides the reading went down and up dozens of times per ride and recovered by up to
 * 7 points from its lowest value. So:
 * - A single reading under load is not the charge; only a reading **at rest** is (no load for a while).
 * - Sag is one-sided: a reading is never above the charge (apart from ±1 display flicker). Within one ride
 *   the charge itself can only fall: a ride file never spans a charge, because it ends after 5 minutes
 *   without movement ([dev.donrobo.ebike.ride.RideTracker]) and nobody charges an e-bike in 5 minutes.
 * - Hence the charge during a ride is the lowest non-increasing curve that stays on or above every
 *   reading: at any moment, the highest reading still to come. Its value at the start is the highest
 *   reading of the ride; at the end, the highest reading of the final standing phase (no load there, the
 *   charge is constant, lower readings are sag still recovering).
 * - Consumption of a ride is the difference of these two rest levels, never a sum of single steps:
 *   one percent step is mostly sag noise.
 * - A rise is recovery, not charging, unless it exceeds anything recovery can do ([CHARGE_RISE_PCT]).
 */
object BatteryReadings {
    /**
     * A rise of the reading by at least this much over a lower reading means the battery was charged.
     * Recovery from sag reached 7 points on the owner's rides (from the lowest reading under load back to
     * rest, ride of 2026-11-11 evening) and at most 4 points between the end of one ride and the start of
     * the next; 10 is clearly above both and far below any real top-up at the charger.
     */
    const val CHARGE_RISE_PCT = 10

    /**
     * Width of the trailing window for the live rest level ([LiveRestLevel]). It must be long enough to
     * span the display's battery updates (about every 30 s) and a short climb, and short enough that real
     * consumption inside it stays below one display step: at 1.2 % per km and 25 km/h the battery loses
     * 0.5 % per minute, so 2 minutes keep the lag at about one percent.
     */
    const val LIVE_WINDOW_MS = 120_000L

    /** The display reports 0 before it knows the battery; above 100 is not a percentage. */
    fun plausible(pct: Int) = pct in 1..100

    /** Rest level at the start and the end of one ride, in whole display percent. */
    data class RestLevels(val startPct: Int, val endPct: Int) {
        /** Battery used by the ride; never negative (see [BatteryReadings]). */
        val usedPct: Int get() = startPct - endPct
    }

    /**
     * Rest levels of a ride (see the class comment): start = highest plausible reading of the ride, end =
     * highest plausible reading after the last moving sample (the last reading if the ride ended while
     * moving, e.g. the connection dropped). Null without any plausible reading.
     */
    fun restLevels(s: List<RideSample>): RestLevels? {
        val readings = s.map { it.batteryPct }.filter(::plausible)
        if (readings.isEmpty()) return null
        // the standing phase: the samples after the last moving one (that one was still under load)
        val tail = s.subList(s.indexOfLast { it.speedKmh > 0 } + 1, s.size).map { it.batteryPct }.filter(::plausible)
        val start = readings.max()
        val end = tail.maxOrNull() ?: s.last { plausible(it.batteryPct) }.batteryPct
        return RestLevels(start, minOf(start, end))
    }
}

/**
 * Live estimate of the rest level while riding: the highest reading of the last [windowMs]. A sag on a
 * climb therefore does not pull the range down at once; it counts only if the reading stays down for the
 * whole window, which is what real consumption does. Not thread-safe; one instance per live session.
 */
class LiveRestLevel(private val windowMs: Long = BatteryReadings.LIVE_WINDOW_MS) {
    private val window = ArrayDeque<Pair<Long, Int>>()

    /** Adds the reading at [nowMs] and returns the current rest level; implausible readings are ignored. */
    fun update(nowMs: Long, pct: Int): Int? {
        if (BatteryReadings.plausible(pct)) window.addLast(nowMs to pct)
        while (window.isNotEmpty() && window.first().first < nowMs - windowMs) window.removeFirst()
        return window.maxOfOrNull { it.second }
    }

    /** The live session ended (disconnected, other bike): the next one starts fresh. */
    fun reset() = window.clear()
}
