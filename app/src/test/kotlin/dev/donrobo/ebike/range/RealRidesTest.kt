package dev.donrobo.ebike.range

import dev.donrobo.ebike.battery.BatteryReadings
import dev.donrobo.ebike.battery.LiveCharge
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
import kotlin.math.abs

/**
 * The owner's real rides (November 11-22 2026, a backup export), reduced to anonymous fixtures in
 * `src/test/resources/real-rides/` (oldest first): time since the ride's first sample, speed as recorded, battery
 * percent, assist level, odometer since the first sample. No positions, no absolute times. Rides 1-7 are all in
 * assist level 5; rides 1-7 have speed in the old 10x scale (only "moving or not" matters here).
 *
 * The display's percent is voltage-derived: within a ride it went down and up dozens of times and recovered by up
 * to 7 points, and between rides by up to 13 more. Taking each ride's end level at the end of the ride booked
 * about 1.8 %/km (54 km on a full battery); the rest level read at the next ride's start
 * ([dev.donrobo.ebike.battery.BatteryReadings.settleEnds]) shows about 1.0 %/km. These tests pin that.
 */
class RealRidesTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val month = YearMonth.of(2026, 11)
    private val count = 29

    private fun samples(n: Int): List<RideSample> {
        val base = LocalDate.of(2026, 11, 10).atStartOfDay(zone).toInstant().toEpochMilli() + n * 6 * 3_600_000L
        val text = checkNotNull(javaClass.getResource("/real-rides/ride$n.csv")) { "fixture ride$n" }.readText()
        return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split(',')
            RideSample(base + f[0].toLong(), null, null, f[1].toDouble(), f[2].toInt(), f[3].toInt(), f[4].toDouble(), f[4].toDouble())
        }.toList()
    }

    /** Each file's own summary, oldest first (end = reading at the end of the ride). */
    private val raw: List<RideSummary> by lazy { (1..count).map { RideCsv.summarize(File("ride$it.csv"), samples(it))!! } }

    /** Newest first with settled ends, as [dev.donrobo.ebike.ride.RideIndex.load] delivers them. */
    private val settled: List<RideSummary> by lazy { BatteryReadings.settleEnds(raw.reversed()) }

    private fun learn(window: Int = 50) = RangeModel.learn(settled, window, month, zone)

    /** Model distance between two battery levels at [l]'s level-5 rate, before the safety margin. */
    private fun modelKm(l: LearnedRange, fromPct: Int, toPct: Int) = (l.averagePct(fromPct) - l.averagePct(toPct)) / RangeCalc.rateFor(l, 5)!!

    @Test fun eachRideIsRestToRestWithinItsFile() {
        // highest reading of the ride -> highest reading of the final standing phase
        val first = raw.take(7)
        assertEquals(listOf(100, 92, 88, 100, 93, 92, 88), first.map { it.batteryStartPct })
        assertEquals(listOf(88, 86, 75, 92, 91, 88, 73), first.map { it.batteryEndPct })
        // the deepest sag (start - lowest reading: 15, 9, 20, 10, 4, 8, 15 = 81 %) is not booked as used
        assertEquals(60.0, first.sumOf { it.batteryUsedPct }, 1e-9)
        assertEquals(49.4, first.sumOf { it.distanceKm }, 0.05)
    }

    @Test fun endsSettleAtTheNextRidesStart() {
        val s = settled.reversed()
        // charged after rides 3, 12, 17 and 26 (each next ride starts above that ride's start); 29 is the newest
        assertEquals(listOf(3, 12, 17, 26, 29), s.indices.filter { !s[it].endSettled }.map { it + 1 })
        // the battery recovered by 3 points (median) from the end of a ride to the next ride's start
        val recoveries = s.indices.filter { s[it].endSettled }.map { s[it].batteryEndPct - raw[it].batteryEndPct }
        assertEquals(3, BatteryReadings.recoveryPct(recoveries))
        assertEquals(13, recoveries.max())
        // first seven rides: ends at the next start (ride 3: own end 75 + 3); ride 7 used 7 %, not 15
        assertEquals(listOf(92, 88, 78, 93, 92, 88, 81), s.take(7).map { it.batteryEndPct })
        // over all rides of 1 km or more: 165 % used instead of 263
        val counted = s.indices.filter { s[it].distanceKm >= RangeModel.MIN_RIDE_KM }
        assertEquals(263.0, counted.sumOf { raw[it].batteryUsedPct }, 1e-9)
        assertEquals(165.0, counted.sumOf { s[it].batteryUsedPct }, 1e-9)
    }

    @Test fun learnedRateMatchesWhatTheBatteryReallyDid() {
        val l = learn(20)
        assertEquals(20, l.ridesUsed)
        assertEquals(1.33, l.pctPerKm!!, 0.03)
        assertEquals(Confidence.LEARNED, l.level(5)!!.confidence)
        val r = raw
        // rides 18-25: from 100 % to 72 % at rest (ride 26's start) in 35.3 km
        val topKm = (17..24).sumOf { r[it].distanceKm }
        assertEquals(35.3, topKm, 0.1)
        assertEquals(topKm, modelKm(l, r[17].batteryStartPct, r[25].batteryStartPct), 0.1 * topKm)
        // rides 9-11: from 74 % to 35 % at rest (ride 12's start) in 19.7 km, lower in the battery. Only these rides
        // and two others went there, so the band fit keeps those bands nearer the average: about 15 % optimistic
        val midKm = (8..10).sumOf { r[it].distanceKm }
        assertEquals(19.7, midKm, 0.1)
        assertEquals(midKm * 1.15, modelKm(l, r[8].batteryStartPct, r[11].batteryStartPct), 0.05 * midKm)
        // full battery about 80 km; with the end-of-ride readings it was under 40
        assertEquals(80.0, RangeCalc.full(l, 5, 0)!!.fullKm, 4.0)
        val sagged = RangeModel.learn(raw.reversed(), 20, month, zone)
        assertTrue(RangeCalc.full(sagged, 5, 0)!!.fullKm < 40.0)
        // the owner's case: 68 % left, 10 % margin: about 39 km, not 14
        assertEquals(39.0, RangeCalc.estimate(l, RangeReading(68, 5, 5, false), 10)!!.remainingKm, 2.0)
        assertTrue(RangeCalc.estimate(sagged, RangeReading(68, 5, 5, false), 10)!!.remainingKm < 16.0)
    }

    @Test fun lowBatteryRidesAreNotOutliers() {
        // rides 10 and 11 (72 -> 46 and 46 -> 35) used 2.5 %/km, three times the top-of-battery rides: the battery's
        // lower percents go less far, and ride 26 confirms it, so with the whole export in the window all rides count
        val usable = settled.count { RangeModel.use(it) == RideUse.OK }
        assertEquals(27, usable)
        assertEquals(usable, learn(50).ridesUsed)
    }

    @Test fun dischargeCurveOfTheRealRides() {
        val l = learn(20)
        for (b in l.bands.reversed()) {
            println("band ${b.lowPct}-${b.highPct}: factor %.3f, %.1f %% own data, %s, %.2f km/%%".format(b.factor, b.pct, b.confidence, b.factor / l.pctPerKm!!))
        }
        // the top 30 % go further than the average percent, 50-70 % clearly less far
        assertTrue(l.bands.takeLast(3).all { it.factor > 1.2 })
        assertTrue(l.bands.subList(5, 7).all { it.factor < 0.75 })
        // below the lowest ride nothing is known: no band goes further than the average
        assertTrue(l.bands.filter { it.confidence == Confidence.UNKNOWN }.all { it.lowPct < 20 && it.factor in 0.8..1.1 })
    }

    @Test fun learnedButBatteryUnknownIsItsOwnState() {
        // the phone's situation: rides learned, but no battery report since the update
        val l = learn(20)
        val d = RangeEngine.compose(l, 20, null, 10, null, LocationLimit.NO_PERMISSION, HomeSettings(true, null, 10.0))
        assertNull(d.estimate)
        assertNotNull(d.full)
        val full = d.full!!
        assertEquals(l.averagePct(100) / l.pctPerKm!! * 0.9, full.fullKm, 1e-9)
        assertEquals(RangeSummaryMode.BATTERY_UNKNOWN, summaryMode(d))
        // the per-level list is at a full battery then, and agrees with the full range (nearly all km in level 5)
        assertEquals(full.fullKm, d.levels.single { it.level == 5 }.km!!, 0.01 * full.fullKm)
        // with a reading the same rate and curve give the remaining range too
        val live = RangeEngine.compose(l, 20, RangeReading(80, 5, 5, true), 10, null, null, HomeSettings(false, null, 10.0))
        assertEquals(RangeSummaryMode.KNOWN, summaryMode(live))
        assertEquals(l.averagePct(80) / RangeCalc.rateFor(l, 5)!! * 0.9, live.estimate!!.remainingKm, 1e-9)
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
            // never above the ride's start rest level, and ends at its own end reading or within one sag step of it
            val r = raw[n - 1]
            assertTrue(live.all { it <= r.batteryStartPct })
            assertTrue("ride $n ends at ${live.last()} vs ${r.batteryEndPct}", live.last() in r.batteryEndPct - 2..r.batteryEndPct)
        }
    }

    @Test fun liveChargeEndsNearTheRestLevel() {
        // every ride with a settled end: the live value at its last moving moment vs the rest level read at the next
        // start, each ride replayed with the model learned from the other rides
        var errRest = 0; var errCharge = 0; var n = 0
        for (i in 0 until count) {
            val r = settled[count - 1 - i]
            if (!r.endSettled || r.distanceKm < RangeModel.MIN_RIDE_KM) continue
            val l = RangeModel.learn(settled.filter { it !== r }, 20, month, zone)
            val rate = RangeCalc.rateFor(l, 5)!!
            val s = samples(i + 1)
            val rest = LiveRestLevel()
            val charge = LiveCharge()
            val live = s.map { x -> rest.update(x.epochMs, x.batteryPct)!! to charge.update(x.epochMs, x.batteryPct, x.odoKm) { p -> rate / l.factor(p) }!! }
            assertTrue("ride ${i + 1}", live.all { it.second <= r.batteryStartPct })
            val (atStopRest, atStopCharge) = live[s.indexOfLast { it.speedKmh > 0 }]
            println("ride ${i + 1}: %.1f km ${r.batteryStartPct} -> ${r.batteryEndPct}: trailing max $atStopRest, estimate $atStopCharge".format(r.distanceKm))
            n++
            errRest += abs(atStopRest - r.batteryEndPct)
            errCharge += abs(atStopCharge - r.batteryEndPct)
        }
        assertEquals(22, n)
        // the trailing maximum lagged by 3.7 points on average (always low); the estimate is off by under 2
        println("mean error: trailing max %.2f, estimate %.2f".format(errRest.toDouble() / n, errCharge.toDouble() / n))
        assertTrue(errRest.toDouble() / n > 3.5)
        assertTrue(errCharge.toDouble() / n < 2.0)
    }
}
