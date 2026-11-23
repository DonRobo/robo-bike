package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ride.LevelStats
import dev.donrobo.ebike.ride.RideCsv
import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

class RangeModelTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val month = YearMonth.of(2026, 11)
    private fun at(date: String) = LocalDate.parse(date).atTime(17, 0).atZone(zone).toInstant().toEpochMilli()

    /** A summary as the index would produce it (used = start - end rest level); [levels] default to all in level 2. */
    private fun ride(
        km: Double, used: Double, start: Int = 80, date: String = "2026-11-10",
        levels: List<LevelStats> = listOf(LevelStats(2, km, 0)),
    ) = RideSummary(File("$date-$km-$used"), at(date), 1800, km, start, start - used.toInt(), levels = levels)

    private fun learn(rides: List<RideSummary>, window: Int = 20) = RangeModel.learn(rides, window, month, zone)

    // --- which rides count -----------------------------------------------------------

    @Test fun unusableRides() {
        assertEquals(RideUse.OK, RangeModel.use(ride(10.0, 12.0)))
        assertEquals(RideUse.TOO_SHORT, RangeModel.use(ride(0.4, 1.0)))
        assertEquals(RideUse.NO_BATTERY, RangeModel.use(ride(10.0, 0.0, start = 0)))
        assertEquals(RideUse.IMPLAUSIBLE, RangeModel.use(ride(2.0, 30.0)))
        // (no "battery rose" rule any more: a ride file never spans a charge, and recovery from sag is already
        // absorbed by the rest levels the summary is built on - see RealRidesTest)
    }

    // --- first rides -----------------------------------------------------------------

    @Test fun nothingLearnedFromNothing() {
        val l = learn(emptyList())
        assertFalse(l.ready)
        assertEquals(0, l.ridesUsed)
        assertTrue(l.levels.isEmpty())
        assertEquals(6, l.months.size)
        assertTrue(l.months.all { it.pctPerKm == null })
    }

    @Test fun firstRidesAreNotEnough() {
        // two short commutes: 6 km, 7 % - below the 8 km / 8 % threshold
        val l = learn(listOf(ride(3.0, 4.0), ride(3.0, 3.0)))
        assertFalse(l.ready)
        assertEquals(2, l.ridesUsed)
        assertEquals(6.0, l.km, 1e-9)
        assertNull(RangeCalc.estimate(l, RangeReading(75, 2, 5, true), 10))
        // a third one crosses it
        val l3 = learn(listOf(ride(4.0, 5.0), ride(3.0, 4.0), ride(3.0, 3.0)))
        assertTrue(l3.ready)
        assertEquals(1.2, l3.pctPerKm!!, 1e-9)
    }

    @Test fun tinyRidesAndRidesWithoutBatteryAreIgnored() {
        val good = List(4) { ride(10.0, 12.0) }
        val noise = listOf(
            ride(0.3, 2.0),            // a few hundred metres with one step: would be 6.7 %/km
            ride(8.0, 0.0, start = 0), // the display never reported a battery level
        )
        val l = learn(noise + good)
        assertEquals(4, l.ridesUsed)
        assertEquals(1.2, l.pctPerKm!!, 1e-9)
    }

    // --- weighting, window, outliers -------------------------------------------------

    @Test fun longerRidesWeighMoreButOneTourDoesNotDominate() {
        // ratio of sums: 2 km with 4 % (quantisation-heavy) barely moves 20 km at 1.0 %/km
        val l = learn(listOf(ride(2.0, 4.0), ride(20.0, 20.0)))
        assertEquals(24.0 / 22.0, l.pctPerKm!!, 1e-9)
        // a 100 km tour at 0.6 %/km counts like 25 km
        val t = learn(listOf(ride(100.0, 60.0), ride(10.0, 12.0), ride(10.0, 12.0), ride(5.0, 6.0)))
        val expected = (0.25 * 60 + 30.0) / (25.0 + 25.0)
        assertEquals(expected, t.pctPerKm!!, 1e-9)
    }

    @Test fun windowTakesNewestUsableRides() {
        val newest = List(10) { ride(10.0, 10.0, date = "2026-11-${10 + it}") }.reversed() // newest first
        val older = List(10) { ride(10.0, 20.0, date = "2026-10-${10 + it}") }.reversed()
        val all = listOf(ride(0.2, 1.0)) + newest + older // a tiny ride first must not use up a slot
        assertEquals(1.0, learn(all, window = 10).pctPerKm!!, 1e-9)
        assertEquals(10, learn(all, window = 10).ridesUsed)
        assertEquals(1.5, learn(all, window = 20).pctPerKm!!, 1e-9)
    }

    @Test fun displayJumpIsDroppedAsOutlier() {
        val normal = List(6) { ride(8.0, 10.0) }
        val jump = ride(5.0, 25.0, start = 60) // 5 %/km: the display re-estimated downwards
        val l = learn(listOf(jump) + normal)
        assertEquals(6, l.ridesUsed)
        assertEquals(1.25, l.pctPerKm!!, 1e-9)
        // with only a few rides nobody can tell what is normal: kept
        assertEquals(3, learn(listOf(jump, ride(8.0, 10.0), ride(8.0, 10.0))).ridesUsed)
    }

    // --- per level -------------------------------------------------------------------

    @Test fun singleLevelIsExactlyTheRatioOfSums() {
        val l = learn(listOf(ride(10.0, 12.0), ride(6.0, 9.0), ride(4.0, 3.0)))
        assertEquals(24.0 / 20.0, l.pctPerKm!!, 1e-9)
        assertEquals(l.pctPerKm!!, l.level(2)!!.pctPerKm!!, 1e-9)
        assertEquals(Confidence.LEARNED, l.level(2)!!.confidence)
    }

    @Test fun ridesInDifferentMixesTellTheLevelsApart() {
        // true rates 0.8 (level 2) and 1.6 (level 4) %/km; rides all in 2, all in 4, and half/half
        fun mixed(km2: Double, km4: Double) =
            ride(km2 + km4, Math.round(0.8 * km2 + 1.6 * km4).toDouble(), levels = listOf(LevelStats(2, km2, 0), LevelStats(4, km4, 0)))
        val rides = List(4) { mixed(10.0, 0.0) } + List(4) { mixed(0.0, 10.0) } + List(4) { mixed(5.0, 5.0) }
        val l = learn(rides)
        assertEquals(0.8, l.level(2)!!.pctPerKm!!, 0.03)
        assertEquals(1.6, l.level(4)!!.pctPerKm!!, 0.03)
        assertEquals(Confidence.LEARNED, l.level(2)!!.confidence)
        assertEquals(Confidence.LEARNED, l.level(4)!!.confidence)
    }

    @Test fun oneDominantLevel() {
        // every ride the same mix: 11 km in level 3, 1 km in level 5 - the data cannot tell the levels apart
        val rides = List(8) { ride(12.0, 14.0, levels = listOf(LevelStats(3, 11.0, 0), LevelStats(5, 1.0, 0))) }
        val l = learn(rides)
        val mix = l.pctPerKm!!
        val l3 = l.level(3)!!
        assertEquals(Confidence.LEARNED, l3.confidence)
        assertEquals(mix, l3.pctPerKm!!, 0.02 * mix)
        // level 5: 8 km ridden, but always within level-3 rides: estimated, held at the mix (no invented difference)
        val l5 = l.level(5)!!
        assertEquals(Confidence.ESTIMATED, l5.confidence)
        assertEquals(mix, l5.pctPerKm!!, 0.05 * mix)
        // levels never ridden are unknown, not invented
        val bars = RangeCalc.levels(l, 75, 5, 10)
        assertEquals(listOf(1, 2, 3, 4, 5), bars.map { it.level })
        assertEquals(listOf(Confidence.UNKNOWN, Confidence.UNKNOWN, Confidence.LEARNED, Confidence.UNKNOWN, Confidence.ESTIMATED), bars.map { it.confidence })
        assertNull(bars[0].km)
    }

    @Test fun levelZeroIsLeftOutOfLevelsButCountsInTheMix() {
        val l = learn(List(3) { ride(10.0, 6.0, levels = listOf(LevelStats(0, 5.0, 0), LevelStats(2, 5.0, 0))) })
        assertEquals(listOf(2), l.levels.map { it.level })
        assertEquals(0.6, l.pctPerKm!!, 1e-9)
    }

    @Test fun levelsUnknownWhileNothingLearned() {
        val l = learn(listOf(ride(3.0, 3.0)))
        assertTrue(l.levels.all { it.confidence == Confidence.UNKNOWN && it.pctPerKm == null })
    }

    // --- quantisation and sag: rides simulated sample by sample -----------------------

    /**
     * Simulates [n] rides with whole-percent, voltage-derived battery readings like the real display's: true
     * consumption [ratePerLevel] per km, the rider switching between levels 2 and 4 about every 1.4 km (at
     * random), random start charge. The reading sags under load by up to [sagPct] points (more in the
     * higher level and on random climbs), recovering with a lag, and is at rest during a 1-minute stop
     * mid-ride and the 1-minute standing tail at the end.
     */
    private fun simulate(n: Int, ratePerLevel: Map<Int, Double>, seed: Int, sagPct: Double = 6.0): List<RideSummary> {
        val rnd = Random(seed)
        var t = at("2026-11-01")
        return List(n) { i ->
            val km = 2.0 + rnd.nextDouble() * 14.0
            var battery = 40.0 + rnd.nextDouble() * 60.0 // true charge, continuous
            var odo = 1000.0 + i * 50
            var level = if (rnd.nextBoolean()) 2 else 4
            var climb = 0.0
            var sag = 0.0 // current voltage sag in display points, follows the load with a lag
            val samples = mutableListOf<RideSample>()
            var ridden = 0.0
            var stopped = false
            fun sample(speed: Double, load: Double) {
                sag += (load - sag) * 0.05
                samples += RideSample(t, null, null, speed, ceil(battery - sag).toInt(), level, ridden, odo)
                t += 1000
            }
            while (ridden <= km) {
                if (rnd.nextDouble() < 0.01) climb = rnd.nextDouble()
                val load = sagPct * (if (level == 4) 0.6 else 0.3) * (0.3 + climb)
                sample(20.0, load)
                val step = 0.0056 // 1 s at 20 km/h
                battery -= ratePerLevel.getValue(level) * step
                odo += step; ridden += step
                if (rnd.nextDouble() < 0.004) level = if (level == 2) 4 else 2
                if (!stopped && ridden > km / 2) { stopped = true; repeat(60) { sample(0.0, 0.0) } } // a red light
            }
            repeat(60) { sample(0.0, 0.0) } // standing at the destination
            t += 3_600_000
            RideCsv.summarize(File("sim$i"), samples)!!
        }.reversed()
    }

    @Test fun sagDoesNotInflateTheRate() {
        // a single level, so the mix is exactly known: deep sag on every climb must not count as consumption
        val l = learn(simulate(20, mapOf(2 to 1.2, 4 to 1.2), seed = 3, sagPct = 10.0))
        assertTrue("rate ${l.pctPerKm}", abs(l.pctPerKm!! - 1.2) / 1.2 < 0.08)
        assertEquals(20, l.ridesUsed) // every ride counts, although the reading went up and down all the time
    }

    @Test fun quantisedRidesConvergeToTheTrueRate() {
        val rates = mapOf(2 to 0.8, 4 to 1.6)
        val l = learn(simulate(20, rates, seed = 1))
        val km2 = l.level(2)!!.km; val km4 = l.level(4)!!.km
        val trueMix = (0.8 * km2 + 1.6 * km4) / (km2 + km4)
        assertTrue("mix ${l.pctPerKm} vs $trueMix", abs(l.pctPerKm!! - trueMix) / trueMix < 0.08)
        // the rides differ in how much of them was in each level, so fitting the ride totals keeps the levels
        // apart instead of both drifting to the mix - without attributing single (sag-driven) percent steps
        assertEquals(Confidence.LEARNED, l.level(2)!!.confidence)
        assertTrue("level 2 ${l.level(2)!!.pctPerKm}", abs(l.level(2)!!.pctPerKm!! - 0.8) < 0.25)
        assertTrue("level 4 ${l.level(4)!!.pctPerKm}", abs(l.level(4)!!.pctPerKm!! - 1.6) < 0.35)
        assertTrue(l.level(4)!!.pctPerKm!! > l.level(2)!!.pctPerKm!!)
    }

    // --- discharge curve (battery not draining linearly) ------------------------------

    /**
     * Rides on a battery where one display percent at level q goes [kmPerPct](q) km, as a rider uses it: charged
     * to 100 %, rides of 4..25 % each, the next one starting 0..3 points higher (the reading recovers at rest),
     * charged again once below [chargeBelow]. Distance = the sum over the percents used (all in level 2).
     */
    private fun curveRides(n: Int, seed: Int, chargeBelow: Int = 10, kmPerPct: (Int) -> Double): List<RideSummary> {
        val rnd = Random(seed)
        var level = 100
        return List(n) { i ->
            if (level < chargeBelow) level = 100
            val start = level
            val end = (start - 4 - rnd.nextInt(22)).coerceAtLeast(0)
            level = (end + rnd.nextInt(4)).coerceAtMost(100)
            val km = (end + 1..start).sumOf(kmPerPct)
            RideSummary(File("c$i"), at("2026-11-01") + i * 3_600_000L, 1800, km, start, end, levels = listOf(LevelStats(2, km, 0)))
        }.reversed()
    }

    @Test fun linearBatteryComesOutAsBefore() {
        // exactly 1.2 %/km in every band, rides all over the battery
        val l = learn(curveRides(40, seed = 7) { 1 / 1.2 }, window = 50)
        assertEquals(1.2, l.pctPerKm!!, 1e-9)
        assertEquals(10, l.bands.size)
        l.bands.forEach { assertEquals("band ${it.lowPct}", 1.0, it.factor, 1e-9) }
        assertEquals(100.0, l.averagePct(100), 1e-9)
        // the same numbers as the linear model: remaining = level / rate, full = 100 / rate, minus the margin
        val e = RangeCalc.estimate(l, RangeReading(37, 2, 5, true), 10)!!
        assertEquals(37 / 1.2 * 0.9, e.remainingKm, 1e-9)
        assertEquals(100 / 1.2 * 0.9, e.fullKm, 1e-9)
        assertEquals(1.2, e.pctPerKm, 1e-9)
        assertEquals(37 / 1.2 * 0.9, RangeCalc.levels(l, 37, 5, 10)[1].km!!, 1e-9)
    }

    @Test fun nonLinearBatteryIsLearnedPerBand() {
        // like the owner's previous bike: the top percents go far, the bottom ones a few hundred metres
        fun k(q: Int) = when {
            q > 90 -> 1.5
            q > 20 -> 1.0
            q > 10 -> 0.6
            else -> 0.3
        }
        val trueFull = (1..100).sumOf(::k) // 90.0 km
        val l = learn(curveRides(50, seed = 11, kmPerPct = ::k), window = 50)
        val avg = 1 / l.pctPerKm!! // the data's average km per percent
        fun km(b: Int) = l.bands[b].factor * avg
        println("learned km per %, bands 0-10 .. 90-100: " + l.bands.indices.map { "%.2f".format(km(it)) })
        // the ridge pulls every band a little towards the average, the extremes the most
        assertEquals(1.5, km(9), 0.15)
        assertEquals(1.0, km(5), 0.05)
        assertEquals(0.6, km(1), 0.1)
        assertEquals(0.3, km(0), 0.2)
        assertTrue(km(9) > km(8) && km(1) < km(2) && km(0) < km(1))
        assertTrue(l.bands.all { it.confidence == Confidence.LEARNED })
        // full and remaining follow the curve; a linear model would say 15 % = 15 % of the full range
        val full = RangeCalc.full(l, 2, 0)!!
        assertEquals(trueFull, full.fullKm, 0.05 * trueFull)
        assertEquals(100 / full.fullKm, full.pctPerKm, 1e-9)
        val at15 = RangeCalc.estimate(l, RangeReading(15, 2, 5, true), 0)!!.remainingKm
        val true15 = (1..15).sumOf(::k) // 6.0 km
        assertEquals(true15, at15, 2.0) // the bottom band, least visited, is pulled most towards the average
        assertTrue("$at15 vs linear ${full.fullKm * 0.15}", at15 < full.fullKm * 0.15 * 0.7)
        // at 100 % remaining is the full range
        assertEquals(full.fullKm, RangeCalc.estimate(l, RangeReading(100, 2, 5, true), 0)!!.remainingKm, 1e-9)
    }

    @Test fun unvisitedBandsContinueAFlatCourse() {
        // only ever ridden between 100 and 60 %, flat below 90 %: the unknown lower bands stay at that level,
        // not at the average (which the far-reaching top band lifts)
        val l = learn(curveRides(20, seed = 5, chargeBelow = 70) { q -> if (q > 90) 1.2 else 0.8 }, window = 50)
        val low = l.bands.filter { it.highPct <= 50 }
        assertTrue(low.all { it.confidence == Confidence.UNKNOWN })
        assertEquals(Confidence.LEARNED, l.bands[9].confidence)
        assertTrue(l.bands[9].factor > l.bands[7].factor)
        low.forEach { assertEquals("band ${it.lowPct}", l.bands[7].factor, it.factor, 0.08) }
    }

    @Test fun unvisitedBandsContinueAFallingTrendAndLevelOff() {
        // ridden only between 100 and 50 %, each band going clearly less far than the one above
        fun k(q: Int) = 0.6 + 0.01 * q // 1.6 km per % at the top, 1.1 at 50 %
        val l = learn(curveRides(40, seed = 9, chargeBelow = 60, kmPerPct = ::k), window = 50)
        val avg = 1 / l.pctPerKm!!
        fun km(b: Int) = l.bands[b].factor * avg
        val lowestLearned = l.bands.indexOfFirst { it.confidence == Confidence.LEARNED }
        val below = (0 until lowestLearned).filter { l.bands[it].confidence == Confidence.UNKNOWN }
        assertTrue(below.isNotEmpty())
        // further down they go less far still, but the damped continuation levels off instead of running away
        val chain = (below + lowestLearned).map(::km)
        assertTrue(chain.toString(), chain.zipWithNext().all { (lower, upper) -> lower <= upper + 1e-9 })
        assertTrue(chain.toString(), chain.first() > km(lowestLearned) - 0.3)
    }

    @Test fun oneOddRideInARareBandMovesItOnlyPartly() {
        // ten ordinary rides at 1.0 km per %, then one through the bottom band claiming 0.05 km per %
        val normal = curveRides(10, seed = 3, chargeBelow = 60) { 1.0 }
        val odd = RideSummary(File("odd"), at("2026-11-20"), 1800, 1.5, 12, 0, levels = listOf(LevelStats(2, 1.5, 0)))
        val l = learn(listOf(odd) + normal, window = 50)
        val b0 = l.bands[0]
        assertEquals(Confidence.ESTIMATED, b0.confidence)
        assertTrue("factor ${b0.factor}", b0.factor >= RangeModel.BAND_FACTOR_MIN && b0.factor < 1.0)
        assertTrue("factor ${b0.factor}", b0.factor > 0.5) // a single short ride does not halve the bottom band
    }

    @Test fun noCurveBeforeAnythingIsLearned() {
        assertTrue(learn(emptyList()).bands.isEmpty())
        assertTrue(learn(listOf(ride(3.0, 3.0))).bands.isEmpty())
        assertEquals(42.0, LearnedRange.EMPTY.averagePct(42), 1e-9)
    }

    // --- R6 --------------------------------------------------------------------------

    @Test fun monthlyRatesNeedEnoughData() {
        val rides = listOf(
            ride(20.0, 20.0, date = "2026-11-03"),            // Nov: 1.0 %/km
            ride(10.0, 12.0, date = "2026-09-03"), ride(10.0, 12.0, date = "2026-09-20"), // Sep: 1.2
            ride(8.0, 9.0, date = "2026-08-03"),              // Aug: too little
            ride(30.0, 30.0, date = "2026-04-03"),            // older than six months
        )
        val m = learn(rides).months
        assertEquals((5 downTo 0).map { month.minusMonths(it.toLong()) }, m.map { it.month })
        assertEquals(listOf(null, null, null, 1.2, null, 1.0), m.map { it.pctPerKm?.let { r -> Math.round(r * 100) / 100.0 } })
        val full = RangeCalc.months(learn(rides), marginPct = 0)
        // the month's rate over the learned discharge curve, as "Voll geladen" (100 km on a linear battery)
        assertEquals(learn(rides).averagePct(100) / 1.0, full.last().fullKm!!, 1e-9)
        // a few rides only: the curve is nearly linear (about 100 km on a linear battery)
        assertEquals(100.0, full.last().fullKm!!, 5.0)
    }
}

