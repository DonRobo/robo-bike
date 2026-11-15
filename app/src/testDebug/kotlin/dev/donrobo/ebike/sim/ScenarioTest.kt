package dev.donrobo.ebike.sim

import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScenarioTest {
    private val ride = Scenario.Replay("ride", RideScript.parse(File("src/debug/assets/sim/ride.csv").readText()))

    @Test fun bundledRideIsAPlausibleRealRide() {
        val p = ride.points
        assertTrue(p.size > 1000)
        assertEquals(0.0, p.first().tS, 0.0)
        assertEquals(1525.0, ride.durationS, 5.0)          // about 25 min
        assertEquals(9.58, p.last().distKm, 0.001)
        assertTrue(p.zipWithNext().all { (a, b) -> b.tS > a.tS && b.distKm >= a.distKm })
        assertTrue(p.all { it.speedKmh in 0.0..40.0 })       // corrected 0.01 km/h scale, not the old 10x
        assertEquals(100, p.first().batteryPct)
        assertEquals(88, p.last().batteryPct)
        assertTrue(p.zipWithNext().any { (a, b) -> b.batteryPct > a.batteryPct }) // sag and recovery kept
        // distance ~ integral of speed (the check that fixed the speed scale)
        val integral = p.zipWithNext().sumOf { (a, b) -> a.speedKmh * (b.tS - a.tS) / 3600 }
        assertEquals(p.last().distKm, integral, 0.3)
    }

    @Test fun interpolationBetweenRows() {
        val r = Scenario.Replay("x", listOf(RidePoint(0.0, 10.0, 90, 2, 0.0), RidePoint(10.0, 20.0, 89, 3, 0.05)))
        val mid = r.at(5.0)
        assertEquals(15.0, mid.speedKmh, 1e-9)
        assertEquals(0.025, mid.distKm, 1e-9)
        assertEquals(90, mid.batteryPct) // steps, not interpolated
        assertEquals(r.points.last(), r.at(99.0))
    }

    @Test fun replayAtTenTimesCoversTheWholeRideInATenth() {
        val d = FakeDisplay()
        d.odoKm = 500.0; d.tripKm = 3.0
        val e = SimEngine(d)
        e.play(ride, speedup = 10.0)
        assertEquals(100, d.batteryPct)
        val reports = mutableListOf<BikeReport>()
        var finishedAt = -1
        for (i in 0 until 400) { // 200 s real time at 2 Hz
            if (e.tick(500) && finishedAt < 0) finishedAt = i
            reports += BikeReport.decode(Frame.parse(d.report1Frame()).data)
        }
        assertEquals(305.0, finishedAt.toDouble(), 2.0) // 1525 s / 10 / 0.5 s
        assertEquals(509.58, d.odoKm, 0.011)
        assertEquals(12.58, d.tripKm, 0.011)
        assertTrue(reports.zipWithNext().all { (a, b) -> b.odoKm >= a.odoKm })
        assertTrue(reports.maxOf { it.speedKmh } > 20)
        assertEquals(0.0, reports.last().speedKmh, 0.0) // stands after the end
        assertEquals(88, reports.last().batteryPct)
        assertTrue(e.finished)
    }

    @Test fun realTimeReplayAdvancesOneScenarioSecondPerSecond() {
        val e = SimEngine(FakeDisplay())
        e.play(ride, speedup = 1.0)
        repeat(60) { e.tick(500) }
        assertEquals(30.0, e.timeS, 1e-9)
        assertFalse(e.finished)
    }

    @Test fun manualValuesWinAndMoveTheOdometer() {
        val d = FakeDisplay()
        val e = SimEngine(d)
        e.play(Scenario.Standstill, 1.0)
        val odo = d.odoKm
        e.tick(1000)
        assertEquals(odo, d.odoKm, 0.0)
        e.speedOverride = 18.0
        e.batteryOverride = 40
        repeat(10) { e.tick(1000) }
        assertEquals(18.0, d.speedKmh, 0.0)
        assertEquals(40, d.batteryPct)
        assertEquals(odo + 0.05, d.odoKm, 1e-9) // 18 km/h for 10 s
        e.play(ride, 1.0)
        assertEquals(40, d.batteryPct) // override still holds over the replay
        e.batteryOverride = null
        e.tick(500)
        assertEquals(100, d.batteryPct)
    }
}
