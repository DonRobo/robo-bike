package dev.donrobo.ebike.ride

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.light.Weather
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/** Outdoor air temperature in °C when a ride started and when it ended; either may be unknown. */
data class RideTemperature(val startC: Double?, val endC: Double?) {
    /** "3 → 5 °C" (start to end), a single value when only one is known or both round the same; null without any. */
    fun measure(fmt: UnitFormatter): Measure? = when {
        startC != null -> fmt.temperature(startC, endC)
        endC != null -> fmt.temperature(endC)
        else -> null
    }
}

/**
 * Outdoor temperature of each ride, for a later range model that accounts for cold (battery use per km varies a
 * lot between rides). Kept in a small file next to the ride CSV (`<ride>.weather` next to `<ride>.csv`), one
 * [Reading] per line, "start,<epochMs>,<°C>" or "end,<epochMs>,<°C>". Rides without the file (older ones, or no
 * position / network at the time) simply have no temperature. Backups take the whole files dir, so the file is
 * included; deleting a ride deletes it too.
 *
 * Recording ([record]) takes the current temperature from Open-Meteo ([Weather], shared with the light
 * automation) once at the start and once at the end of a ride, at the newest position the phone already has; it
 * never asks for a location of its own and never holds up the ride log.
 */
object RideTemperatures {
    const val EXT = "weather"

    /** A position older than this is too far from the ride to stand for its weather. */
    const val MAX_POSITION_AGE_MS = 30 * 60_000L

    /** How long a reading waits for the next low-power fix when no fresh one is known (e.g. right after connect). */
    const val FIX_WAIT_MS = 2 * 60_000L

    enum class Moment { START, END }

    data class Reading(val moment: Moment, val timeMs: Long, val celsius: Double)

    fun file(csv: File) = File(csv.parentFile, "${csv.nameWithoutExtension}.$EXT")

    fun encode(r: Reading): String = String.format(Locale.ROOT, "%s,%d,%.1f", r.moment.name.lowercase(Locale.ROOT), r.timeMs, r.celsius)

    fun decode(line: String): Reading? {
        val f = line.split(',')
        if (f.size != 3) return null
        val moment = Moment.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == f[0] } ?: return null
        val time = f[1].toLongOrNull() ?: return null
        val c = f[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Reading(moment, time, c)
    }

    /** The newest reading of each moment; null without any. */
    fun of(readings: List<Reading>): RideTemperature? {
        if (readings.isEmpty()) return null
        fun last(m: Moment) = readings.filter { it.moment == m }.maxByOrNull { it.timeMs }?.celsius
        return RideTemperature(last(Moment.START), last(Moment.END))
    }

    /** The temperature of the ride [csv]; null if none was recorded (also for every ride from before this file existed). */
    fun read(csv: File): RideTemperature? {
        val f = file(csv)
        if (!f.exists()) return null
        return runCatching { of(f.readLines().mapNotNull(::decode)) }.getOrNull()
    }

    @Synchronized
    fun append(csv: File, r: Reading) {
        // a ride deleted meanwhile gets no orphaned file
        if (!csv.exists()) return
        runCatching { file(csv).appendText(encode(r) + "\n") }
    }

    /**
     * Records the temperature at [moment] of the ride [csv]: the newest known position if it is fresh, else the next
     * fix the location provider takes anyway (up to [FIX_WAIT_MS]); then one request (or a cached answer). Stores
     * nothing without location permission, a fresh position or an answer. Suspends for up to a few minutes, so the
     * caller runs it outside the recording loop.
     */
    suspend fun record(ctx: Context, csv: File, moment: Moment) {
        if (!Locations.permissions(ctx).any) return
        fun fresh(f: LocationFix?) = f != null && f.ageMs(System.currentTimeMillis()) <= MAX_POSITION_AGE_MS
        val fix = Locations.best(ctx)?.takeIf { fresh(it) }
            ?: withTimeoutOrNull(FIX_WAIT_MS) { Locations.fix.first { fresh(it) } }
            ?: return
        val c = try {
            Weather.current(fix.point.lat, fix.point.lon)?.temperatureC
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.log("ride temperature: weather fetch failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } ?: return
        append(csv, Reading(moment, System.currentTimeMillis(), c))
    }
}
