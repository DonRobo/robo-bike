package dev.donrobo.ebike.ui.rides

import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.LocationPermissions
import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Fahrten list, Fahrtdetail figures and charts. */
class RidesStateTest {
    private val zone = ZoneId.of("Europe/Vienna")

    private fun ride(at: LocalDateTime, km: Double = 10.0, durS: Long = 1800, used: Double = 10.0) = RideSummary(
        File("${at.toLocalDate()}_${at.hour}${at.minute}.csv"), at.atZone(zone).toInstant().toEpochMilli(), durS, km, 90, 90 - used.toInt(),
    )

    @Test fun listGroupsByLocalDayNewestFirstAndCountsThisWeek() {
        // Wednesday 2026-11-11 is "today"; the week starts Monday 2026-11-09
        val today = LocalDate.of(2026, 11, 11)
        val rides = listOf(
            ride(LocalDateTime.of(2026, 11, 11, 7, 41), km = 9.6, used = 12.0),
            ride(LocalDateTime.of(2026, 11, 11, 21, 53), km = 10.6, used = 13.0),
            ride(LocalDateTime.of(2026, 11, 10, 23, 59), km = 1.0, used = 1.0),
            ride(LocalDateTime.of(2026, 11, 8, 12, 0), km = 50.0, used = 40.0), // Sunday: last week
        )
        val s = ridesUiState(rides, RouteGroups.EMPTY, gpsTrack = true, gpsLimit = null, recording = true, today = today, zone = zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 8)), s.days.map { it.date })
        assertEquals(listOf(21, 7), s.days[0].items.map { java.time.Instant.ofEpochMilli(it.ride.startMs).atZone(zone).hour })
        assertEquals(21.2, s.week.distanceKm, 1e-9)
        assertEquals(26.0, s.week.batteryPct, 1e-9)
        // only the newest ride is the one being recorded
        assertEquals(listOf(true, false), s.days[0].items.map { it.recording })
        assertFalse(s.days[1].items.single().recording)
    }

    @Test fun gpsLimitOnlyWhileTrackIsOn() {
        val s = ridesUiState(emptyList(), RouteGroups.EMPTY, gpsTrack = false, gpsLimit = LocationLimit.NO_PERMISSION, recording = false, today = LocalDate.of(2026, 11, 11))
        assertNull(s.gpsLimit)
        assertTrue(s.days.isEmpty())
        assertEquals(0.0, s.week.distanceKm, 0.0)
    }

    @Test fun trackLimitFromPermissions() {
        val all = LocationPermissions(coarse = true, fine = true, background = true)
        assertNull(trackLimit(null, all))
        assertEquals(LocationLimit.NEEDS_BACKGROUND, trackLimit(null, all.copy(background = false)))
        assertEquals(LocationLimit.TRACK_NEEDS_PRECISE, trackLimit(null, all.copy(fine = false)))
        assertEquals(LocationLimit.NO_PERMISSION, trackLimit(null, LocationPermissions.NONE))
        assertEquals(LocationLimit.SYSTEM_LOCATION_OFF, trackLimit(LocationLimit.SYSTEM_LOCATION_OFF, all))
    }

    @Test fun rideFigures() {
        val r = ride(LocalDateTime.of(2026, 11, 11, 21, 53), km = 10.6, durS = 29 * 60, used = 13.0)
        assertEquals(21.93, r.avgSpeedKmh()!!, 0.01)
        assertEquals(1.226, r.pctPerKm()!!, 0.001)
        assertNull(ride(LocalDateTime.of(2026, 11, 11, 8, 0), km = 0.2).pctPerKm())
        assertNull(ride(LocalDateTime.of(2026, 11, 11, 8, 0), durS = 10).avgSpeedKmh())
        assertEquals("1:15", hoursMinutes(75 * 60))
        assertEquals("0:29", hoursMinutes(29 * 60 + 10))
    }

    @Test fun chartsFromSamples() {
        fun s(i: Int, gear: Int, alt: Double?) = RideSample(1000L * i, null, null, 20.0, 90 - i / 10, gear, 0.0, 100 + i * 0.01, alt)
        val samples = (0..300).map { i -> s(i, if (i < 100) 2 else if (i < 200) 3 else 2, null) }
        val c = rideCharts(samples)
        assertEquals(3.0, c.distanceKm, 1e-9)
        assertNull(c.altitude) // old file / no GPS: no altitude chart
        assertEquals(listOf(2f, 3f, 2f, 2f), c.level.map { it.y })
        assertEquals(listOf(0f, 1f, 2f, 3f), c.level.map { Math.round(it.x * 100) / 100f })
        assertTrue(c.speed.size <= 200)
        assertEquals(3, c.maxLevel)
        assertTrue(c.track.isEmpty())
        val withAlt = rideCharts(samples.mapIndexed { i, x -> x.copy(altM = 300.0 + i) })
        assertEquals(300f, withAlt.altitude!!.first().y)
    }
}
