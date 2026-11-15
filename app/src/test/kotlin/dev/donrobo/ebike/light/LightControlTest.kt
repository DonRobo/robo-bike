package dev.donrobo.ebike.light

import dev.donrobo.ebike.proto.BikeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one hand-switch rule shown by Start, Lenkermodus, widget, tile and notification. */
class LightControlTest {
    private val r = BikeReport(lightOn = true, gear = 1, gears = 5, batteryPct = 50, speedKmh = 0.0, tripKm = 0.0, odoKm = 0.0)

    @Test fun states() {
        assertEquals(LightControl.ON, LightControl.of(r))
        assertEquals(LightControl.OFF, LightControl.of(r.copy(lightOn = false)))
        assertEquals(LightControl.LOCKED, LightControl.of(r.copy(unlocked = false)))
        assertEquals(LightControl.NOT_CONNECTED, LightControl.of(null))
        assertEquals(LightControl.NO_LAMP, LightControl.of(r, lampSwitch = false))
        assertEquals(LightControl.NO_LAMP, LightControl.of(null, lampSwitch = false))
    }

    @Test fun onlyOnAndOffAreSwitchable() {
        assertTrue(LightControl.ON.switchable)
        assertTrue(LightControl.OFF.switchable)
        assertFalse(LightControl.LOCKED.switchable)
        assertFalse(LightControl.NOT_CONNECTED.switchable)
        assertFalse(LightControl.NO_LAMP.switchable)
    }

    @Test fun shownLightOnRule() {
        val r = BikeReport(lightOn = true, gear = 1, gears = 5, batteryPct = 50, speedKmh = 0.0, tripKm = 0.0, odoKm = 0.0)
        assertTrue(LightControl.lampOn(r))
        assertFalse(LightControl.lampOn(r.copy(lightOn = false)))
        assertFalse(LightControl.lampOn(null))
    }
}
