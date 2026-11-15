package dev.donrobo.ebike.errors

import dev.donrobo.ebike.Live
import dev.donrobo.ebike.proto.BikeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveErrorTrackerTest {
    private val t = DriveErrorTracker(quietMs = 60_000)
    private val report = BikeReport(lightOn = false, gear = 1, gears = 5, batteryPct = 50, speedKmh = 10.0, tripKm = 1.0, odoKm = 123.4)

    private fun live(raw: Int, bike: String = "a") = Live(bike, report.copy(errorRaw = raw))
    private fun appeared(events: List<DriveErrorEvent>) = events.filterIsInstance<DriveErrorEvent.Appeared>()
    private fun ended(events: List<DriveErrorEvent>) = events.filterIsInstance<DriveErrorEvent.Ended>()

    @Test fun noErrorNoEvent() {
        assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0), 0))
        assertEquals(emptyList<DriveErrorEvent>(), t.update(null, 1))
        assertNull(t.current)
    }

    @Test fun appearsOnceWhileItStays() {
        val e = appeared(t.update(live(0x21), 1_000)).single()
        assertEquals(CurrentDriveError(0x21, 21, 1_000), e.error)
        assertEquals("a", e.bikeId)
        assertEquals(123.4, e.odoKm, 0.0)
        assertEquals(CurrentDriveError(0x21, 21, 1_000), t.current)
        repeat(10) { assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0x21), 1_500L + it * 500)) }
        assertEquals(1_000L, t.current!!.sinceMs) // first seen, not last seen
    }

    /** The rule the error log had (one entry per occurrence) and now the alert too: a flicker is one episode. */
    @Test fun flickerWithinTheQuietTimeIsOneEpisode() {
        assertEquals(1, appeared(t.update(live(0x21), 1_000)).size)
        assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0), 2_000))
        assertNull(t.current) // the banner goes at once
        assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0x21), 2_500))
        assertEquals(1_000L, t.current!!.sinceMs)
        // a quiet minute ends it; the same code after that is a new episode
        assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0), 3_000))
        assertEquals(listOf<DriveErrorEvent>(DriveErrorEvent.Ended("a")), t.update(live(0), 2_500 + 60_001))
        assertEquals(1, appeared(t.update(live(0x21), 2_500 + 60_500)).size)
    }

    @Test fun persistingCodeCountsOnce() {
        t.update(live(0x21), 0)
        for (s in 1..200) assertEquals(emptyList<DriveErrorEvent>(), t.update(live(0x21), s * 1_000L))
    }

    @Test fun otherCodeEndsTheEpisodeAndStartsANewOne() {
        t.update(live(0x21), 0)
        val events = t.update(live(0x30), 500)
        assertEquals(listOf<DriveErrorEvent>(DriveErrorEvent.Ended("a")), ended(events))
        assertEquals(30, appeared(events).single().error.code)
        // back to the first code: new episode again
        assertEquals(1, appeared(t.update(live(0x21), 1_000)).size)
    }

    @Test fun disconnectEndsTheEpisode() {
        t.update(live(0x21), 0)
        assertEquals(listOf<DriveErrorEvent>(DriveErrorEvent.Ended("a")), t.update(null, 500))
        assertNull(t.current)
        // reconnected with the code still there: a new connection counts it again
        assertEquals(1, appeared(t.update(live(0x21), 1_000)).size)
    }

    /** A2: a link to another bike never continues this bike's episode; events name the bike they belong to. */
    @Test fun episodesBelongToTheirBike() {
        t.update(live(0x21, bike = "a"), 0)
        val events = t.update(live(0x21, bike = "b"), 500)
        assertEquals(listOf<DriveErrorEvent>(DriveErrorEvent.Ended("a")), ended(events))
        assertEquals("b", appeared(events).single().bikeId)
    }

    @Test fun undecodableByteStillReported() {
        val e = appeared(t.update(live(0xAB), 0)).single()
        assertNull(e.error.code)
        assertEquals(false, e.error.known)
        assertTrue(t.current != null)
    }
}
