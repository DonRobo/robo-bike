package dev.donrobo.ebike.battery

import dev.donrobo.ebike.ride.RideSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
