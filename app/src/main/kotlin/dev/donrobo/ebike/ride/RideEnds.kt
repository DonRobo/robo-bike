package dev.donrobo.ebike.ride

import android.content.Context
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import java.io.File
import kotlin.math.abs

/** Where a ride started and ended (R9); [straightKm] is the straight line between them. */
data class RideEnds(val start: GeoPoint, val end: GeoPoint) {
    val straightKm: Double get() = start.distanceM(end) / 1000
}

/**
 * Start and end of a ride without a GPS track (R9, Q3). The phone takes a short location query when the bike
 * is switched on and off (connect / disconnect) and at the end of a ride; those fixes are kept in a small file
 * next to the ride CSV ([RideFixes]), and positioned samples of the CSV count too. Which of them is the start
 * and which the end is decided here, from the times only (unit-tested), so the files hold observations, not
 * conclusions.
 *
 * The bike stands still before a ride starts and after it ends (a ride ends after minutes without movement), so
 * a fix from shortly before the first movement is exactly the start, one from shortly after the last movement
 * exactly the end ([STILL_MS]); a fix taken while already riding is close, but only within [MOVING_MS].
 */
object RideEndpoints {
    /** A fix this long before the start / after the end was taken next to the standing bike. */
    const val STILL_MS = 10 * 60_000L

    /** A fix this long after the start / before the end is still near it (about 800 m at 25 km/h). */
    const val MOVING_MS = 2 * 60_000L

    data class Observation(val point: GeoPoint, val timeMs: Long)

    /** Both ends, or null when either is unknown. [startMs]/[endMs] = first and last movement of the ride. */
    fun pick(obs: List<Observation>, startMs: Long, endMs: Long): RideEnds? {
        val start = at(obs, startMs, stillBefore = true) ?: return null
        val end = at(obs, endMs, stillBefore = false) ?: return null
        return RideEnds(start, end)
    }

    /**
     * The best observation for the moment [momentMs]: one from the standing side ([stillBefore] = before it,
     * else after it) within [STILL_MS] beats one from the riding side within [MOVING_MS]; then the closest in time.
     */
    fun at(obs: List<Observation>, momentMs: Long, stillBefore: Boolean): GeoPoint? = obs.mapNotNull { o ->
        val dt = o.timeMs - momentMs
        val still = if (stillBefore) dt in -STILL_MS..0 else dt in 0..STILL_MS
        val moving = if (stillBefore) dt in 0..MOVING_MS else dt in -MOVING_MS..0
        when {
            still -> Triple(o, 0, abs(dt))
            moving -> Triple(o, 1, abs(dt))
            else -> null
        }
    }.minWithOrNull(compareBy({ it.second }, { it.third }))?.first?.point

    /** Observations of one ride: the fixes from its [RideFixes] file plus the positioned samples. */
    fun observations(samples: List<RideSample>, fixes: List<LocationFix>): List<Observation> =
        fixes.filter { it.timeMs > 0 }.map { Observation(it.point, it.timeMs) } +
            samples.mapNotNull { s -> if (s.lat != null && s.lon != null) Observation(GeoPoint(s.lat, s.lon), s.epochMs) else null }
}

/**
 * The location fixes taken around one ride (`<ride>.fixes` next to `<ride>.csv`, one [LocationFix.encode] per
 * line). Only a few lines per ride: the fix known when it started, the one at its end and the ones at
 * disconnect. [RideIndex] re-summarises a ride when this file changes; deleting a ride deletes it too.
 */
object RideFixes {
    const val EXT = "fixes"

    fun file(csv: File) = File(csv.parentFile, "${csv.nameWithoutExtension}.$EXT")

    fun read(csv: File): List<LocationFix> {
        val f = file(csv)
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines().mapNotNull(LocationFix::decode) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(csv: File, fix: LocationFix) {
        runCatching { file(csv).appendText(LocationFix.encode(fix) + "\n") }
    }

    /**
     * Offers [fix] (taken when [bikeId]'s connection ended) as the end of that bike's newest ride: kept only if
     * the ride was still being written within [RideEndpoints.STILL_MS] before the fix, so an old ride never
     * collects positions from later days. [RideEndpoints] decides whether it is used.
     */
    fun offerToLatest(ctx: Context, bikeId: String, fix: LocationFix): Boolean {
        val csv = RideStore.dir(ctx, bikeId).listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.maxByOrNull { it.name } ?: return false
        if (!belongsToRide(csv.lastModified(), fix.timeMs)) return false
        append(csv, fix)
        RideRepository.notifyChanged()
        return true
    }

    /** Whether a fix at [fixMs] can be about a ride whose file was last written at [csvModifiedMs]. */
    fun belongsToRide(csvModifiedMs: Long, fixMs: Long): Boolean = fixMs - csvModifiedMs in -RideEndpoints.STILL_MS..RideEndpoints.STILL_MS
}
