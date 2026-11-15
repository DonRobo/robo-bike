package dev.donrobo.ebike.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightReason
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.light.RainLevel
import dev.donrobo.ebike.light.RainNow
import dev.donrobo.ebike.light.SunDay
import dev.donrobo.ebike.ui.light.CurveUi
import dev.donrobo.ebike.ui.light.LightActions
import dev.donrobo.ebike.ui.light.LightContent
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.ui.light.lightUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LightScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val actions = object : LightActions {
        override fun back() {}
        override fun setMode(mode: LightMode) {}
        override fun setRain(on: Boolean) {}
        override fun setRainLevel(level: RainLevel) {}
        override fun setMorningOffDeg(deg: Double, persist: Boolean) {}
        override fun setEveningOnDeg(deg: Double, persist: Boolean) {}
    }
    private fun report(lightOn: Boolean) = BikeReport(lightOn = lightOn, gear = 2, gears = 5, batteryPct = 70, speedKmh = 0.0, tripKm = 0.0, odoKm = 0.0)
    private val vienna = SunDay.of(LocalDate.of(2026, 11, 14), ZoneId.of("Europe/Vienna"), 48.21, 16.37)

    /** The artboard's sample: Vienna in November, off at 07:15, on at 16:06, now 22:50, light on because dark. */
    private fun evening() = lightUiState(
        liveReport = report(lightOn = true), status = LightStatus(LightReason.DARK, RainNow.DRY),
        mode = LightMode.AUTO, rainEnabled = true, rainLevel = RainLevel.RAIN,
        morningOffDeg = vienna.morningDegFor(7 * 60 + 15), eveningOnDeg = vienna.eveningDegFor(16 * 60 + 6),
        curve = CurveUi(vienna, 22 * 60 + 50),
    )

    @Test @Config(qualifiers = "de-$PHONE")
    fun light_evening_de() = compose.screenshot("light_evening_de") { LightContent(evening(), actions) }

    @Test @Config(qualifiers = "en-$PHONE")
    fun light_evening_en() = compose.screenshot("light_evening_en") { LightContent(evening(), actions) }

    /** Midday, default points (1°), light off, weather says drizzle below the chosen level. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun light_midday_de() = compose.screenshot("light_midday_de") {
        LightContent(
            lightUiState(
                report(lightOn = false), LightStatus(LightReason.DAY, RainNow.BELOW_LEVEL), LightMode.AUTO, true, RainLevel.RAIN,
                1.0, 1.0, CurveUi(vienna, 12 * 60 + 30),
            ),
            actions,
        )
    }

    /** Fresh install: no position known yet, connected, light off; rain switching disabled. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun light_no_position_de() = compose.screenshot("light_no_position_de") {
        LightContent(
            lightUiState(
                report(lightOn = false), LightStatus(LightReason.NO_POSITION, RainNow.DISABLED), LightMode.AUTO, false, RainLevel.DRIZZLE,
                1.0, 1.0, null,
            ),
            actions,
        )
    }

    /** Polar night in Tromsø, always-on mode, bike not connected. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun light_polar_offline_de() = compose.screenshot("light_polar_offline_de") {
        val tromso = SunDay.of(LocalDate.of(2026, 12, 21), ZoneId.of("Europe/Oslo"), 69.65, 18.96)
        LightContent(
            lightUiState(
                null, LightStatus(), LightMode.ALWAYS_ON, true, RainLevel.HEAVY, 1.0, 1.0, CurveUi(tromso, 10 * 60),
            ),
            actions,
        )
    }

    /** Rain switched off: the level row is disabled. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun light_rain_off_de() = compose.screenshot("light_rain_off_de") { LightContent(evening().copy(rainEnabled = false), actions) }

    /** Q4: the display has no lamp switch, so there is no automation to offer. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun light_no_lamp_de() = compose.screenshot("light_no_lamp_de") { LightContent(evening().copy(lampSwitch = false), actions) }

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun light_scrolled_de() = compose.scrolled("light_scrolled_de", scroll = Scroll.UNDER) { LightContent(evening(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun light_end_de() = compose.scrolled("light_end_de", scroll = Scroll.ToEnd) { LightContent(evening(), actions) }
}
