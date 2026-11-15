package dev.donrobo.ebike.location

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position in degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** Great-circle distance in metres. */
    fun distanceM(o: GeoPoint): Double {
        val dLat = Math.toRadians(o.lat - lat)
        val dLon = Math.toRadians(o.lon - lon)
        val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}

/**
 * One position fix. [timeMs] is the fix's wall-clock time (0 = unknown, e.g. a position stored by an
 * older app version). [altitudeM] is metres above mean sea level where the platform can convert it
 * (API 34+), otherwise the GPS height above the WGS84 ellipsoid; null if the fix has no altitude.
 */
data class LocationFix(
    val point: GeoPoint,
    val timeMs: Long,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
) {
    fun ageMs(nowMs: Long): Long = if (timeMs == 0L) Long.MAX_VALUE else nowMs - timeMs

    companion object {
        /** "lat,lon,timeMs,accuracyM,altitudeM" (empty fields for null). Also reads the old "lat,lon" format. */
        fun encode(f: LocationFix): String = String.format(
            Locale.ROOT, "%.6f,%.6f,%d,%s,%s", f.point.lat, f.point.lon, f.timeMs,
            f.accuracyM?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
            f.altitudeM?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
        )

        fun decode(s: String): LocationFix? {
            val f = s.split(',')
            if (f.size != 2 && f.size != 5) return null
            val lat = f[0].toDoubleOrNull() ?: return null
            val lon = f[1].toDoubleOrNull() ?: return null
            if (f.size == 2) return LocationFix(GeoPoint(lat, lon), 0)
            return LocationFix(GeoPoint(lat, lon), f[2].toLongOrNull() ?: 0, f[3].toFloatOrNull(), f[4].toDoubleOrNull())
        }
    }
}