class RangeCalcTest {
    private val learned = LearnedRange(
        ridesUsed = 20, km = 300.0, pct = 360.0, pctPerKm = 1.2,
        levels = listOf(
            LevelRate(1, 40.0, 24.0, 0.6, Confidence.LEARNED),
            LevelRate(3, 200.0, 250.0, 1.25, Confidence.LEARNED),
            LevelRate(5, 3.0, 6.0, 1.8, Confidence.ESTIMATED),
        ),
        months = emptyList(),
    )

    @Test fun nowAndFullUseTheCurrentLevelWhenLearned() {
        val e = RangeCalc.estimate(learned, RangeReading(75, 3, 5, true), 0)!!
        assertEquals(60.0, e.remainingKm, 1e-9)
        assertEquals(80.0, e.fullKm, 1e-9)
        assertEquals(1.25, e.pctPerKm, 1e-9)
        // estimated level and level 0 fall back to the mix
        assertEquals(1.2, RangeCalc.estimate(learned, RangeReading(75, 5, 5, true), 0)!!.pctPerKm, 1e-9)
        assertEquals(1.2, RangeCalc.estimate(learned, RangeReading(75, 0, 5, true), 0)!!.pctPerKm, 1e-9)
    }

    @Test fun marginIsTakenOffEverything() {
        val e = RangeCalc.estimate(learned, RangeReading(60, 1, 5, true), 10)!!
        assertEquals(60 / 0.6 * 0.9, e.remainingKm, 1e-9)
        assertEquals(100 / 0.6 * 0.9, e.fullKm, 1e-9)
        val levels = RangeCalc.levels(learned, 60, 5, 10)
        assertEquals(e.remainingKm, levels[0].km!!, 1e-9)
    }

