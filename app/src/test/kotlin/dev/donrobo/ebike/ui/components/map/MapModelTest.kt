package dev.donrobo.ebike.ui.components.map

import dev.donrobo.ebike.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapModelTest {
    @Test fun boundsIncludeArea() {
        val c = GeoPoint(48.0, 14.0)
        val b = MapGeometry.bounds(MapContent(area = MapArea(MapGeometry.ellipse(c, c, 20_000.0)!!)))!!
        assertEquals(48.0 - 10_000 / 111_195.0, b.south, 1e-4)
        assertTrue(b.east - 14.0 > 0.13) // longitude degrees are shorter at 48°
        assertNull(MapGeometry.bounds(MapContent()))
        assertTrue(GeoBounds.of(listOf(GeoPoint(1.0, 2.0)))!!.isPoint)
    }

    @Test fun ellipseWithCoincidentFociIsACircle() {
        val c = GeoPoint(48.2016, 16.3663)
        val ring = MapGeometry.ellipse(c, c, 10_000.0)!!
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertEquals(5_000.0, c.distanceM(it), 1.0) }
        // the example way: out to the edge and back
        val g = MapGeometry.ellipseGuide(c, c, 10_000.0)!!
        assertEquals(listOf(c, c), listOf(g.first(), g.last()))
        assertEquals(5_000.0, c.distanceM(g[1]), 1.0)
    }

    @Test fun ellipsePointsHaveTheGivenDistanceSum() {
        val bike = GeoPoint(48.2375, 16.3721)
        val home = GeoPoint(48.1900, 16.4500) // ~7.7 km south-east
        val sum = 30_000.0
        val e = MapGeometry.ellipse(bike, home, sum)!!
        assertEquals(e.first(), e.last())
        e.forEach { p -> assertEquals(sum, bike.distanceM(p) + p.distanceM(home), sum * 0.002) }
        val g = MapGeometry.ellipseGuide(bike, home, sum)!!
        assertEquals(sum, bike.distanceM(g[1]) + g[1].distanceM(home), sum * 0.002)
        // no area when the sum does not even cover the way between the foci
        assertNull(MapGeometry.ellipse(bike, home, bike.distanceM(home) * 0.99))
        assertNull(MapGeometry.ellipseGuide(bike, home, 0.0))
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
