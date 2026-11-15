package dev.donrobo.ebike.ride

import dev.donrobo.ebike.ride.RideTracker.Action.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class RideLogTest {
    private val t0 = 1_762_803_180_000L  // 2025-11-10T19:33:00Z

    private fun s(dt: Long, speed: Double, bat: Int, odo: Double, pos: Boolean = true) =
        RideSample(t0 + dt, if (pos) 48.2085 else null, if (pos) 16.3721 else null, speed, bat, 3, 1.5, odo)

    @Test fun fileName() = assertEquals("2025-11-10_203300.csv", RideCsv.fileName(t0, ZoneId.of("Europe/Vienna")))

    @Test fun csvRoundTrip() {
        val samples = listOf(s(0, 12.3, 80, 1234.56), s(1000, 0.0, 79, 1234.6, pos = false))
        val text = RideCsv.HEADER + "\n" + samples.joinToString("") { RideCsv.line(it) + "\n" } + "1762803182000,48.1,"
        assertEquals("${t0 + 1000},,,0.0,79,3,1.50,1234.60,", RideCsv.line(samples[1])) // empty altitude column
        assertEquals(samples, RideCsv.parse(text))  // header and the truncated last line are skipped
    }

    @Test fun summary() {
        val f = File("r.csv")
        assertNull(RideCsv.summarize(f, listOf(s(0, 10.0, 80, 100.0))))
        val r = RideCsv.summarize(f, listOf(s(0, 10.0, 80, 100.0), s(600_000, 20.0, 75, 103.5), s(900_000, 0.0, 74, 103.5)))!!
        assertEquals(t0, r.startMs)
        assertEquals(600, r.durationS)  // standing tail excluded
        assertEquals(3.5, r.distanceKm, 1e-9)
        assertEquals(80, r.batteryStartPct)
        assertEquals(74, r.batteryEndPct)
    }

    @Test fun gpx() {
        val g = RideCsv.gpx("ride <1>", listOf(s(0, 12.3, 80, 1.0), s(1000, 5.0, 80, 1.0, pos = false)))
        assertTrue(g.contains("""<gpx version="1.1""""))
        assertTrue(g.contains("<name>ride &lt;1&gt;</name>"))
        assertEquals(1, Regex("<trkpt ").findAll(g).count())
        assertTrue(g.contains("""<trkpt lat="48.208500" lon="16.372100"><time>2025-11-10T19:33:00Z</time>"""))
        assertTrue(g.contains("<eb:speedKmh>12.3</eb:speedKmh><eb:batteryPct>80</eb:batteryPct>"))
    }

    @Test fun tracker() {
        val t = RideTracker()
        assertEquals(SKIP, t.onSample(0, 0.0))          // standing, no ride
        assertFalse(t.isActive(0))
        assertEquals(START, t.onSample(1000, 5.0))
        assertEquals(SKIP, t.onSample(1500, 5.0))       // < 1 s since last write
        assertEquals(APPEND, t.onSample(2000, 5.0))
        assertEquals(APPEND, t.onSample(3000, 0.0))     // standing inside the window still logs
        assertTrue(t.isActive(2000 + 300_000))
        assertFalse(t.isActive(2001 + 300_000))
        assertEquals(SKIP, t.onSample(400_000, 0.0))    // window over, ride closed
        assertFalse(t.isActive(400_000))
        assertEquals(START, t.onSample(401_000, 3.0))   // new ride
        assertEquals(START, t.onSample(800_000, 3.0))   // gap > 5 min without samples -> new ride too
    }
}