    @Test fun emptyBatteryAndNoReading() {
        assertEquals(0.0, RangeCalc.estimate(learned, RangeReading(0, 3, 5, true), 10)!!.remainingKm, 1e-9)
        assertNull(RangeCalc.estimate(learned, null, 10))
        assertNull(RangeCalc.estimate(LearnedRange.EMPTY, RangeReading(80, 3, 5, true), 10))
    }

    @Test fun fullRangeIsKnownWithoutABatteryReading() {
        // learned, but the bike has not reported a battery level (not connected since the update)
        val f = RangeCalc.full(learned, null, 10)!!
        assertEquals(100 / 1.2 * 0.9, f.fullKm, 1e-9)
        assertEquals(1.2, f.pctPerKm, 1e-9)
        assertNull(RangeCalc.full(LearnedRange.EMPTY, null, 10))
        // and the same as the estimate's once the reading is there
        assertEquals(RangeCalc.estimate(learned, RangeReading(60, 3, 5, true), 10)!!.fullKm, RangeCalc.full(learned, 3, 10)!!.fullKm, 1e-9)
    }

    @Test fun levelsWithoutCountUseTheData() {
        assertEquals(listOf(1, 3, 5), RangeCalc.levels(learned, 100, null, 0).map { it.level })
    }

    @Test fun remainingFollowsTheCurve() {
        // the bottom 20 % go half as far, the top 10 % one and a half times
        val bands = (0 until 10).map { b ->
            BandRate(b * 10, b * 10 + 10, 20.0, when (b) { 0, 1 -> 0.5; 9 -> 1.5; else -> 1.0 }, Confidence.LEARNED)
        }
        val curved = learned.copy(bands = bands)
        assertEquals(20 * 0.5 + 70 + 15.0, curved.averagePct(100), 1e-9) // 95 average percent
        val e = RangeCalc.estimate(curved, RangeReading(30, 3, 5, true), 0)!!
        assertEquals((20 * 0.5 + 10) / 1.25, e.remainingKm, 1e-9)
        assertEquals(95 / 1.25, e.fullKm, 1e-9)
        assertEquals(100 / (95 / 1.25), e.pctPerKm, 1e-9) // average over the full charge
        assertEquals(e.remainingKm, RangeCalc.levels(curved, 30, 5, 0)[2].km!!, 1e-9)
    }

