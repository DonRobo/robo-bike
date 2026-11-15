package dev.donrobo.ebike.ui.components.map

import dev.donrobo.ebike.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapModelTest {
    @Test fun boundsIncludeRing() {
        val b = MapGeometry.bounds(MapContent(ring = MapRing(GeoPoint(48.0, 14.0), 10_000.0)))!!
        assertEquals(48.0 - 10_000 / 111_320.0, b.south, 1e-6)
        assertTrue(b.east - 14.0 > 0.13) // longitude degrees are shorter at 48°
        assertNull(MapGeometry.bounds(MapContent()))
        assertTrue(GeoBounds.of(listOf(GeoPoint(1.0, 2.0)))!!.isPoint)
    }

    @Test fun circleRadius() {
        val c = GeoPoint(48.2016, 16.3663)
        val ring = MapGeometry.circle(c, 5_000.0)
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertEquals(5_000.0, c.distanceM(it), 1.0) }
    }

    @Test fun heatCountsDistinctTracks() {
        fun line(fromLon: Double, toLon: Double, lat: Double = 48.2016) = (0..100).map { GeoPoint(lat, fromLon + (toLon - fromLon) * it / 100) }
        val shared = line(16.3663, 16.3763)
        // ride A: shared part twice (back and forth) -> still counts once; ride B: shared part + its own branch
        val a = shared + shared.reversed()
        val b = shared + line(16.3763, 16.3763, 48.2016).map { it } + (0..50).map { GeoPoint(48.2016 + 0.005 * it / 50, 16.3763) }
        val h = HeatLines.build(listOf(a, b))
        assertEquals(2, h.maxCount)
        val strong = h.lines.filter { it.count == 2 }
        val weak = h.lines.filter { it.count == 1 }
        assertTrue(strong.isNotEmpty() && weak.isNotEmpty())
        assertEquals(1f, strong.first().weight)
        assertEquals(0f, weak.first().weight)
        // the shared stretch (~740 m) is drawn once, not per ride
        val sharedLen = strong.sumOf { l -> l.points.zipWithNext { p, q -> p.distanceM(q) }.sum() }
        assertEquals(740.0, sharedLen, 60.0)
        assertTrue(HeatLines.build(emptyList()).lines.isEmpty())
    }
}
