package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.ParkedPosition
import dev.donrobo.ebike.ui.range.healthPoints
import dev.donrobo.ebike.ui.range.healthY
import dev.donrobo.ebike.ui.range.levelBars
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class HomeTest {
    private val home = GeoPoint(48.2085, 16.3721)
    // ~3.23 km north of home (0.029° latitude)
    private val here = GeoPoint(48.2375, 16.3721)
    private val now = 1_800_000_000_000L

    @Test fun distanceIsStraightLineTimesDetour() {
        val straight = here.distanceM(home) / 1000
        assertEquals(3.22, straight, 0.02)
        assertEquals(straight * 1.3, HomeCalc.distanceKm(here, home, 1.3), 1e-9)
    }

    @Test fun levels() {
        assertEquals(HomeLevel.OK, HomeCalc.level(60.0, 4.2, 10.0))
        assertEquals(HomeLevel.TIGHT, HomeCalc.level(12.0, 4.2, 10.0))
        assertEquals(HomeLevel.SHORT, HomeCalc.level(4.0, 4.2, 10.0))
        assertEquals(HomeLevel.OK, HomeCalc.level(14.2, 4.2, 10.0)) // exactly way + reserve is enough
    }

    @Test fun statusCases() {
        val pos = BikePosition(here, now, BikePosition.Source.LIVE)
        val est = RangeEstimate(64.0, 86.0, 1.2)
        assertEquals(HomeStatus.Off, HomeCalc.status(false, home, pos, null, est, 10.0, 1.3))
        assertEquals(HomeStatus.NoHome, HomeCalc.status(true, null, pos, null, est, 10.0, 1.3))
        assertEquals(HomeStatus.NoPosition(LocationLimit.NEEDS_BACKGROUND), HomeCalc.status(true, home, null, LocationLimit.NEEDS_BACKGROUND, est, 10.0, 1.3))
        assertTrue(HomeCalc.status(true, home, pos, null, null, 10.0, 1.3) is HomeStatus.NoRange)
        val k = HomeCalc.status(true, home, pos, null, est, 10.0, 1.3) as HomeStatus.Known
        assertEquals(HomeLevel.OK, k.level)
        assertEquals(64.0 - k.distanceKm, k.leftoverKm, 1e-9)
        assertEquals(HomeReach(k.distanceKm, true), HomeCalc.reach(k))
        assertNull(HomeCalc.reach(HomeStatus.NoPosition(null)))
        // at home (within AT_HOME_M) there is nothing to say about the way home
        val atHome = HomeCalc.status(true, home, BikePosition(home, 0L, BikePosition.Source.LIVE), null, est, 10.0, 1.3) as HomeStatus.Known
        assertTrue(atHome.atHome)
        assertNull(HomeCalc.reach(atHome))
        assertEquals(false, HomeCalc.reach(HomeCalc.status(true, home, pos, null, RangeEstimate(8.0, 86.0, 1.2), 10.0, 1.3))!!.reachable)
    }

    @Test fun positionWhileConnectedMustBeFresh() {
        val fresh = LocationFix(here, now - 5 * 60_000)
        val stale = LocationFix(here, now - 45 * 60_000)
        val parked = ParkedPosition(now - 3_600_000, LocationFix(home, now - 3_700_000))
        assertEquals(BikePosition.Source.LIVE, HomeCalc.position(true, fresh, parked, now)!!.source)
        // stale while riding: no answer rather than an old one shown as current (also not the parked spot)
        assertNull(HomeCalc.position(true, stale, parked, now))
        assertNull(HomeCalc.position(true, LocationFix(here, 0), null, now)) // unknown time
        // a fix that arrived after the caller's last clock tick is the freshest there is
        assertEquals(BikePosition.Source.LIVE, HomeCalc.position(true, LocationFix(here, now + 30_000), parked, now)!!.source)
    }

    @Test fun positionWhileParkedIsWhereTheBikeStands() {
        val parked = ParkedPosition(now - 86_400_000, LocationFix(home, now - 86_500_000))
        val p = HomeCalc.position(false, LocationFix(here, now - 60_000), parked, now)!!
        assertEquals(BikePosition.Source.PARKED, p.source)
        assertEquals(home, p.point)
        // nothing parked: a fresh phone fix
        assertEquals(here, HomeCalc.position(false, LocationFix(here, now - 60_000), null, now)!!.point)
        assertNull(HomeCalc.position(false, null, ParkedPosition(now, null), now))
    }

    @Test fun warningCondition() {
        fun known(remaining: Double) = HomeStatus.Known(4.2, remaining, 10.0, HomeCalc.level(remaining, 4.2, 10.0))
        assertEquals(HomeWarning.Condition.CLEAR, HomeWarning.condition(known(60.0)))
        assertEquals(HomeWarning.Condition.HOLDS, HomeWarning.condition(known(12.0)))
        assertEquals(HomeWarning.Condition.HOLDS, HomeWarning.condition(known(3.0)))
        // after charging the range is back: the episode ends, so the next tight moment alerts again
        assertEquals(HomeWarning.Condition.CLEAR, HomeWarning.condition(known(80.0)))
        assertEquals(HomeWarning.Condition.CLEAR, HomeWarning.condition(HomeStatus.Off))
        assertEquals(HomeWarning.Condition.CLEAR, HomeWarning.condition(HomeStatus.NoHome))
        // no current position: neither confirm nor withdraw
        assertEquals(HomeWarning.Condition.UNKNOWN, HomeWarning.condition(HomeStatus.NoPosition(LocationLimit.NEEDS_BACKGROUND)))
        assertEquals(HomeWarning.Condition.UNKNOWN, HomeWarning.condition(HomeStatus.NoRange(4.2)))
    }

    @Test fun reserveStepsInTheRidersUnit() {
        assertEquals(11.0, HomeCalc.stepReserve(10.0, +1, UnitSystem.METRIC), 1e-9)
        assertEquals(0.0, HomeCalc.stepReserve(0.0, -1, UnitSystem.METRIC), 1e-9)
        assertEquals(50.0, HomeCalc.stepReserve(50.0, +1, UnitSystem.METRIC), 1e-9)
        // 10 km is shown as "6 mi": + gives exactly 7 mi, - exactly 5 mi
        assertEquals(7 * UnitFormatter.KM_PER_MI, HomeCalc.stepReserve(10.0, +1, UnitSystem.IMPERIAL), 1e-9)
        assertEquals(5 * UnitFormatter.KM_PER_MI, HomeCalc.stepReserve(10.0, -1, UnitSystem.IMPERIAL), 1e-9)
    }

    @Test fun homeSettingCodec() {
        assertEquals(home, RangeSettings.decodePoint(RangeSettings.encodePoint(home)))
        assertNull(RangeSettings.decodePoint("91.0,10.0"))
        assertNull(RangeSettings.decodePoint("garbage"))
    }
}

