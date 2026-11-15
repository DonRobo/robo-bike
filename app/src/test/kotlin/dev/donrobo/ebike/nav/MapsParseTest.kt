package dev.donrobo.ebike.nav

import dev.donrobo.ebike.proto.Icon
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MapsParseTest {
    @Test fun distances() {
        assertEquals(200, MapsText.distanceM("200 m"))
        assertEquals(1200, MapsText.distanceM("1,2 km"))
        assertEquals(3400, MapsText.distanceM("12 min · 3,4 km · 14:05"))
        assertEquals(152, MapsText.distanceM("500 ft"))
        assertEquals(483, MapsText.distanceM("0.3 mi – Main St"))
        assertNull(MapsText.distanceM("Hauptstraße"))
        assertNull(MapsText.distanceM("12 min"))
    }

    /** Draws a thick polyline plus an arrow head on a 48x48 grid. */
    private fun arrow(vararg pts: Pair<Int, Int>): BooleanArray {
        val w = 48
        val m = BooleanArray(w * w)
        fun dot(x: Double, y: Double, r: Double) {
            for (yy in 0 until w) for (xx in 0 until w) if (hypot(xx - x, yy - y) <= r) m[yy * w + xx] = true
        }
        fun line(a: Pair<Double, Double>, b: Pair<Double, Double>, r: Double) {
            val steps = (hypot(b.first - a.first, b.second - a.second) * 2).toInt() + 1
            for (i in 0..steps) dot(a.first + (b.first - a.first) * i / steps, a.second + (b.second - a.second) * i / steps, r)
        }
        val p = pts.map { it.first.toDouble() to it.second.toDouble() }
        for (i in 0 until p.size - 1) line(p[i], p[i + 1], 2.5)
        // barbs: back from the tip at +-140 degrees to the last segment
        val (tx, ty) = p.last(); val (qx, qy) = p[p.size - 2]
        val l = hypot(tx - qx, ty - qy); val ux = (tx - qx) / l; val uy = (ty - qy) / l
        for (s in listOf(-1, 1)) {
            val bx = -ux * 0.75 - s * uy * 0.65; val by = -uy * 0.75 + s * ux * 0.65
            line(tx to ty, (tx + bx * 8) to (ty + by * 8), 1.5)
        }
        return m
    }

    private fun cls(vararg pts: Pair<Int, Int>) = ArrowClassifier.classify(arrow(*pts), 48, 48)

    @Test fun arrows() {
        assertEquals(Icon.STRAIGHT, cls(24 to 44, 24 to 6))
        assertEquals(Icon.LEFT, cls(30 to 44, 30 to 16, 8 to 16))
        assertEquals(Icon.RIGHT, cls(18 to 44, 18 to 16, 40 to 16))
        assertEquals(Icon.SLIGHT_LEFT, cls(30 to 44, 30 to 24, 14 to 8))
        assertEquals(Icon.SLIGHT_RIGHT, cls(18 to 44, 18 to 24, 34 to 8))
        assertEquals(Icon.SHARP_LEFT, cls(32 to 44, 32 to 12, 12 to 30))
        assertEquals(Icon.SHARP_RIGHT, cls(16 to 44, 16 to 12, 36 to 30))
        assertEquals(Icon.UTURN, cls(34 to 44, 34 to 12, 14 to 12, 14 to 34))
        assertEquals(Icon.UNKNOWN, ArrowClassifier.classify(BooleanArray(48 * 48), 48, 48))
        // dashed stem, as in Maps' first "head northwest" instruction
        val dashed = arrow(24 to 30, 24 to 6)
        for (y in listOf(36, 37, 38, 43, 44, 45)) for (x in 22..26) dashed[y * 48 + x] = true
        assertEquals(Icon.STRAIGHT, ArrowClassifier.classify(dashed, 48, 48))
    }
}
