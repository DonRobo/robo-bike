package dev.donrobo.ebike.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class RideDataTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Vienna")
    private val t0 = 1_762_803_180_000L // 2025-11-10T19:33:00Z

    private fun s(dt: Long, speed: Double, bat: Int, gear: Int, odo: Double, alt: Double? = null) =
        RideSample(t0 + dt, 48.2016, 16.3663, speed, bat, gear, 0.0, odo, alt)

    /** A file exactly as the app wrote it before altitude was recorded (8 columns). */
    private val oldFile = """
        epochMs,lat,lon,speedKmh,batteryPct,gear,tripKm,odoKm
        1762803180000,48.208500,16.372100,12.3,80,3,1.50,1234.56
        1762803181000,,,0.0,79,3,1.50,1234.60
    """.trimIndent() + "\n"

    @Test fun oldFilesStayReadable() {
        val p = RideCsv.parse(oldFile)
        assertEquals(2, p.size)
        // 8-column files carry the old decoder's 10x speed (12.3 in the file = 1.23 km/h); everything else is as written
        assertEquals(RideSample(1762803180000, 48.2085, 16.3721, 1.23, 80, 3, 1.5, 1234.56, null), p[0].copy(speedKmh = Math.round(p[0].speedKmh * 100) / 100.0))
        assertNull(p[1].lat)
    }

    @Test fun newFormatRoundTripWithAltitude() {
        val a = s(0, 10.0, 80, 2, 100.0, alt = 312.4)
        val b = s(1000, 10.0, 80, 2, 100.01)
        val text = RideCsv.HEADER + "\n" + RideCsv.line(a) + "\n" + RideCsv.line(b) + "\n"
        assertTrue(RideCsv.line(b).endsWith(",")) // no altitude = empty last column
        assertEquals(listOf(a.copy(lat = 48.2016, lon = 16.3663, tripKm = 0.0), b), RideCsv.parse(text))
    }

    @Test fun halfWrittenLastLineIsSkipped() {
        val full = RideCsv.line(s(0, 10.0, 80, 2, 1234.56))
        // cut inside the odometer: 8 fields, which would be a valid old-format line without the header rule
        val cut = full.substring(0, full.lastIndexOf(',') - 3)
        assertEquals(1, RideCsv.parse(RideCsv.HEADER + "\n" + full + "\n" + cut).size)
    }

    @Test fun levelBreakdownAndRestLevels() {
        // level 2 for 3 km, level 4 for 1 km; the reading sags to 74 on a climb and is back to 77 at rest
        val samples = listOf(
            s(0, 20.0, 80, 2, 100.0),
            s(180_000, 20.0, 76, 2, 101.0), // sag
            s(360_000, 20.0, 78, 2, 102.0), // load drops: recovers
            s(540_000, 20.0, 74, 4, 103.0), // interval counts for level 2 (first sample's level); sag
            s(720_000, 0.0, 76, 4, 104.0),  // stopped: recovering
            s(750_000, 0.0, 77, 4, 104.0),  // at rest
        )
        val l = RideStats.levelBreakdown(samples)
        assertEquals(listOf(2, 4), l.map { it.level })
        assertEquals(3.0, l[0].distanceKm, 1e-9)
        assertEquals(1.0, l[1].distanceKm, 1e-9)
        val sum = RideCsv.summarize(File("x.csv"), samples)!!
        // rest level before and after, not the deepest sag (80 - 74 = 6) and not the momentary end values
        assertEquals(80, sum.batteryStartPct)
        assertEquals(77, sum.batteryEndPct)
        assertEquals(3.0, sum.batteryUsedPct, 1e-9)
    }

    @Test fun movingTimeAndClimb() {
        val samples = listOf(
            s(0, 10.0, 80, 1, 0.0, 300.0), s(5_000, 0.0, 80, 1, 0.01, 302.0), // 5 s moving, +2 m is noise
            s(65_000, 10.0, 80, 1, 0.01, 310.0),                               // 60 s standing (not moving time)
            s(70_000, 10.0, 80, 1, 0.02, 306.0), s(75_000, 0.0, 80, 1, 0.03, 315.0),
        )
        val r = RideCsv.summarize(File("x.csv"), samples)!!
        assertEquals(15, r.movingS)                 // 5 + 5 + 5
        assertEquals(19.0, r.climbM!!, 1e-9)       // 300 -> 310 (+10), dip to 306 (> 3 m: new reference), -> 315 (+9)
    }

    @Test fun aggregateMonthWeekYear() {
        fun at(date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun ride(date: String, hour: Int, km: Double, pct: Int) =
            RideSummary(File("$date-$hour"), at(date, hour), 1800, km, 80, 80 - pct)
        val rides = listOf(
            ride("2026-11-02", 8, 10.0, 10), ride("2026-11-02", 17, 9.0, 9), // Monday
            ride("2026-11-08", 23, 20.0, 20),                                 // Sunday late evening
            ride("2026-10-31", 12, 5.0, 5),                                   // other month, same week (Sat)
        )
        val m = RideStats.aggregate(rides, StatsPeriod.MONTH, LocalDate.parse("2026-11-14"), zone)
        assertEquals(3, m.total.rides)
        assertEquals(39.0, m.total.distanceKm, 1e-9)
        assertEquals(0.39, m.total.charges, 1e-9)
        assertEquals(30, m.parts.size)
        assertEquals(19.0, m.parts[1].distanceKm, 1e-9) // Nov 2
        assertEquals(20.0, m.parts[7].distanceKm, 1e-9) // Nov 8
        val w = RideStats.aggregate(rides, StatsPeriod.WEEK, LocalDate.parse("2026-11-01"), zone) // Sunday -> week from Oct 26
        assertEquals(LocalDate.parse("2026-10-26"), w.total.start)
        assertEquals(7, w.parts.size)
        assertEquals(1, w.total.rides)
        val y = RideStats.aggregate(rides, StatsPeriod.YEAR, LocalDate.parse("2026-05-01"), zone)
        assertEquals(12, y.parts.size)
        assertEquals(5.0, y.parts[9].distanceKm, 1e-9)
        assertEquals(39.0, y.parts[10].distanceKm, 1e-9)
    }

    @Test fun indexIsValidatedAgainstFiles() {
        val dir = tmp.newFolder("rides")
        val a = File(dir, "2025-11-10_203300.csv").apply { writeText(oldFile) }
        val idx = RideIndex(dir)
        val first = idx.load()
        assertEquals(1, first.size)
        assertTrue(File(dir, RideIndex.FILE).exists())
        // a fresh index instance reads the cache and returns identical summaries
        assertEquals(first, RideIndex(dir).load())
        // growing file: the stale entry is recomputed, not served
        a.appendText("1762803190000,,,5.0,70,3,1.60,1240.00\n") // same (old) format as the file
        a.setLastModified(a.lastModified() + 2000)
        val grown = RideIndex(dir).load().single()
        assertEquals(70, grown.batteryEndPct)
        // new file appears, deleted file disappears
        File(dir, "2025-11-11_080000.csv").writeText(oldFile.replace("1762803", "1762848"))
        assertEquals(2, idx.load().size)
        a.delete()
        assertEquals(listOf("2025-11-11_080000"), idx.load().map { it.id })
        // a corrupt or old-version cache is simply rebuilt
        File(dir, RideIndex.FILE).writeText("ride-index 0\ngarbage\n")
        assertEquals(1, RideIndex(dir).load().size)
    }
}
