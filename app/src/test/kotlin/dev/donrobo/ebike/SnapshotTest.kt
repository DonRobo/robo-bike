package dev.donrobo.ebike

import dev.donrobo.ebike.proto.BikeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live state bound to its bike. */
class SnapshotTest {
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 20.0, tripKm = 1.0, odoKm = 100.0)
    private val stored = LastKnown(1_000L, report.copy(batteryPct = 60, speedKmh = 0.0))

    @Test fun liveWhenItIsThisBike() {
        val s = Snapshot.of("a", Live("a", report), stored)!!
        assertTrue(s.live)
        assertEquals(75, s.report.batteryPct)
        assertEquals(report, s.liveReport)
    }

    /** A2: the active bike (b) is not the session bike (a): b shows its own last known state, never a's live values. */
    @Test fun activeBikeIsNotTheSessionBike() {
        val s = Snapshot.of("b", Live("a", report), stored)!!
        assertFalse(s.live)
        assertEquals(1_000L, s.asOfMs)
        assertEquals(60, s.report.batteryPct)
        assertNull(s.liveReport)
        assertNull(Snapshot.of("b", Live("a", report), null))
    }

    @Test fun notConnected() {
        assertEquals(Snapshot(stored.report, 1_000L), Snapshot.of("a", null, stored))
        assertNull(Snapshot.of("a", null, null))
        assertNull(Snapshot.of(null, Live("a", report), null)) // no bike shown, nothing to show
    }
}
