package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import java.util.Locale

object RangeSettings {
    /** R7, per bike (each bike learns its own range, A2): how many of the newest usable rides count (10/20/50). */
    val window = Setting.int("range.window", 20, Scope.PER_BIKE)

    /** R7, per bike: safety margin in percent taken off every range (0-30). */
    val marginPct = Setting.int("range.marginPct", 10, Scope.PER_BIKE)

    /** R5: the rider's home, for all bikes. Null = not set. Stored as "lat,lon". */
    val home = Setting.mapped<GeoPoint?>("range.home", null, Scope.GLOBAL, ::decodePoint, { it?.let(::encodePoint) })

    /** R5: "Komme ich heim?" on/off (status, Start banner and warning). */
    val homeEnabled = Setting.bool("range.homeEnabled", true)

    /** R5: range that should be left on arrival, in km. */
    val reserveKm = Setting.double("range.reserveKm", 10.0)

    const val MARGIN_MAX = 30
    const val MARGIN_STEP = 5

    fun encodePoint(p: GeoPoint): String = String.format(Locale.ROOT, "%.6f,%.6f", p.lat, p.lon)

    fun decodePoint(s: String): GeoPoint? {
        val f = s.split(',')
        if (f.size != 2) return null
        val lat = f[0].toDoubleOrNull() ?: return null
        val lon = f[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }
}