class RangeComposeTest {
    private val learned = LearnedRange(
        20, 300.0, 360.0, 1.2,
        listOf(LevelRate(3, 200.0, 250.0, 1.25, Confidence.LEARNED)),
        (5 downTo 0).map { MonthRate(YearMonth.of(2026, 11).minusMonths(it.toLong()), if (it % 2 == 0) 1.1 else null) },
    )

    @Test fun composeLinksEstimateLevelsAndHome() {
        val pos = BikePosition(GeoPoint(48.2375, 16.3721), 0, BikePosition.Source.LIVE)
        val d = RangeEngine.compose(
            learned, 20, RangeReading(75, 3, 5, live = true), 10, pos, null,
            HomeSettings(true, GeoPoint(48.2085, 16.3721), 10.0),
        )
        assertEquals(75 / 1.25 * 0.9, d.estimate!!.remainingKm, 1e-9)
        assertEquals(5, d.levels.size)
        assertEquals(d.estimate!!.remainingKm, d.levels[2].km!!, 1e-9)
        val k = d.home as HomeStatus.Known
        assertEquals(d.estimate!!.remainingKm, k.remainingKm, 1e-9)
        assertEquals(100 / 1.1 * 0.9, d.months.last().fullKm!!, 1e-9)
    }

    @Test fun composeUsesTheDetourForTheWayHomeAndTheArea() {
        val home = GeoPoint(48.2085, 16.3721)
        val pos = BikePosition(GeoPoint(48.2375, 16.3721), 0, BikePosition.Source.LIVE)
        val detour = Detour(1.5, auto = true, rides = 12)
        val d = RangeEngine.compose(learned, 20, RangeReading(75, 3, 5, live = true), 10, pos, null, HomeSettings(true, home, 10.0), detour)
        val straight = pos.point.distanceM(home) / 1000
        assertEquals(straight * 1.5, (d.home as HomeStatus.Known).distanceKm, 1e-9)
        assertEquals(detour, d.detour)
        val area = d.area!!
        assertEquals(home, area.home)
        assertEquals((d.estimate!!.remainingKm - 10.0) / 1.5, area.sumKm, 1e-9)
        assertTrue(area.reachable)
        // "Komme ich heim?" switched off: the area is a circle around the bike (there and back)
        val off = RangeEngine.compose(learned, 20, RangeReading(75, 3, 5, live = true), 10, pos, null, HomeSettings(false, home, 10.0), detour)
        assertNull(off.area!!.home)
        // no remaining range known: no area
        assertNull(RangeEngine.compose(learned, 20, null, 10, pos, null, HomeSettings(true, home, 10.0)).area)
    }

    @Test fun composeWithoutReadingShowsFullBatteryLevels() {
        val d = RangeEngine.compose(learned, 20, null, 0, null, LocationLimit.NO_PERMISSION, HomeSettings(true, null, 10.0))
        assertNull(d.estimate)
        assertEquals(100 / 1.25, d.levels.single().km!!, 1e-9)
        assertEquals(HomeStatus.NoHome, d.home)
    }
}

class RangeUiTest {
    @Test fun barsAreRelativeToTheLongestKnownRange() {
        val bars = levelBars(
            listOf(LevelRange(1, 118.0, Confidence.LEARNED), LevelRange(2, null, Confidence.UNKNOWN), LevelRange(3, 59.0, Confidence.ESTIMATED)),
            current = 3,
        )
        assertEquals(listOf(1f, 0f, 0.5f), bars.map { it.fraction })
        assertEquals(listOf(false, false, true), bars.map { it.current })
    }

    @Test fun healthScaleDoesNotExaggerate() {
        val pts = healthPoints(listOf(MonthRange(YearMonth.of(2026, 6), 91.0), MonthRange(YearMonth.of(2026, 7), null), MonthRange(YearMonth.of(2026, 11), 86.0)))
        assertEquals(listOf(0, 2), pts.map { it.index })
        assertEquals(0f, healthY(91.0, pts), 1e-6f)
        // a 5 km drop out of 91 is drawn as 5 / (0.2 * 91) of the height, not the full height
        assertEquals((5 / 18.2).toFloat(), healthY(86.0, pts), 1e-4f)
    }
}
