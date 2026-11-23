package dev.donrobo.ebike.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.res.stringResource
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.PreviewNavigator
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.components.ConfirmDialog
import dev.donrobo.ebike.ui.components.InfoSheet
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.light.SunCurveInfo
import dev.donrobo.ebike.ui.range.AreaInfo
import dev.donrobo.ebike.ui.range.DetourInfo
import dev.donrobo.ebike.ui.range.ModelInfo
import dev.donrobo.ebike.ui.components.TextInputDialog
import dev.donrobo.ebike.ui.rides.RidesScreen
import dev.donrobo.ebike.ui.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Other screens of the shell. Feature agents: add one test per screen here or in their own file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-$PHONE")
class ScreensScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settings_de() = compose.screenshot("settings_de", Tab.SETTINGS) { SettingsScreen(PreviewNavigator) }

    @Test fun rides_placeholder_de() = compose.screenshot("rides_placeholder_de", Tab.RIDES) { RidesScreen(PreviewNavigator) }

    /** The shared dialogs in their real windows over the settings tab. */
    @Test fun dialog_text_input_de() = compose.screenshotWindows("dialog_text_input_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        TextInputDialog("Strompreis", "32", {}, {}, number = true, suffix = "ct/kWh", label = "Preis pro kWh")
    }

    @Test fun dialog_confirm_de() = compose.screenshotWindows("dialog_confirm_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        ConfirmDialog("Fahrt löschen?", "Die Fahrt und ihre Strecke werden vom Handy gelöscht.", "Löschen", {}, {})
    }

    /** Explanation sheets (S4) as the ⓘ opens them, over the settings tab. */
    @Test fun info_sun_curve_de() = compose.screenshotWindows("info_sun_curve_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.light_curve_title), {}) { SunCurveInfo() }
    }

    @Test fun info_range_model_de() = compose.screenshotWindows("info_range_model_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.range_model_title), {}) { ModelInfo(rememberUnitFormatter()) }
    }

    @Test @Config(qualifiers = "en-$PHONE")
    fun info_range_model_en() = compose.screenshotWindows("info_range_model_en", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.range_model_title), {}) { ModelInfo(rememberUnitFormatter()) }
    }

    @Test fun info_range_area_de() = compose.screenshotWindows("info_range_area_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.range_area_title), {}) { AreaInfo(1.27) }
    }

    @Test @Config(qualifiers = "de-w390dp-h1000dp-port-xhdpi")
    fun info_range_detour_de() = compose.screenshotWindows("info_range_detour_de", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.range_detour_sheet_title), {}) { DetourInfo(rememberUnitFormatter()) }
    }

    @Test @Config(qualifiers = "en-w390dp-h1000dp-port-xhdpi")
    fun info_range_detour_en() = compose.screenshotWindows("info_range_detour_en", Tab.SETTINGS) {
        SettingsScreen(PreviewNavigator)
        InfoSheet(stringResource(R.string.range_detour_sheet_title), {}) { DetourInfo(rememberUnitFormatter()) }
    }

    @Test @Config(qualifiers = "de-w390dp-h1900dp-port-xhdpi")
    fun components_de() = compose.screenshot("components_de") { ComponentGallery() }
}
