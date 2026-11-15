package dev.donrobo.ebike.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.nav.ShowFrom
import dev.donrobo.ebike.settings.AppLanguage
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.settings.LightSummary
import dev.donrobo.ebike.ui.settings.NavSummary
import dev.donrobo.ebike.ui.settings.RangeSummary
import dev.donrobo.ebike.ui.settings.Reminders
import dev.donrobo.ebike.ui.settings.RemindersSheet
import dev.donrobo.ebike.ui.settings.CounterDialog
import dev.donrobo.ebike.ui.settings.SettingsActions
import dev.donrobo.ebike.ui.settings.SettingsContent
import dev.donrobo.ebike.ui.settings.SettingsUiState
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Artboard Einstellungen is 1460 dp high; the counter rows (Fahren) add 150 dp. */
private const val SETTINGS_PHONE = "w390dp-h1790dp-port-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = object : SettingsActions {
        override fun open(route: String) {}
        override fun setConnectionDetails(on: Boolean) {}
        override fun setDriveErrorAlerts(on: Boolean) {}
        override fun setAutoConnect(on: Boolean) {}
        override fun setClockAuto(on: Boolean) {}
        override fun openReminders() {}
        override fun editPrice() {}
        override fun pickLanguage() {}
        override fun pickUnits() {}
        override fun setWallpaperColors(on: Boolean) {}
    }

    /** The artboard's sample values. */
    private fun sample(language: AppLanguage = AppLanguage.GERMAN) = SettingsUiState(
        light = LightSummary(LightMode.AUTO, rain = true),
        range = RangeSummary(20, 10, home = true),
        nav = NavSummary(true, ShowFrom.M200),
        gpsTrack = true,
        priceCentPerKwh = 30.0,
        connectionDetails = true,
        reminders = Reminders(homeRange = true, charge = true, maintenance = true),
        driveErrorAlerts = true,
        bikeNames = listOf("Stadtrad"),
        autoConnect = true,
        clockAuto = true,
        language = language,
        units = UnitSystem.METRIC,
        wallpaperColors = false,
    )

    @Test @Config(qualifiers = "de-$SETTINGS_PHONE")
    fun settings_full_de() = compose.screenshot("settings_full_de", Tab.SETTINGS) { SettingsContent(sample(), actions) }

    @Test @Config(qualifiers = "en-$SETTINGS_PHONE")
    fun settings_full_en() = compose.screenshot("settings_full_en", Tab.SETTINGS) { SettingsContent(sample(AppLanguage.SYSTEM), actions) }

    /** A display without lamp switch and clock (Q4): Licht and the clock row are gone; details off. */
    @Test @Config(qualifiers = "de-$SETTINGS_PHONE")
    fun settings_reduced_de() = compose.screenshot("settings_reduced_de", Tab.SETTINGS) {
        SettingsContent(sample().copy(light = null, clockAuto = null, connectionDetails = false, nav = NavSummary(false, ShowFrom.ALWAYS)), actions)
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun settings_reminders_de() = compose.screenshotWindows("settings_reminders_de", Tab.SETTINGS) {
        SettingsContent(sample(), actions)
        RemindersSheet(Reminders(homeRange = true, charge = false, maintenance = true), { _, _ -> }, {})
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun settings_counter_picker_de() = compose.screenshotWindows("settings_counter_picker_de", Tab.SETTINGS) {
        SettingsContent(sample(), actions)
        CounterDialog(androidx.compose.ui.res.stringResource(dev.donrobo.ebike.R.string.counters_setting_start), dev.donrobo.ebike.counters.DistanceCounter.THIS_RIDE, {}, {})
    }

    @Test @Config(qualifiers = "en-$PHONE")
    fun settings_counter_picker_en() = compose.screenshotWindows("settings_counter_picker_en", Tab.SETTINGS) {
        SettingsContent(sample(), actions)
        CounterDialog(androidx.compose.ui.res.stringResource(dev.donrobo.ebike.R.string.counters_setting_widget), dev.donrobo.ebike.counters.DistanceCounter.SINCE_CHARGE, {}, {})
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation unless named) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun settings_collapsing_de() = compose.scrolled("settings_collapsing_de", Tab.SETTINGS, Scroll.COLLAPSING) { SettingsContent(sample(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun settings_scrolled_de() = compose.scrolled("settings_scrolled_de", Tab.SETTINGS, Scroll.UNDER) { SettingsContent(sample(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun settings_end_de() = compose.scrolled("settings_end_de", Tab.SETTINGS, Scroll.ToEnd) { SettingsContent(sample(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun settings_end_buttons_de() = compose.scrolled("settings_end_buttons_de", Tab.SETTINGS, Scroll.ToEnd, SystemBars.BUTTONS) { SettingsContent(sample(), actions) }
}
