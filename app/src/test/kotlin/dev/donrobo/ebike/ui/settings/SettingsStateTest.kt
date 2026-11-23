package dev.donrobo.ebike.ui.settings

import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightSettings
import dev.donrobo.ebike.nav.NavSettings
import dev.donrobo.ebike.notify.NotifySettings
import dev.donrobo.ebike.proto.MeterConfig
import dev.donrobo.ebike.range.RangeSettings
import dev.donrobo.ebike.ride.RideSettings
import dev.donrobo.ebike.settings.AppLanguage
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Einstellungen state, including capability gating (Q4). */
class SettingsStateTest {
    /** A feature block with only byte 3 (lamp = bit 0, date/time = bit 2) set as given. */
    private fun caps(byte3: Int) = DisplayCapabilities(MeterConfig.decode(ByteArray(26).also { it[3] = byte3.toByte() }))
    private val lampOnly = caps(0b001)
    private val clockOnly = caps(0b100)

    @Test fun settingsStateReadsTheStoredSettings() {
        val s = Settings(MemoryStore())
        s.set(LightSettings.mode, "b1", LightMode.ALWAYS_ON)
        s.set(LightSettings.rain, "b1", false)
        s.set(RangeSettings.window, "b1", 50)
        s.set(RangeSettings.marginPct, "b1", 15)
        s[RangeSettings.homeEnabled] = false
        s[NavSettings.forward] = false
        s[RideSettings.gpsTrack] = true
        s[NotifySettings.charge] = false
        s[ConnectionSettings.autoConnect] = false
        val st = settingsUiState(s, listOf(Bike("b1", "Stadtrad", null), Bike("b2", "MTB", null)), "b1", DisplayCapabilities.UNKNOWN, AppLanguage.SYSTEM)
        assertEquals(LightSummary(LightMode.ALWAYS_ON, rain = false), st.light)
        assertEquals(RangeSummary(50, 15, home = false), st.range)
        assertFalse(st.navForward)
        assertTrue(st.gpsTrack)
        assertEquals(Reminders(homeRange = true, charge = false, maintenance = true), st.reminders)
        assertEquals(listOf("Stadtrad", "MTB"), st.bikeNames)
        assertFalse(st.autoConnect)
        assertEquals(true, st.clockAuto) // unknown capabilities count as supported
    }

    @Test fun settingsHideWhatTheDisplayCannotDo() {
        val s = Settings(MemoryStore())
        val noClock = settingsUiState(s, emptyList(), null, lampOnly, AppLanguage.SYSTEM)
        assertNotNull(noClock.light)
        assertNull(noClock.clockAuto)
        val noLamp = settingsUiState(s, emptyList(), null, clockOnly, AppLanguage.SYSTEM)
        assertNull(noLamp.light)
        assertNotNull(noLamp.clockAuto)
        // No bike: per-bike defaults.
        assertEquals(RangeSummary(RangeSettings.window.default, RangeSettings.marginPct.default, true), noLamp.range)
    }
}
