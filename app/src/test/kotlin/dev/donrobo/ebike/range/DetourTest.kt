package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ride.RideEnds
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ui.range.RangeUiState
import dev.donrobo.ebike.ui.range.areaMap
import dev.donrobo.ebike.ui.range.shownDetour
import dev.donrobo.ebike.ui.components.map.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetourTest {
    private val a = GeoPoint(48.2085, 16.3721)

    /** A ride of [km] whose ends lie [straightKm] apart (due north), or without ends. */
    private fun ride(km: Double, straightKm: Double?, i: Int = 0) = RideSummary(
        File("r$i"), 1_800_000_000_000L - i * 3_600_000L, 1800, km, 80, 70,
        ends = straightKm?.let { RideEnds(a, GeoPoint(a.lat + it / 111.195, a.lon)) },
    )

    @Test fun medianOfUsableRides() {
        val rides = listOf(
            ride(12.0, 10.0, 0), ride(13.0, 10.0, 1), ride(15.0, 10.0, 2),
            ride(5.0, 0.5, 3), // round trip under 1 km straight line: ignored
            ride(8.0, null, 4), // no ends: ignored
            ride(0.0, 3.0, 5), // no distance: ignored
        )
        assertEquals(3, DetourCalc.ratios(rides).size)
        val d = DetourCalc.effective(true, 1.6, rides)
        assertEquals(1.3, d.factor, 1e-3) // median of 1.2, 1.3, 1.5
        assertEquals(3, d.rides)
        assertTrue(d.learned)
    }

    @Test fun evenCountAveragesTheMiddleTwo() {
        assertEquals(1.25, DetourCalc.median(listOf(1.4, 1.1, 1.2, 1.3))!!, 1e-9)
        assertNull(DetourCalc.median(emptyList()))
    }

    @Test fun tooFewRidesGiveTheDefault() {
        val d = DetourCalc.effective(true, 1.6, listOf(ride(12.0, 10.0, 0), ride(20.0, 10.0, 1)))
        assertEquals(DetourCalc.DEFAULT, d.factor, 0.0)
        assertEquals(2, d.rides)
        assertFalse(d.learned)
    }

    @Test fun clampedAndLimitedToTheNewestRides() {
        // odometer/GPS outliers cannot push the factor out of 1.0..2.0
        assertEquals(DetourCalc.MAX, DetourCalc.effective(true, 1.3, (0..4).map { ride(30.0, 10.0, it) }).factor, 0.0)
        assertEquals(DetourCalc.MIN, DetourCalc.effective(true, 1.3, (0..4).map { ride(9.0, 10.0, it) }).factor, 0.0)
        // only the newest MAX_RIDES count: 50 new rides at 1.2 outvote 60 older at 1.8
        val rides = (0 until 50).map { ride(12.0, 10.0, it) } + (50 until 110).map { ride(18.0, 10.0, it) }
        val d = DetourCalc.effective(true, 1.3, rides)
        assertEquals(1.2, d.factor, 1e-3)
        assertEquals(DetourCalc.MAX_RIDES, d.rides)
    }

    @Test fun manualValueWins() {
        val d = DetourCalc.effective(false, 1.45, (0..4).map { ride(12.0, 10.0, it) })
        assertEquals(Detour(1.45, auto = false), d)
        assertFalse(d.learned)
    }

    @Test fun stepper() {
        assertEquals(1.35, DetourCalc.step(1.3, +1), 0.0)
        assertEquals(1.25, DetourCalc.step(1.3, -1), 0.0)
        assertEquals(1.3, DetourCalc.step(1.27, +1), 0.0) // snaps onto the 0.05 grid
        assertEquals(DetourCalc.MIN, DetourCalc.step(1.0, -1), 0.0)
        assertEquals(DetourCalc.MAX, DetourCalc.step(2.0, +1), 0.0)
    }

    @Test fun shownDetourFollowsTheControlsAtOnce() {
        val engine = Detour(1.27, auto = true, rides = 23)
        fun state(auto: Boolean, detail: Detour?) = RangeUiState(
            detail = detail?.let { RangeDetailFixture.with(it) }, window = 20, marginPct = 10, homeEnabled = true, home = null,
            reserveKm = 10.0, detourAuto = auto, detourManual = 1.4,
        )
        assertEquals(engine, shownDetour(state(true, engine)))
        assertEquals(Detour(1.4, auto = false), shownDetour(state(false, engine)))
        // switched back on, the engine has not caught up yet: not the stale manual value
        assertEquals(Detour(DetourCalc.DEFAULT, auto = true), shownDetour(state(true, Detour(1.4, auto = false))))
    }

    @Test fun areaMapDrawsTheEllipseAndAnExampleWay() {
        val home = a
        val bike = BikePosition(GeoPoint(48.2375, 16.3721), 0, BikePosition.Source.PARKED)
        val m = areaMap(bike, home, ReachArea(bike.point, home, 20.0))
        assertEquals(listOf(MarkerKind.PARKED, MarkerKind.HOME), m.markers.map { it.kind })
        assertTrue(m.area!!.points.size > 50)
        assertEquals(listOf(bike.point, home), listOf(m.guide.first(), m.guide.last()))
        // not enough for there and home: markers only
        val none = areaMap(bike, home, ReachArea(bike.point, home, 2.0))
        assertNull(none.area)
        assertTrue(none.guide.isEmpty())
    }
}

/** A [RangeDetail] with nothing learned, for UI mapping tests. */
private object RangeDetailFixture {
    fun with(detour: Detour) = RangeEngine.compose(LearnedRange.EMPTY, 20, null, 10, null, null, HomeSettings(true, null, 10.0), detour)
}
