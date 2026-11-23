package dev.donrobo.ebike.location

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private val whileInUse = LocationPermissions(coarse = true, fine = true, background = false)
    private val always = whileInUse.copy(background = true)
    private val coarseOnly = LocationPermissions(coarse = true, fine = false, background = false)
    private fun input(
        p: LocationPermissions = whileInUse, track: Boolean = false, connected: Boolean = true,
        on: Boolean = true, visible: Boolean = false, type: Boolean = false,
    ) = LocationInput(track, connected, p, on, visible, type)

    @Test fun noPermissionOrSystemOff() {
        assertEquals(LocationLimit.NO_PERMISSION, LocationPolicy.decide(input(p = LocationPermissions.NONE)).limit)
        val off = LocationPolicy.decide(input(on = false))
        assertEquals(LocationMode.OFF, off.mode)
        assertEquals(LocationLimit.SYSTEM_LOCATION_OFF, off.limit)
    }

    @Test fun backgroundStartWithoutAlwaysGetsNothing() {
        // bike switched on, phone in the pocket: service started in the background, only "while in use"
        val s = LocationPolicy.decide(input(track = true))
        assertEquals(LocationMode.OFF, s.mode)
        assertEquals(LocationLimit.NEEDS_BACKGROUND, s.limit)
        assertFalse(s.usable)
        // same situation with "allow all the time"
        assertEquals(LocationMode.CONTINUOUS, LocationPolicy.decide(input(p = always, track = true)).mode)
        // or the service was (re)started while the app was visible -> location type -> works in the pocket too
        assertEquals(LocationMode.CONTINUOUS, LocationPolicy.decide(input(track = true, type = true)).mode)
        assertEquals(LocationMode.OCCASIONAL, LocationPolicy.decide(input(visible = true)).mode)
    }

    @Test fun gpsOnlyWithTrack() {
        assertEquals(LocationMode.OCCASIONAL, LocationPolicy.decide(input(p = always)).mode)
        assertEquals(LocationMode.CONTINUOUS, LocationPolicy.decide(input(p = always, track = true)).mode)
        assertEquals(LocationMode.OFF, LocationPolicy.decide(input(p = always, track = true, connected = false)).mode)
        val coarse = LocationPolicy.decide(input(p = LocationPermissions(coarse = true, fine = false, background = true), track = true))
        assertEquals(LocationMode.OCCASIONAL, coarse.mode)
        assertEquals(LocationLimit.TRACK_NEEDS_PRECISE, coarse.limit)
    }

    @Test fun occasionalAndPersistTiming() {
        val f = LocationFix(GeoPoint(48.2016, 16.3663), 1_000_000)
        assertTrue(LocationPolicy.needsOccasionalFix(null, 0))
        assertFalse(LocationPolicy.needsOccasionalFix(f, 1_000_000 + 60_000))
        assertTrue(LocationPolicy.needsOccasionalFix(f, 1_000_000 + LocationPolicy.OCCASIONAL_INTERVAL_MS))
        assertFalse(LocationPolicy.shouldPersist(f, f.copy(timeMs = f.timeMs + 10_000)))
        assertTrue(LocationPolicy.shouldPersist(f, f.copy(timeMs = f.timeMs + 60_000)))
        assertTrue(LocationPolicy.shouldPersist(f, f.copy(point = GeoPoint(48.2036, 16.3663), timeMs = f.timeMs + 1)))
    }

    // --- occasional fixes: once at connect, periodic only for "Komme ich heim?" ---------------

    private val home = GeoPoint(48.2085, 16.3721)

    @Test fun recordingUsesGpsWhateverTheHomeSettings() {
        // the home settings only gate OCCASIONAL fixes; with the track on, decide() picks CONTINUOUS
        assertEquals(LocationMode.CONTINUOUS, LocationPolicy.decide(input(p = always, track = true)).mode)
    }

    @Test fun periodicFixesOnlyWithHomeWarningAndHome() {
        assertFalse(LocationPolicy.periodicFixesWanted(homeEnabled = false, home = home))
        assertFalse(LocationPolicy.periodicFixesWanted(homeEnabled = false, home = null))
        assertFalse(LocationPolicy.periodicFixesWanted(homeEnabled = true, home = null))
        assertTrue(LocationPolicy.periodicFixesWanted(homeEnabled = true, home = home))
    }

    @Test fun connectFixAlwaysLaterOnesOnlyWhenPeriodic() {
        val t0 = 1_000_000L
        val stale = LocationFix(home, t0 - LocationPolicy.OCCASIONAL_INTERVAL_MS)
        // switch-on: one fix whether or not periodic fixes are wanted
        assertTrue(LocationPolicy.occasionalFixDue(null, t0, firstOfConnection = true, periodic = false))
        assertTrue(LocationPolicy.occasionalFixDue(stale, t0, firstOfConnection = true, periodic = false))
        // ... unless a fresh one is already known
        assertFalse(LocationPolicy.occasionalFixDue(LocationFix(home, t0 - 60_000), t0, firstOfConnection = true, periodic = false))
        // later: feature off or no home -> never again during this connection
        assertFalse(LocationPolicy.occasionalFixDue(stale, t0, firstOfConnection = false, periodic = false))
        assertFalse(LocationPolicy.occasionalFixDue(null, t0, firstOfConnection = false, periodic = false))
        // feature on with a home -> every interval
        assertTrue(LocationPolicy.occasionalFixDue(stale, t0, firstOfConnection = false, periodic = true))
        assertFalse(LocationPolicy.occasionalFixDue(LocationFix(home, t0 - 60_000), t0, firstOfConnection = false, periodic = true))
    }

    @Test fun togglingWhileConnectedStartsAndStopsPeriodicFixes() {
        val connectFix = LocationFix(home, 0)
        val later = LocationPolicy.OCCASIONAL_INTERVAL_MS + 60_000
        fun due(enabled: Boolean, h: GeoPoint?) =
            LocationPolicy.occasionalFixDue(connectFix, later, firstOfConnection = false, LocationPolicy.periodicFixesWanted(enabled, h))
        assertFalse(due(enabled = true, h = null))  // connected, no home yet: nothing after the connect fix
        assertTrue(due(enabled = true, h = home))   // home set while connected: periodic fixes start
        assertFalse(due(enabled = false, h = home)) // feature switched off: they stop
        assertTrue(due(enabled = true, h = home))   // and on again
    }

    // --- foreground-service type (Play permission review) ------------------------

    @Test fun locationTypeOnlyWhenItsPrerequisitesHold() {
        // started from the visible app: while-in-use is enough
        assertTrue(LocationPolicy.locationServiceTypeAllowed(whileInUse, systemLocationOn = true, appVisible = true))
        assertTrue(LocationPolicy.locationServiceTypeAllowed(coarseOnly, systemLocationOn = true, appVisible = true))
        // started by the companion presence event (app not visible): only with "allow all the time"
        assertFalse(LocationPolicy.locationServiceTypeAllowed(whileInUse, systemLocationOn = true, appVisible = false))
        assertTrue(LocationPolicy.locationServiceTypeAllowed(always, systemLocationOn = true, appVisible = false))
        // no permission or location switched off: never
        assertFalse(LocationPolicy.locationServiceTypeAllowed(LocationPermissions.NONE, systemLocationOn = true, appVisible = true))
        assertFalse(LocationPolicy.locationServiceTypeAllowed(always, systemLocationOn = false, appVisible = true))
    }

    @Test fun connectedDeviceAloneIsAlwaysTheFallback() {
        val dev = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(listOf(dev or loc, dev), LocationPolicy.serviceTypeAttempts(locationAllowed = true))
        assertEquals(listOf(dev), LocationPolicy.serviceTypeAttempts(locationAllowed = false))
    }
}
