package dev.donrobo.ebike.location

import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting

/** Where a bike was when its connection ended (W3). [fix] is null if no position was known then. */
data class ParkedPosition(val atMs: Long, val fix: LocationFix?) {
    companion object {
        /** "atMs;<LocationFix.encode or empty>". */
        fun encode(p: ParkedPosition) = "${p.atMs};${p.fix?.let(LocationFix::encode).orEmpty()}"
        fun decode(s: String): ParkedPosition? {
            val f = s.split(';')
            if (f.size != 2) return null
            val at = f[0].toLongOrNull() ?: return null
            return ParkedPosition(at, f[1].takeIf { it.isNotEmpty() }?.let(LocationFix::decode))
        }
    }
}

object LocationSettings {
    /**
     * Last position the app saw. Written only by [LocationProvider]. Uses the key of the former
     * `LightSettings.lastPosition` ("lat,lon"), which [LocationFix.decode] still reads (time 0 = unknown).
     */
    val lastFix = Setting.mapped<LocationFix?>("lastPosition", null, Scope.GLOBAL, LocationFix::decode, { it?.let(LocationFix::encode) })

    /** Per bike: position and time when the connection to its display ended. Written only by [LocationProvider]. */
    val parked = Setting.mapped<ParkedPosition?>("location.parked", null, Scope.PER_BIKE, ParkedPosition::decode, { it?.let(ParkedPosition::encode) })
}
