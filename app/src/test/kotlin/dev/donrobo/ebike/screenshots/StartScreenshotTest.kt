package dev.donrobo.ebike.screenshots

import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.ConnectionStatus
import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.light.LightReason
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.light.RainNow
import dev.donrobo.ebike.maintenance.MaintenanceDue
import dev.donrobo.ebike.bike.DisplayInfo
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.range.HomeReach
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.start.StartActions
import dev.donrobo.ebike.ui.start.StartContent
import dev.donrobo.ebike.ui.start.startUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val bike = Bike("b1", "Stadtrad", "00:11:22:33:44:55")
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 23.2, tripKm = 10.6, odoKm = 1284.3)
    /** Ride since odometer 1273.7 (= the artboard's 10,6 km), full charge at 1250, set off from home at 1262. */
    private val counters = dev.donrobo.ebike.counters.CounterInputs(
        live = true, odoKm = 1284.3, tripKm = 51.7, openRide = dev.donrobo.ebike.ride.OpenRide("b1", 0, 1273.7),
        lastRideKm = 8.2, fullChargeOdoKm = 1250.0, homeDepartureOdoKm = 1262.0,
    )
    private val actions = object : StartActions {
        override fun openRoute(route: String) {}
        override fun selectBike(id: String) {}
        override fun setLight(on: Boolean) {}
        override fun openConnection() {}
    }

    /** The artboard's sample data, connected, with all feature cards present. */
    private val display = DisplayInfo(mapOf("software" to "EKD01_CAN_BF_APO_N04"))

    private fun full() = startUiState(
        bike, listOf(bike), ConnectionStatus.CONNECTED, display, Snapshot.live(report),
        light = LightStatus(LightReason.DARK, RainNow.DRY, since = java.time.LocalTime.of(16, 6)),
        range = RangeEstimate(64.0, 86.0, 1.2), home = HomeReach(4.2, true),
        mapsNav = NavState(130, Icon.LEFT, 2500), testNav = null, forwardNav = true,
        maintenance = MaintenanceDue("Kette schmieren", 46.0), counters = counters,
    )

    @Test @Config(qualifiers = "de-$PHONE")
    fun start_de() = compose.screenshot("start_de", Tab.START) { StartContent(full(), actions) }

    @Test @Config(qualifiers = "en-$PHONE")
    fun start_en() = compose.screenshot("start_en", Tab.START) { StartContent(full(), actions) }

    /**
     * Not connected: last known state with its time (U2), nothing learned yet, no nav/maintenance. The last report
     * had the lamp on, which must not show as current.
     */
    private fun offline(status: ConnectionStatus = ConnectionStatus.WAITING) = startUiState(
        bike, listOf(bike), status, null, Snapshot(report, System.currentTimeMillis() - 3_600_000),
        light = LightStatus(), range = null, home = null,
        mapsNav = null, testNav = null, forwardNav = true, maintenance = null, counters = counters.copy(openRide = null),
    )

    @Test @Config(qualifiers = "de-$PHONE")
    fun start_offline_de() = compose.screenshot("start_offline_de", Tab.START) { StartContent(offline(), actions) }

    @Test @Config(qualifiers = "en-$PHONE")
    fun start_offline_en() = compose.screenshot("start_offline_en", Tab.START) { StartContent(offline(), actions) }

    /** The connection dropped and the service still tries: the same "Getrennt" as notification and widget. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun start_lost_de() = compose.screenshot("start_lost_de", Tab.START) { StartContent(offline(ConnectionStatus.LOST), actions) }

    /** Range learned, battery level unknown (no report since the update): full range and consumption, no "learning". */
    @Test @Config(qualifiers = "de-$PHONE")
    fun start_battery_unknown_de() = compose.screenshot("start_battery_unknown_de", Tab.START) {
        StartContent(
            startUiState(
                bike, listOf(bike), ConnectionStatus.CONNECTING, null, null,
                light = LightStatus(), range = null, home = null,
                mapsNav = null, testNav = null, forwardNav = true, maintenance = null,
                rangeFull = dev.donrobo.ebike.range.FullRange(74.1, 1.2),
            ),
            actions,
        )
    }

    /** A drive error right now (code 21, speed sensor), and a display without lamp switch (Q4). */
    @Test @Config(qualifiers = "de-$PHONE")
    fun start_drive_error_de() = compose.screenshot("start_drive_error_de", Tab.START) {
        StartContent(full().copy(driveError = dev.donrobo.ebike.errors.CurrentDriveError(0x21, 21, 0L), canSwitchLight = false), actions)
    }

    /** The rider chose "Seit Vollladen" for the tile (Einstellungen). */
    @Test @Config(qualifiers = "de-$PHONE")
    fun start_counter_charge_de() = compose.screenshot("start_counter_charge_de", Tab.START) {
        StartContent(
            startUiState(
                bike, listOf(bike), ConnectionStatus.CONNECTED, display, Snapshot.live(report),
                light = LightStatus(LightReason.DARK, RainNow.DRY, since = java.time.LocalTime.of(16, 6)),
                range = RangeEstimate(64.0, 86.0, 1.2), home = null, mapsNav = null, testNav = null, forwardNav = true, maintenance = null,
                counter = dev.donrobo.ebike.counters.DistanceCounter.SINCE_CHARGE, counters = counters,
            ),
            actions,
        )
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun start_scrolled_de() = compose.scrolled("start_scrolled_de", Tab.START, Scroll.UNDER) { StartContent(full(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun start_end_de() = compose.scrolled("start_end_de", Tab.START, Scroll.ToEnd) {
        StartContent(full().copy(driveError = dev.donrobo.ebike.errors.CurrentDriveError(0x21, 21, 0L)), actions)
    }
}
