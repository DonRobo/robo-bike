package dev.donrobo.ebike.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.donrobo.ebike.ui.disclosure.Disclosure
import dev.donrobo.ebike.ui.disclosure.DisclosureDialog
import dev.donrobo.ebike.ui.setup.PermissionFacts
import dev.donrobo.ebike.ui.setup.PermissionsStepContent
import dev.donrobo.ebike.ui.setup.permissionItems
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The prominent disclosures in their real dialog window, over setup step 2 where the rider taps "Erlauben". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisclosureScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Bluetooth and location (while in use) allowed: background location and notification access still open. */
    private val facts = PermissionFacts(
        bluetoothScan = true, bluetoothConnect = true, notificationListener = false, fineLocation = true, coarseLocation = true,
        backgroundLocation = false, batteryOptimizationIgnored = false, backgroundRestricted = false, postNotifications = true,
        companionAssociated = true,
    )

    private fun shot(name: String, d: Disclosure, label: String) = compose.screenshotWindows(name) {
        PermissionsStepContent(permissionItems(facts), label, {}, {})
        DisclosureDialog(d, {}, {}, label)
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun disclosure_bg_location_de() = shot("disclosure_bg_location_de", Disclosure.BACKGROUND_LOCATION, "Immer zulassen")

    @Test @Config(qualifiers = "en-$PHONE")
    fun disclosure_bg_location_en() = shot("disclosure_bg_location_en", Disclosure.BACKGROUND_LOCATION, "Allow all the time")

    @Test @Config(qualifiers = "de-$PHONE")
    fun disclosure_listener_de() = shot("disclosure_listener_de", Disclosure.NOTIFICATION_ACCESS, "Immer zulassen")

    @Test @Config(qualifiers = "en-$PHONE")
    fun disclosure_listener_en() = shot("disclosure_listener_en", Disclosure.NOTIFICATION_ACCESS, "Allow all the time")
}