    @Test fun areaIsTheRangeLeftAfterTheReserveAsStraightLine() {
        val bike = GeoPoint(48.2375, 16.3721); val home = GeoPoint(48.2085, 16.3721)
        val a = RangeCalc.area(bike, home, 64.0, 10.0, 1.35)
        assertEquals(54.0 / 1.35, a.sumKm, 1e-9)
        assertTrue(a.reachable)
        // the area ends exactly where the way home stops being OK (HomeLevel), as both use way + reserve
        val way = HomeCalc.distanceKm(bike, home, 1.35)
        assertTrue(RangeCalc.area(bike, home, way + 10.01, 10.0, 1.35).reachable)
        assertFalse(RangeCalc.area(bike, home, way + 9.99, 10.0, 1.35).reachable)
        assertEquals(0.0, RangeCalc.area(bike, home, 5.0, 10.0, 1.3).sumKm, 0.0)
        assertFalse(RangeCalc.area(bike, home, 5.0, 10.0, 1.3).reachable)
    }

    @Test fun noAreaWithoutHomeOrWithTheFeatureOff() {
        val bike = BikePosition(GeoPoint(48.2375, 16.3721), 0, BikePosition.Source.LIVE)
        val home = GeoPoint(48.2085, 16.3721)
        val learned = LearnedRange(10, 100.0, 120.0, 1.2, emptyList(), emptyList())
        fun area(h: HomeSettings) = RangeEngine.compose(learned, 20, RangeReading(80, 0, 5, true), 10, bike, null, h).area
        assertTrue(area(HomeSettings(true, home, 10.0)) != null)
        assertNull(area(HomeSettings(true, null, 10.0)))
        assertNull(area(HomeSettings(false, home, 10.0)))
    }
}
