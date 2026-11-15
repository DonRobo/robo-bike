package dev.donrobo.ebike.ui.components.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {
    @Test fun niceTicks() {
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0), ChartScale.ticks(0.0, 26.0))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), ChartScale.ticks(0.0, 5.0, 5, integer = true))
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), ChartScale.ticks(0.0, 10.6, 3))
        val alt = ChartScale.ticks(262.0, 318.0)
        assertTrue(alt.first() <= 262.0 && alt.last() >= 318.0)
        assertEquals(20.0, ChartScale.reference(31.0)!!, 0.0)
        assertNull(ChartScale.reference(0.0))
    }

    @Test fun downsampleKeepsEnds() {
        val pts = (0..1000).map { ChartPoint(it / 100f, (it % 7).toFloat()) }
        val d = ChartScale.downsample(pts, 100)
        assertTrue(d.size <= 100)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.last(), d.last())
    }
}
