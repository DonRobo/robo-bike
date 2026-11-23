package dev.donrobo.ebike.location

import android.content.pm.ServiceInfo

/** Location permissions as granted right now. */
data class LocationPermissions(val coarse: Boolean, val fine: Boolean, val background: Boolean) {
    val any: Boolean get() = coarse || fine

    companion object {
        val NONE = LocationPermissions(coarse = false, fine = false, background = false)
    }
}

/** How the app currently obtains positions. */
enum class LocationMode {
    /** No location requests. */
    OFF,
    /**
     * A low-power fix when the bike connects; further ones every [LocationPolicy.OCCASIONAL_INTERVAL_MS] only while
     * "Komme ich heim?" needs them ([LocationPolicy.periodicFixesWanted], [LocationPolicy.occasionalFixDue]).
     */
    OCCASIONAL,
    /** GPS every second, for the ride track (only while the track setting is on, Q3). */
    CONTINUOUS,
}

/** Why location works less than wanted; the UI explains these. Null = nothing missing. */
enum class LocationLimit {
    /** Neither coarse nor fine location granted. */
    NO_PERMISSION,
    /** The system-wide location switch is off. */
    SYSTEM_LOCATION_OFF,
    /**
     * Only "while in use" is granted and the service was started in the background (bike switched on,
     * phone in the pocket): Android grants no location then. Fix: "allow all the time", or open the app.
     */
    NEEDS_BACKGROUND,
    /** The track is on but only approximate location is granted; positions are occasional and coarse. */
    TRACK_NEEDS_PRECISE,
}

/** Inputs of [LocationPolicy.decide]. */
data class LocationInput(
    val trackWanted: Boolean,
    val connected: Boolean,
    val permissions: LocationPermissions,
    val systemLocationOn: Boolean,
    /** An activity of the app is visible (while-in-use permissions apply). */
    val appVisible: Boolean,
    /** The foreground service runs with the location type (started while the app was visible). */
    val serviceHasLocationType: Boolean,
)

/** What the provider does and what the UI shows. */
data class LocationStatus(
    val mode: LocationMode,
    val limit: LocationLimit?,
    val permissions: LocationPermissions,
    val systemLocationOn: Boolean,
    /** Location can be obtained right now (also for one-shot fixes outside a connection). */
    val usable: Boolean,
) {
    companion object {
        val UNKNOWN = LocationStatus(LocationMode.OFF, null, LocationPermissions.NONE, systemLocationOn = true, usable = false)
    }
}

/** Pure location decisions (unit-tested). */
object LocationPolicy {
    const val OCCASIONAL_INTERVAL_MS = 10 * 60_000L
    const val GPS_INTERVAL_MS = 1_000L

    /**
     * Whether BikeService may start with the `location` foreground-service type right now. Its prerequisites on API
     * 34+ (else startForeground throws a SecurityException): location switched on, coarse or fine granted, and —
     * because location is a while-in-use permission — either an activity of the app visible or "allow all the time".
     * The companion exemptions (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND) only allow *starting*
     * the service from the background, not while-in-use access. On API 31–33 the system accepts the type anyway but
     * gives no location, so the same rule keeps [decide] honest there.
     */
    fun locationServiceTypeAllowed(p: LocationPermissions, systemLocationOn: Boolean, appVisible: Boolean) =
        p.any && systemLocationOn && (appVisible || p.background)

    /**
     * The foreground-service types BikeService tries, in order: with location only when [locationAllowed], and always
     * connectedDevice alone as the fallback (needs only BLUETOOTH_CONNECT), so a missing location grant never blocks
     * the connection.
     */
    fun serviceTypeAttempts(locationAllowed: Boolean): List<Int> {
        val dev = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return if (locationAllowed) listOf(dev or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, dev) else listOf(dev)
    }

    fun decide(i: LocationInput): LocationStatus {
        val p = i.permissions
        fun status(mode: LocationMode, limit: LocationLimit?, usable: Boolean) =
            LocationStatus(mode, limit, p, i.systemLocationOn, usable)
        if (!p.any) return status(LocationMode.OFF, LocationLimit.NO_PERMISSION, false)
        if (!i.systemLocationOn) return status(LocationMode.OFF, LocationLimit.SYSTEM_LOCATION_OFF, false)
        val usable = p.background || i.appVisible || i.serviceHasLocationType
        if (!usable) return status(LocationMode.OFF, LocationLimit.NEEDS_BACKGROUND, false)
        val trackLimit = if (i.trackWanted && !p.fine) LocationLimit.TRACK_NEEDS_PRECISE else null
        if (!i.connected) return status(LocationMode.OFF, trackLimit, true)
        if (i.trackWanted && p.fine) return status(LocationMode.CONTINUOUS, null, true)
        return status(LocationMode.OCCASIONAL, trackLimit, true)
    }

    /** OCCASIONAL: request a new fix when the newest one is older than the interval (or there is none). */
    fun needsOccasionalFix(last: LocationFix?, nowMs: Long): Boolean =
        last == null || last.ageMs(nowMs) >= OCCASIONAL_INTERVAL_MS

    /**
     * Whether OCCASIONAL keeps taking a fix every [OCCASIONAL_INTERVAL_MS] while connected: only for the in-ride
     * "Komme ich heim?" warning, which needs a recent position ([dev.donrobo.ebike.range.HomeCalc.MAX_FIX_AGE_MS]),
     * i.e. when it is enabled and a home is set. Otherwise the fix at connect is the only one (the track, when on,
     * uses CONTINUOUS instead).
     */
    fun periodicFixesWanted(homeEnabled: Boolean, home: GeoPoint?): Boolean = homeEnabled && home != null

    /**
     * OCCASIONAL: whether to request a fix now. [firstOfConnection] = the first check since the bike connected (the
     * switch-on fix), later checks only while [periodic] ([periodicFixesWanted]); either way only when the newest fix
     * is stale ([needsOccasionalFix]).
     */
    fun occasionalFixDue(last: LocationFix?, nowMs: Long, firstOfConnection: Boolean, periodic: Boolean): Boolean =
        (firstOfConnection || periodic) && needsOccasionalFix(last, nowMs)

    /** At disconnect a fresh fix is worth requesting when the newest one is older than this. */
    const val PARKED_FRESH_MS = 60_000L

    /** The persisted last position is rewritten at most this often, or when it moved more than [PERSIST_MOVE_M]. */
    const val PERSIST_INTERVAL_MS = 60_000L
    const val PERSIST_MOVE_M = 100.0

    fun shouldPersist(stored: LocationFix?, next: LocationFix): Boolean =
        stored == null || next.timeMs - stored.timeMs >= PERSIST_INTERVAL_MS || stored.point.distanceM(next.point) > PERSIST_MOVE_M
}
