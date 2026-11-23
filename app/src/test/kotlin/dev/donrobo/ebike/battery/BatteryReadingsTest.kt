package dev.donrobo.ebike.battery

import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatteryReadingsTest {
    private fun s(tS: Long, speed: Double, pct: Int) = RideSample(tS * 1000, null, null, speed, pct, 3, 0.0, 0.0)

    @Test fun restLevelsIgnoreSagAndRecovery() {
        val r = BatteryReadings.restLevels(listOf(
            s(0, 10.0, 80), s(60, 20.0, 74), s(120, 20.0, 78), s(180, 20.0, 71), // sag on climbs
            s(240, 0.0, 74), s(270, 0.0, 76), s(300, 0.0, 75),                  // standing: recovering, flicker
        ))!!
        assertEquals(80, r.startPct)
        assertEquals(76, r.endPct)
        assertEquals(4, r.usedPct)
    }

    @Test fun startIsTheHighestReadingOfTheRide() {
        // the first sample under load already: a later, higher reading shows the charge was at least that
        val r = BatteryReadings.restLevels(listOf(s(0, 20.0, 87), s(2, 20.0, 88), s(60, 0.0, 85)))!!
        assertEquals(88, r.startPct)
        assertEquals(85, r.endPct)
    }

    @Test fun rideEndingWhileMovingUsesItsLastReading() {
        val r = BatteryReadings.restLevels(listOf(s(0, 20.0, 60), s(60, 20.0, 58), s(120, 20.0, 55)))!!
        assertEquals(55, r.endPct)
    }

    @Test fun implausibleReadingsAreIgnored() {
        assertNull(BatteryReadings.restLevels(listOf(s(0, 20.0, 0), s(1, 0.0, 0))))
        val r = BatteryReadings.restLevels(listOf(s(0, 20.0, 0), s(1, 20.0, 70), s(2, 0.0, 0), s(3, 0.0, 68)))!!
        assertEquals(70, r.startPct)
        assertEquals(68, r.endPct)
    }

    @Test fun liveRestLevelIsTheTrailingMaximum() {
        val f = LiveRestLevel(windowMs = 120_000)
        assertEquals(80, f.update(0, 80))
        assertEquals(80, f.update(30_000, 75))  // sag on a climb: ignored for now
        assertEquals(80, f.update(110_000, 76))
        assertEquals(76, f.update(140_000, 76)) // stayed down for the whole window: real
        assertEquals(76, f.update(150_000, 0))  // implausible reading ignored
        f.reset()
        assertEquals(60, f.update(160_000, 60))
    }

    private fun ride(i: Int, start: Int, end: Int) = RideSummary(File("r$i"), i * 3_600_000L, 1800, 5.0, start, end)

    /** [rides] oldest first, as written; the result oldest first too. */
    private fun settle(vararg rides: RideSummary) = BatteryReadings.settleEnds(rides.reversed()).reversed()

    @Test fun endsSettleAtTheNextRidesStart() {
        // 80 -> 70 (sagged), rested to 77; 77 -> 74, rested to 76; 76 -> 68, then charged to 100; 100 -> 92 (newest)
        val s = settle(ride(0, 80, 70), ride(1, 77, 74), ride(2, 76, 68), ride(3, 100, 92))
        assertEquals(listOf(true, true, false, false), s.map { it.endSettled })
        // recoveries 7 and 2: the rides without a reading at rest after them get the median, 4 (whole percent)
        assertEquals(listOf(77, 76, 72, 96), s.map { it.batteryEndPct })
        assertEquals(listOf(3.0, 1.0, 4.0, 4.0), s.map { it.batteryUsedPct })
    }

    @Test fun onlyAFullNextStartIsACharge() {
        // the owner charges fully only: 80 -> 70, next start 85 is no charge (recovery, capped at the start)
        val s = settle(ride(0, 80, 70), ride(1, 85, 80))
        assertTrue(s[0].endSettled)
        assertEquals(80, s[0].batteryEndPct)
        // a full next start above the ride's start is
        assertFalse(settle(ride(0, 80, 70), ride(1, 100, 92))[0].endSettled)
        assertFalse(settle(ride(0, 98, 90), ride(1, 100, 92))[0].endSettled)
        // one step above a full start is flicker
        assertTrue(settle(ride(0, 99, 95), ride(1, 100, 92))[0].endSettled)
    }

    @Test fun fullToFullIsAChargeOnlyAfterALargeDrop() {
        // 100 -> 95, next 100: recovery, the ride used less than one display step
        val small = settle(ride(0, 100, 95), ride(1, 100, 92))
        assertTrue(small[0].endSettled)
        assertEquals(0.0, small[0].batteryUsedPct, 1e-9)
        // 100 -> 88, next 100: more than recovery does near the top, so charged; end estimated (no recovery data: 88)
        val big = settle(ride(0, 100, 88), ride(1, 100, 92))
        assertFalse(big[0].endSettled)
        assertEquals(88, big[0].batteryEndPct)
    }

    @Test fun settledEndIsNeverBelowTheRidesOwnEnd() {
        // the next ride starts lower than this one ended (days of standing): this ride did not use that
        val s = settle(ride(0, 80, 72), ride(1, 70, 60))
        assertTrue(s[0].endSettled)
        assertEquals(72, s[0].batteryEndPct)
    }

    @Test fun estimatedEndIsNeverAboveTheStart() {
        // recovery 10, then a ride of 2 % followed by a charge: estimated end capped at its start
        val s = settle(ride(0, 80, 60), ride(1, 70, 68), ride(2, 100, 95))
        assertEquals(70, s[1].batteryEndPct)
        assertFalse(s[1].endSettled)
    }

    @Test fun withoutSettledRidesNothingIsAdded() {
        val s = settle(ride(0, 80, 72))
        assertEquals(72, s[0].batteryEndPct)
        assertFalse(s[0].endSettled)
        // no battery reading: left as it is, and settles nothing before it
        val t = settle(ride(0, 80, 72), ride(1, 0, 0))
        assertFalse(t[0].endSettled)
        assertEquals(0, t[1].batteryEndPct)
    }
}
