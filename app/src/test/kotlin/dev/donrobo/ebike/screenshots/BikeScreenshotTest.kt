package dev.donrobo.ebike.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.bike.DisplayInfo
import dev.donrobo.ebike.errors.ErrorEntry
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.maintenance.Maintenance
import dev.donrobo.ebike.maintenance.MaintenanceItem
import dev.donrobo.ebike.maintenance.MaintenanceKind
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.bike.BikeActions
import dev.donrobo.ebike.ui.bike.BikeContent
import dev.donrobo.ebike.ui.bike.BikeDialog
import dev.donrobo.ebike.ui.bike.BikeDialogHost
import dev.donrobo.ebike.ui.bike.BikeUiState
import dev.donrobo.ebike.ui.bike.ParkedUi
import dev.donrobo.ebike.ui.bike.bikeUiState
import dev.donrobo.ebike.ui.bike.errorSection
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private const val TALL = "w390dp-h1640dp-port-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BikeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val bike = Bike("b1", "Stadtrad", "00:11:22:33:44:55")
    private val other = Bike("b2", "Lastenrad", null)
    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val yesterday2222 = ms(LocalDate.now().minusDays(1).atTime(22, 22))
    private val oct3 = ms(LocalDate.now().withMonth(10).withDayOfMonth(3).atTime(17, 40))

    /** The artboard's items: chain done at 1030 km, brake pads at 1000 km, gear hub never (baseline 0). */
    private val items = listOf(
        MaintenanceItem("chain", MaintenanceKind.CHAIN, "", 300.0, 1030.0),
        MaintenanceItem("brakes", MaintenanceKind.BRAKES, "", 1000.0, 1000.0),
        MaintenanceItem("gear", MaintenanceKind.GEAR, "", 3000.0, null),
    )
    private val display = DisplayInfo(
        mapOf("hardware" to "B02NC_1.01", "software" to "EKD01_CAN_BF_APO_N04", "serial" to "0000A1B24321", "controller" to ""),
    )

    private fun artboard(errors: List<ErrorEntry> = listOf(ErrorEntry(ErrorEntry.Source.LIVE, oct3, 1100.0, null, "21")), currentRaw: Int = 0, live: Boolean = false, items: List<MaintenanceItem> = this.items) =
        bikeUiState(
            bike = bike, bikes = listOf(bike, other), odoKm = 1284.3,
            parked = if (live) ParkedUi.ConnectedNow else ParkedUi.Known(yesterday2222, GeoPoint(48.2085, 16.3721), null, null),
            maintenance = Maintenance.statuses(items, 1284.3, 0.0),
            errors = errorSection(true, errors, currentRaw, live, currentSinceMs = if (live) System.currentTimeMillis() - 120_000 else null),
            chargeRemind = true, chargeThresholdPct = 30, batteryWh = 0, display = display,
        )

    private fun neverConnected() = bikeUiState(
        bike = Bike("b3", "Neues Rad", "00:11:22:33:44:01"), bikes = listOf(bike), odoKm = null, parked = ParkedUi.Never,
        maintenance = Maintenance.statuses(Maintenance.defaults(UnitSystem.METRIC), null, null),
        errors = errorSection(true, emptyList(), null, false), chargeRemind = true, chargeThresholdPct = 30, batteryWh = 0, display = null,
    )

    /** A bike looked after for a while: the artboard's items plus tyres, fork, lights and a yearly service. */
    private fun wellKept() = artboard(
        errors = listOf(
            ErrorEntry(ErrorEntry.Source.LIVE, oct3, 1100.0, null, "21"),
            ErrorEntry(ErrorEntry.Source.HISTORY, oct3, null, null, "3330"),
            ErrorEntry(ErrorEntry.Source.LIVE, oct3 - 86_400_000, 1090.0, null, "2f"),
        ),
        items = items + listOf(
            MaintenanceItem("tyres", MaintenanceKind.CUSTOM, "Reifendruck prüfen", 200.0, 1200.0),
            MaintenanceItem("fork", MaintenanceKind.CUSTOM, "Federgabel schmieren", 500.0, 900.0),
            MaintenanceItem("light", MaintenanceKind.CUSTOM, "Lichtanlage prüfen", 1000.0, 600.0),
            MaintenanceItem("service", MaintenanceKind.CUSTOM, "Jahresservice Händler", 2000.0, null),
        ),
    )

    private val actions = object : BikeActions {
        override fun selectBike(id: String) {}
        override fun renameBike(name: String) {}
        override fun addBike() {}
        override fun removeBike() {}
        override fun navigateTo(point: GeoPoint) {}
        override fun fixLocation() {}
        override fun markDone(itemId: String) {}
        override fun saveItem(itemId: String?, name: String?, intervalKm: Double) {}
        override fun removeItem(itemId: String) {}
        override fun setChargeRemind(on: Boolean) {}
        override fun setChargeThreshold(pct: Int) {}
        override fun setBatteryWh(wh: Int) {}
        override fun forgetDisplay() {}
        override fun pairDisplay() {}
    }

    @Test @Config(qualifiers = "de-$TALL")
    fun bike_de() = compose.screenshot("bike_de", Tab.BIKE) { BikeContent(artboard(), actions, null, {}) }

    @Test @Config(qualifiers = "en-$TALL")
    fun bike_en() = compose.screenshot("bike_en", Tab.BIKE) { BikeContent(artboard(), actions, null, {}) }

    /** Connected with a drive error now; history with a live entry, a register entry and an undecodable byte. */
    @Test @Config(qualifiers = "de-$TALL")
    fun bike_error_de() = compose.screenshot("bike_error_de", Tab.BIKE) {
        val errors = listOf(
            ErrorEntry(ErrorEntry.Source.LIVE, System.currentTimeMillis() - 120_000, 1284.0, null, "21"),
            ErrorEntry(ErrorEntry.Source.HISTORY, oct3, null, null, "3330"),
            ErrorEntry(ErrorEntry.Source.LIVE, oct3 - 86_400_000, 1100.0, null, "2f"),
            ErrorEntry(ErrorEntry.Source.LIVE, oct3 - 2 * 86_400_000, 1090.0, null, "99"),
        )
        BikeContent(artboard(errors, currentRaw = 0x21, live = true).copy(batteryWh = 504), actions, null, {})
    }

    @Test @Config(qualifiers = "de-$TALL")
    fun bike_never_connected_de() = compose.screenshot("bike_never_connected_de", Tab.BIKE) { BikeContent(neverConnected(), actions, null, {}) }

    /** The Rad tab with [dialog] open in its real Material dialog/sheet window. */
    private fun dialogShot(name: String, state: BikeUiState, dialog: BikeDialog) =
        compose.screenshotWindows(name, Tab.BIKE) { BikeContent(state, actions, dialog, {}) }

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_edit_de() = dialogShot("bike_dialog_edit_de", artboard(), BikeDialog.EditItem("chain"))

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_add_de() = dialogShot("bike_dialog_add_de", artboard(), BikeDialog.AddItem)

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_capacity_de() = dialogShot("bike_dialog_capacity_de", artboard().copy(batteryWh = 504), BikeDialog.Capacity)

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_threshold_de() = dialogShot("bike_dialog_threshold_de", artboard(), BikeDialog.Threshold)

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_remove_de() = dialogShot("bike_dialog_remove_de", artboard(), BikeDialog.Remove)

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_dialog_forget_de() = dialogShot("bike_dialog_forget_de", artboard(), BikeDialog.Forget)

    @Test @Config(qualifiers = "de-$PHONE")
    fun bike_sheet_bikes_de() = dialogShot("bike_sheet_bikes_de", artboard(), BikeDialog.Bikes)

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun bike_collapsing_de() = compose.scrolled("bike_collapsing_de", Tab.BIKE, Scroll.COLLAPSING) { BikeContent(wellKept(), actions, null, {}) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun bike_scrolled_de() = compose.scrolled("bike_scrolled_de", Tab.BIKE, Scroll.UNDER) { BikeContent(wellKept(), actions, null, {}) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun bike_end_de() = compose.scrolled("bike_end_de", Tab.BIKE, Scroll.ToEnd) { BikeContent(wellKept(), actions, null, {}) }
}
