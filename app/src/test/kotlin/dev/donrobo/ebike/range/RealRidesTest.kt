package dev.donrobo.ebike.range

import dev.donrobo.ebike.battery.LiveRestLevel
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.ride.RideCsv
import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ui.range.RangeSummaryMode
import dev.donrobo.ebike.ui.range.summaryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The owner's first seven real rides (November 2026, all in assist level 5), reduced to anonymous
 * fixtures in `src/test/resources/real-rides/`: time since the ride's first sample, speed as recorded,
 * battery percent, assist level, odometer since the first sample. No positions, no absolute times.
 *
 * The display's percent is voltage-derived: within a ride it went down and up dozens of times and
 * recovered by up to 7 points. Start-to-end over all rides is about 59 % for 49.4 km (1.2 %/km, about 84 km
 * on a full battery). The previous model booked the deepest sag as consumed and threw away four of the
 * seven rides as "charged" (1.62 %/km, 56 km); these tests pin the measurement that replaced it.
 */
class RealRidesTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val month = YearMonth.of(2026, 11)

    private fun samples(n: Int): List<RideSample> {
        val base = LocalDate.of(2026, 11, 10).atStartOfDay(zone).toInstant().toEpochMilli() + n * 6 * 3_600_000L
        val text = checkNotNull(javaClass.getResource("/real-rides/ride$n.csv")) { "fixture ride$n" }.readText()
        return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split(',')
            RideSample(base + f[0].toLong(), null, null, f[1].toDouble(), f[2].toInt(), f[3].toInt(), f[4].toDouble(), f[4].toDouble())
        }.toList()
    }

    /** Oldest first. */
    private val rides: List<RideSummary> by lazy { (1..7).map { RideCsv.summarize(File("ride$it.csv"), samples(it))!! } }

    @Test fun eachRideIsRestToRest() {
        // highest reading of the ride -> highest reading of the final standing phase
        assertEquals(listOf(100, 92, 88, 100, 93, 92, 88), rides.map { it.batteryStartPct })
        assertEquals(listOf(88, 86, 75, 92, 91, 88, 73), rides.map { it.batteryEndPct })
        assertEquals(listOf(12.0, 6.0, 13.0, 8.0, 2.0, 4.0, 15.0), rides.map { it.batteryUsedPct })
        // the deepest sag (start - lowest reading: 15, 9, 20, 10, 4, 8, 15 = 81 %) is not booked as used
        assertEquals(60.0, rides.sumOf { it.batteryUsedPct }, 1e-9)
        assertEquals(49.4, rides.sumOf { it.distanceKm }, 0.05)
    }

    @Test fun everyRideCountsAndTheRateIsAboutOnePointTwo() {
        assertTrue(rides.all { RangeModel.use(it) == RideUse.OK })
        val l = RangeModel.learn(rides.reversed(), 20, month, zone)
        assertEquals(7, l.ridesUsed)
        assertEquals(59.0, l.pct, 1.5)
        assertEquals(1.2, l.pctPerKm!!, 0.03)
        // all in level 5: that level is learned, at exactly the overall rate
        val l5 = l.level(5)!!
        assertEquals(Confidence.LEARNED, l5.confidence)
        assertEquals(l.pctPerKm!!, l5.pctPerKm!!, 1e-9)
        // full battery before the safety margin: the rides only cover 100..73 %, where each band went less far than
        // the one above, so the bands below continue that (damped) instead of assuming the average: about 74 km,
        // not the 84 a linear battery would give
        assertEquals(74.0, RangeCalc.full(l, 5, 0)!!.fullKm, 3.0)
        // one month with 49 km and 60 %: the R6 value is the same rate
        assertEquals(l.pctPerKm!!, l.months.last().pctPerKm!!, 1e-9)
    }

    @Test fun dischargeCurveOfTheRealRides() {
        // all seven rides lie between 100 and 73 %: only the top three bands have data, the rest continues their trend
        val l = RangeModel.learn(rides.reversed(), 20, month, zone)
        for (b in l.bands.reversed()) {
            println("band ${b.lowPct}-${b.highPct}: factor %.3f, %.1f %% own data, %s, %.2f km/%%".format(b.factor, b.pct, b.confidence, b.factor / l.pctPerKm!!))
        }
        // 70-80 % was crossed by two rides only (5 and 7 of its percents): estimated, pulled towards the average
        assertEquals(listOf(Confidence.ESTIMATED, Confidence.LEARNED, Confidence.LEARNED), l.bands.takeLast(3).map { it.confidence })
        val unknown = l.bands.dropLast(3)
        assertTrue(unknown.all { it.confidence == Confidence.UNKNOWN })
        // they go less far than the learned bands above, and level off instead of running down to the bound
        assertTrue(unknown.all { it.factor < l.bands[8].factor && it.factor > 0.75 })
        assertTrue(unknown.zipWithNext().all { (lower, upper) -> lower.factor <= upper.factor + 1e-9 })
        // the data do not show a strongly non-linear battery in 73..100 %: every band within 35 % of the average
        assertTrue(l.bands.takeLast(3).all { it.factor in 0.65..1.35 })
        val linearFull = 100 / l.pctPerKm!!
        val full = RangeCalc.full(l, 5, 0)!!
        println("full: linear %.1f km, curve %.1f km (%.3f vs %.3f %%/km)".format(linearFull, full.fullKm, l.pctPerKm, full.pctPerKm))
        for (pct in listOf(88, 73, 50, 20)) {
            val now = RangeCalc.estimate(l, RangeReading(pct, 5, 5, false), 10)!!.remainingKm
            println("at $pct %%: linear %.1f km, curve %.1f km (10 %% margin)".format(linearFull * pct / 100 * 0.9, now))
        }
        assertEquals(linearFull, full.fullKm, 0.1 * linearFull)
    }

    @Test fun learnedButBatteryUnknownIsItsOwnState() {
        // the phone's situation: rides learned, but no battery report since the update
        val l = RangeModel.learn(rides.reversed(), 20, month, zone)
        val d = RangeEngine.compose(l, 20, null, 10, null, LocationLimit.NO_PERMISSION, HomeSettings(true, null, 10.0))
        assertNull(d.estimate)
        assertNotNull(d.full)
        val full = d.full!!
        assertEquals(l.averagePct(100) / l.pctPerKm!! * 0.9, full.fullKm, 1e-9)
        assertEquals(RangeSummaryMode.BATTERY_UNKNOWN, summaryMode(d))
        // the per-level list is at a full battery then, and agrees with the full range
        assertEquals(full.fullKm, d.levels.single { it.level == 5 }.km!!, 1e-9)
        // with a reading the same rate and curve give the remaining range too
        val live = RangeEngine.compose(l, 20, RangeReading(80, 5, 5, true), 10, null, null, HomeSettings(false, null, 10.0))
        assertEquals(RangeSummaryMode.KNOWN, summaryMode(live))
        assertEquals(l.averagePct(80) / l.pctPerKm!! * 0.9, live.estimate!!.remainingKm, 1e-9)
        // the top 20 % went further than the rest, so 80 % left is less than 80 % of the full range
        assertTrue(live.estimate!!.remainingKm < full.fullKm * 0.8)
        // nothing learned: learning
        val none = RangeEngine.compose(LearnedRange.EMPTY, 20, RangeReading(80, 5, 5, true), 10, null, null, HomeSettings(false, null, 10.0))
        assertEquals(RangeSummaryMode.LEARNING, summaryMode(none))
        assertNull(none.full)
    }

    @Test fun liveRestLevelDoesNotFollowEverySag() {
        for (n in 1..7) {
            val s = samples(n)
            val f = LiveRestLevel()
            val live = s.map { f.update(it.epochMs, it.batteryPct)!! }
            val rawChanges = s.zipWithNext().count { (a, b) -> a.batteryPct != b.batteryPct }
            val liveChanges = live.zipWithNext().count { (a, b) -> a != b }
            val liveRises = live.zipWithNext().count { (a, b) -> b > a }
            assertTrue("ride $n: $liveChanges live vs $rawChanges raw changes", liveChanges * 2 <= rawChanges)
            assertTrue("ride $n: $liveRises rises", liveRises <= 4)
            // never above the ride's start rest level, and ends at the end rest level or within one sag step of it
            val r = rides[n - 1]
            assertTrue(live.all { it <= r.batteryStartPct })
            assertTrue("ride $n ends at ${live.last()} vs ${r.batteryEndPct}", live.last() in r.batteryEndPct - 2..r.batteryEndPct)
        }
    }
}
