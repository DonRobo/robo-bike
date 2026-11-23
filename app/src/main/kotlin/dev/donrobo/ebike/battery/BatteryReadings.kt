package dev.donrobo.ebike.battery

import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideSummary

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
 * - The end of a ride is not at rest yet: the standing phase is short (10-80 s on the owner's rides) and the
 *   voltage keeps recovering for much longer. By the next ride the reading was back up by 1-5 points on most of
 *   the owner's rides and by up to 13 (11 within half an hour), so the end-of-ride reading booked about twice the
 *   real consumption. The true end level is the next ride's start level, as long as no charge came in between
 *   ([settleEnds], [charged]).
 */
object BatteryReadings {
    /**
     * A rise of the reading by at least this much over a lower reading means the battery was charged.
     * Recovery from sag reached 7 points on the owner's first rides (from the lowest reading under load back to
     * rest, ride of 2026-11-11 evening); 10 is above that and far below any real top-up at the charger. Between
     * rides the reading later recovered by up to 13 points, so across rides [settleEnds] does not use this bound.
     */
    const val CHARGE_RISE_PCT = 10

    /**
     * Width of the trailing window for the live rest level ([LiveRestLevel]). It must be long enough to
     * span the display's battery updates (about every 30 s) and a short climb, and short enough that real
     * consumption inside it stays below one display step: at 1.2 % per km and 25 km/h the battery loses
     * 0.5 % per minute, so 2 minutes keep the lag at about one percent.
     */
    const val LIVE_WINDOW_MS = 120_000L

    /**
     * The display's percent flickers by one step, so the next ride may start one point above this ride's start
     * without a charge.
     */
    const val FLICKER_PCT = 1

    /** A start at or above this is a full battery (100 % within flicker), where every charge ends ([charged]). */
    const val FULL_PCT = 100 - FLICKER_PCT

    /** From a full start: a rise from the end reading to a full next start by this much is a charge ([charged]). */
    const val TOP_CHARGE_RISE_PCT = 8

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

    /**
     * Replaces each ride's end level by its rest level (see [BatteryReadings]): the next ride's start level, the first
     * reading at rest after it, marked [RideSummary.endSettled], unless the bike was charged in between ([charged]).
     * The settled end is never above the start and never below the ride's own end reading.
     *
     * A ride followed by a charge, and the newest ride, have no reading at rest after them. They get their own end
     * reading plus the median recovery of the settled rides ([recoveryPct]), unsettled. Dropping them instead would
     * lose exactly the rides deepest into the battery, the only data on its low bands.
     *
     * @param rides newest first, as [dev.donrobo.ebike.ride.RideIndex.load] lists them.
     */
    fun settleEnds(rides: List<RideSummary>): List<RideSummary> {
        val rest = rides.mapIndexed { i, r ->
            val nextStart = rides.getOrNull(i - 1)?.batteryStartPct?.takeIf(::plausible)
            if (plausible(r.batteryStartPct) && nextStart != null && !charged(r, nextStart))
                nextStart.coerceIn(r.batteryEndPct, r.batteryStartPct) else null
        }
        val recovery = recoveryPct(rides.indices.mapNotNull { i -> rest[i]?.let { it - rides[i].batteryEndPct } })
        return rides.mapIndexed { i, r ->
            when {
                rest[i] != null -> r.copy(batteryEndPct = rest[i]!!, endSettled = true)
                plausible(r.batteryStartPct) -> r.copy(batteryEndPct = (r.batteryEndPct + recovery).coerceAtMost(r.batteryStartPct))
                else -> r
            }
        }
    }

    /**
     * Whether the bike was charged between [ride] and a next ride starting at [nextStartPct]. The owner only charges
     * fully, so a charge always ends at (about) 100 %: a next start below [FULL_PCT] is never a charge (a start above
     * this ride's start then is flicker or recovery, and the settled end is capped at the start). A full next start
     * is a charge when it lies above this ride's start (the charge cannot rise by itself), or when this ride started
     * full too and the reading rose from its end by [TOP_CHARGE_RISE_PCT] or more: near the top, recovery lifted the
     * reading by at most 5 points on the owner's rides (11 rides starting at 90 % or more), so 100 -> 95 -> 100 is
     * recovery (the ride used less than one display step) and 100 -> 88 -> 100 a charge.
     */
    fun charged(ride: RideSummary, nextStartPct: Int): Boolean =
        nextStartPct >= FULL_PCT &&
            (nextStartPct > ride.batteryStartPct + FLICKER_PCT || nextStartPct - ride.batteryEndPct >= TOP_CHARGE_RISE_PCT)

    /** Typical recovery from the end of a ride to rest: the median of [recoveries] (whole percent), 0 without any. */
    fun recoveryPct(recoveries: List<Int>): Int {
        if (recoveries.isEmpty()) return 0
        val s = recoveries.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
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
