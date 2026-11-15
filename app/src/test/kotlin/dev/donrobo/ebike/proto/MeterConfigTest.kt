package dev.donrobo.ebike.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real block is not in the logged sessions (the dump tool only tried 0xF1 reg 2), so the vectors are built from the bit
 * table in docs/PROTOCOL.md.
 */
class MeterConfigTest {
    private fun block(vararg set: Pair<Int, Int>) = ByteArray(26).also { b -> set.forEach { (i, v) -> b[i] = v.toByte() } }

    @Test fun decodesEveryFlag() {
        val c = MeterConfig.decode(
            block(
                2 to 0b11_10_01_00, 3 to 0b1000_0111, 4 to 1, 5 to 1, 6 to 0b1000,
                7 to (0x80 or 1), 8 to (0x80 or (5 shl 1) or 1), 9 to (0x80 or (3 shl 1)), 11 to 0x80,
            ),
        )!!
        assertEquals(DisplayFeature.entries.toSet(), c.features)
        assertEquals(1, c.minGear)
        assertEquals(1, c.minBrightness)
        assertEquals(5, c.maxBrightness)
        assertEquals(0, c.minUnlockLevel)
        assertEquals(3, c.maxUnlockLevel)
        assertEquals(listOf(0, 1, 2, 3), c.coefficients)
    }

    @Test fun singleFlags() {
        assertEquals(setOf(DisplayFeature.LAMP_SWITCH), MeterConfig.decode(block(3 to 1))!!.features)
        assertEquals(setOf(DisplayFeature.DATE_TIME), MeterConfig.decode(block(3 to 4))!!.features)
        assertEquals(setOf(DisplayFeature.UNIT), MeterConfig.decode(block(6 to 8))!!.features)
        assertEquals(setOf(DisplayFeature.ERROR_CODE), MeterConfig.decode(block(11 to 0x80))!!.features)
        // neighbouring bits do not leak in
        assertEquals(emptySet<DisplayFeature>(), MeterConfig.decode(block(6 to 0xF7, 11 to 0x7F, 4 to 0xFE))!!.features)
    }

    @Test fun shortBlockIsRejected() {
        assertNull(MeterConfig.decode(ByteArray(25)))
        assertTrue(MeterConfig.decode(ByteArray(32)) != null) // longer answers are accepted like in BikeGo (>= 26)
    }
}
