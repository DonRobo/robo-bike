package dev.donrobo.ebike

import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownTest {
    private val r = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 23.4, tripKm = 10.6, odoKm = 1284.37)

    @Test fun codecRoundTripDropsSpeed() {
        val v = LastKnown(1_762_803_180_000L, r)
        assertEquals(LastKnown(v.atMs, r.copy(speedKmh = 0.0)), LastKnown.decode(LastKnown.encode(v)))
        assertNull(LastKnown.decode("garbage"))
        assertNull(LastKnown.decode("1;2;3"))
    }

    @Test fun writerThrottlesAndFlushesLastReport() {
        val settings = Settings(MemoryStore())
        val w = LastKnownWriter(settings, "b1", maxAgeMs = 30_000)
        w.record(r, 1_000)
        assertEquals(1_000L, settings.get(LastKnown.setting, "b1")!!.atMs)       // first report is written
        w.record(r.copy(tripKm = 10.7), 2_000)
        assertEquals(10.6, settings.get(LastKnown.setting, "b1")!!.report.tripKm, 0.0) // trip only: throttled
        w.record(r.copy(tripKm = 10.8, batteryPct = 74), 3_000)
        assertEquals(74, settings.get(LastKnown.setting, "b1")!!.report.batteryPct)   // battery change: written
        w.record(r.copy(tripKm = 10.9, batteryPct = 74), 4_000)
        w.flush()
        val stored = settings.get(LastKnown.setting, "b1")!!
        assertEquals(4_000L, stored.atMs)
        assertEquals(10.9, stored.report.tripKm, 0.0)
        assertNull(settings.get(LastKnown.setting, "other"))                          // per bike
    }

    @Test fun maxAgeForcesWrite() {
        val prev = LastKnown(0, r)
        assertEquals(false, LastKnownWriter.needsWrite(prev, LastKnown(29_999, r), 30_000))
        assertEquals(true, LastKnownWriter.needsWrite(prev, LastKnown(30_000, r), 30_000))
    }

    @Test fun lastKnownKeepsTheDriveError() {
        val r = BikeReport(lightOn = false, gear = 1, gears = 5, batteryPct = 40, speedKmh = 0.0, tripKm = 1.0, odoKm = 2.0, errorRaw = 0x21)
        assertEquals(0x21, LastKnown.decode(LastKnown.encode(LastKnown(1, r)))!!.report.errorRaw)
        assertEquals(0, LastKnown.decode("1;0;1;5;40;1.00;2.00")!!.report.errorRaw)   // stored before errorRaw was kept
    }
}
