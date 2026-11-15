package dev.donrobo.ebike.bike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInfoTest {
    @Test fun displayInfo() {
        val d = DisplayInfo(mapOf("hardware" to "B02NC_1.01", "software" to "EKD01_CAN_BF_APO_N04", "serial" to "A1B2C3D44321", "controller" to ""))
        assertEquals("EKD01", d.model)
        assertEquals("4321", d.serialEnd)
        assertNull(d.controller)                                                    // empty: row hidden
        val merged = DisplayInfoStore.merge(mapOf("serial" to "X", "controller" to "C1"), mapOf("controller" to "", "software" to "S"))
        assertEquals(mapOf("serial" to "X", "controller" to "", "software" to "S"), merged)
        assertEquals(merged, DisplayInfoStore.decode(DisplayInfoStore.encode(merged)))
        assertTrue(DisplayInfo(mapOf("controller" to " ")).isEmpty)
    }
}
