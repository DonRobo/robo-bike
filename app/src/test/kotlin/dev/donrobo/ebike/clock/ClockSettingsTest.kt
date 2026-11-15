package dev.donrobo.ebike.clock

import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.proto.MeterConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clock sync is offered only where the display has a date/time (capability gating, Q4). */
class ClockSettingsTest {
    /** A feature block with only byte 3 (lamp = bit 0, date/time = bit 2) set as given. */
    private fun caps(byte3: Int) = DisplayCapabilities(MeterConfig.decode(ByteArray(26).also { it[3] = byte3.toByte() }))
    private val lampOnly = caps(0b001)
    private val clockOnly = caps(0b100)

    @Test fun clockSyncOnlyWithDateTimeOrUnknown() {
        assertTrue(ClockSettings.autoWanted(true, DisplayCapabilities.UNKNOWN))
        assertTrue(ClockSettings.autoWanted(true, clockOnly))
        assertFalse(ClockSettings.autoWanted(true, lampOnly))
        assertFalse(ClockSettings.autoWanted(false, clockOnly))
    }
}
