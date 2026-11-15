package dev.donrobo.ebike.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.donrobo.ebike.ui.PreviewNavigator
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.components.ConfirmDialog
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

    @Test @Config(qualifiers = "de-w390dp-h1900dp-port-xhdpi")
    fun components_de() = compose.screenshot("components_de") { ComponentGallery() }
}
