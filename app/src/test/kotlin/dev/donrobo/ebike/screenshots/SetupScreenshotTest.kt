package dev.donrobo.ebike.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.pairing.Signal
import dev.donrobo.ebike.ui.setup.AppPermission
import dev.donrobo.ebike.ui.setup.DoneActions
import dev.donrobo.ebike.ui.setup.DoneContent
import dev.donrobo.ebike.ui.setup.PairActions
import dev.donrobo.ebike.ui.setup.PairContent
import dev.donrobo.ebike.ui.setup.PairUiState
import dev.donrobo.ebike.ui.setup.PermissionFacts
import dev.donrobo.ebike.ui.setup.PermissionsStepContent
import dev.donrobo.ebike.ui.setup.PermissionsScreen
import dev.donrobo.ebike.ui.PreviewNavigator
import dev.donrobo.ebike.ui.setup.SearchState
import dev.donrobo.ebike.ui.setup.doneUiState
import dev.donrobo.ebike.ui.setup.permissionItems
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Artboard size (390×844) so steps 1 and 2 can be laid over Einrichtung.png / Berechtigungen.png. */
private const val ARTBOARD = "w390dp-h844dp-port-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val pair = object : PairActions {
        override fun setName(name: String) {}
        override fun pair() {}
        override fun searchOther() {}
        override fun allowBluetooth() {}
        override fun enableBluetooth() {}
    }
    private val done = object : DoneActions {
        override fun changePermissions() {}
        override fun finish() {}
    }
    private val found = SearchState.Found("EKD01", "00:11:22:33:44:55", Signal.GOOD)

    /** The artboard's state: Bluetooth allowed, the rest not yet. */
    private val artboardFacts = PermissionFacts(
        bluetoothScan = true, bluetoothConnect = true, notificationListener = false, fineLocation = false, coarseLocation = false,
        backgroundLocation = false, batteryOptimizationIgnored = false, backgroundRestricted = false, postNotifications = false,
    )

    @Test @Config(qualifiers = "de-$ARTBOARD")
    fun setup1_found_de() = compose.screenshot("setup1_found_de") { PairContent(PairUiState(found, "Stadtrad"), pair) }

    @Test @Config(qualifiers = "en-$ARTBOARD")
    fun setup1_found_en() = compose.screenshot("setup1_found_en") { PairContent(PairUiState(found, "Stadtrad"), pair) }

    @Test @Config(qualifiers = "de-$ARTBOARD")
    fun setup1_searching_de() = compose.screenshot("setup1_searching_de") { PairContent(PairUiState(SearchState.Searching, "Mein Rad"), pair) }

    @Test @Config(qualifiers = "de-$ARTBOARD")
    fun setup1_permission_de() = compose.screenshot("setup1_permission_de") { PairContent(PairUiState(SearchState.NeedsPermission, "Mein Rad"), pair) }

    @Test @Config(qualifiers = "de-$ARTBOARD")
    fun setup1_notfound_de() = compose.screenshot("setup1_notfound_de") { PairContent(PairUiState(SearchState.NotFound, "Rad 2"), pair) }

    @Test @Config(qualifiers = "de-$ARTBOARD")
    fun setup2_de() = compose.screenshot("setup2_de") { PermissionsStepContent(permissionItems(artboardFacts), "Immer zulassen", {}, {}) }

    @Test @Config(qualifiers = "en-$ARTBOARD")
    fun setup2_en() = compose.screenshot("setup2_en") { PermissionsStepContent(permissionItems(artboardFacts), "Allow all the time", {}, {}) }

    /** Location approximate, background location missing, notification access allowed. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun setup2_partial_de() = compose.screenshot("setup2_partial_de") {
        PermissionsStepContent(
            permissionItems(artboardFacts.copy(notificationListener = true, coarseLocation = true, batteryOptimizationIgnored = true)),
            "Immer zulassen", {}, {},
        )
    }

    /** Bluetooth missing: "Weiter" disabled with the reason. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun setup2_no_bluetooth_de() = compose.screenshot("setup2_no_bluetooth_de") {
        PermissionsStepContent(permissionItems(artboardFacts.copy(bluetoothScan = false, bluetoothConnect = false)), "Immer zulassen", {}, {})
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun setup3_skipped_de() = compose.screenshot("setup3_skipped_de") {
        val items = permissionItems(artboardFacts.copy(fineLocation = true, coarseLocation = true, batteryOptimizationIgnored = true, postNotifications = true))
        DoneContent(doneUiState("Stadtrad", "EKD01", items, associated = true, autoConnect = true), done)
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun setup3_all_de() = compose.screenshot("setup3_all_de") {
        val all = permissionItems(PermissionFacts(true, true, true, true, true, true, true, false, true))
        DoneContent(doneUiState("Stadtrad", "EKD01", all, associated = true, autoConnect = true), done)
    }

    @Test @Config(qualifiers = "en-$PHONE")
    fun setup3_skipped_en() = compose.screenshot("setup3_skipped_en") {
        val items = permissionItems(artboardFacts).filter { it.permission != AppPermission.NOTIFICATIONS }
        DoneContent(doneUiState("City bike", null, items, associated = false, autoConnect = true), done)
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    /** Berechtigungen opened from the settings (the live screen; Robolectric grants nothing). */
    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun permissions_scrolled_de() = compose.scrolled("permissions_scrolled_de", scroll = Scroll.UNDER) { PermissionsScreen(PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun permissions_end_de() = compose.scrolled("permissions_end_de", scroll = Scroll.ToEnd) { PermissionsScreen(PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun setup2_end_de() = compose.scrolled("setup2_end_de", scroll = Scroll.ToEnd) { PermissionsStepContent(permissionItems(artboardFacts), "Immer zulassen", {}, {}) }
}
