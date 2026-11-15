package dev.donrobo.ebike.screenshots

import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.ConnectionStatus
import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.handlebar.HandlebarTile
import dev.donrobo.ebike.handlebar.HandlebarTiles
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.ui.handlebar.HandlebarActions
import dev.donrobo.ebike.ui.handlebar.HandlebarContent
import dev.donrobo.ebike.ui.handlebar.HandlebarTilesActions
import dev.donrobo.ebike.ui.handlebar.HandlebarTilesContent
import dev.donrobo.ebike.ui.handlebar.HandlebarTilesUiState
import dev.donrobo.ebike.ui.handlebar.handlebarUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/** Artboard size: the handlebar mode is full screen (no bars), 390 x 844 dp. */
private const val HANDLEBAR_PHONE = "w390dp-h844dp-port-xhdpi"
private const val HANDLEBAR_LAND = "w844dp-h390dp-land-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandlebarScreenshotTest {
    @get:Rule val compose = createComposeRule()

    // The artboard's sample data.
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 23.2, tripKm = 10.6, odoKm = 1284.3)
    private val range = RangeEstimate(64.0, 86.0, 1.2)
    private val turn = NavState(130, Icon.LEFT, 2500)
    private val now = LocalTime.of(14, 5)
    private val counters = dev.donrobo.ebike.counters.CounterInputs(
        live = true, odoKm = 1284.3, tripKm = 51.7, openRide = dev.donrobo.ebike.ride.OpenRide("b1", 0, 1273.7),
        lastRideKm = 8.2, fullChargeOdoKm = 1250.0, homeDepartureOdoKm = 1262.0,
    )
    private val actions = object : HandlebarActions {
        override fun setLight(on: Boolean) {}
        override fun lightLocked() {}
        override fun customize() {}
        override fun exit() {}
    }

    private fun live(nav: NavState? = turn, r: BikeReport = report, tiles: List<HandlebarTile> = HandlebarTiles.DEFAULT) =
        handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(r), nav, null, range, null, tiles, now, counters = counters)

    /** Not connected, waiting for the bike; last known values, nothing learned for range yet. */
    private fun offline(status: ConnectionStatus = ConnectionStatus.WAITING) = handlebarUiState(
        status, Snapshot(report.copy(speedKmh = 0.0, lightOn = false), System.currentTimeMillis() - 3_600_000),
        null, null, null, null, HandlebarTiles.DEFAULT, now, counters = counters.copy(openRide = null),
    )

    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_de() = compose.screenshot("handlebar_de") { HandlebarContent(live(), actions) }

    @Test @Config(qualifiers = "en-$HANDLEBAR_PHONE")
    fun handlebar_en() = compose.screenshot("handlebar_en") { HandlebarContent(live(), actions) }

    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_no_nav_de() = compose.screenshot("handlebar_no_nav_de") {
        HandlebarContent(live(nav = null, r = report.copy(lightOn = false)), actions)
    }

    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_offline_de() = compose.screenshot("handlebar_offline_de") { HandlebarContent(offline(), actions) }

    /** After a drop: "Getrennt", as in the notification and the widget. */
    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_lost_de() = compose.screenshot("handlebar_lost_de") { HandlebarContent(offline(ConnectionStatus.LOST), actions) }

    /** Locked bike, six tiles incl. long values (odometer, clock) that have to shrink. */
    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_locked_six_de() = compose.screenshot("handlebar_locked_six_de") {
        HandlebarContent(
            live(nav = null, r = report.copy(unlocked = false), tiles = listOf(HandlebarTile.BATTERY, HandlebarTile.RANGE, HandlebarTile.ODO, HandlebarTile.CLOCK, HandlebarTile.LIGHT, HandlebarTile.HOME)),
            actions,
        )
    }

    @Test @Config(qualifiers = "de-$HANDLEBAR_LAND")
    fun handlebar_land_de() = compose.screenshot("handlebar_land_de") { HandlebarContent(live(), actions) }

    @Test @Config(qualifiers = "de-$HANDLEBAR_LAND")
    fun handlebar_land_six_de() = compose.screenshot("handlebar_land_six_de") {
        HandlebarContent(live(tiles = HandlebarTile.entries.take(HandlebarTiles.MAX)), actions)
    }

    @Test @Config(qualifiers = "de-$HANDLEBAR_LAND")
    fun handlebar_land_offline_de() = compose.screenshot("handlebar_land_offline_de") { HandlebarContent(offline(), actions) }

    private val tilesActions = object : HandlebarTilesActions {
        override fun back() {}
        override fun setTiles(tiles: List<HandlebarTile>) {}
        override fun setAutoStart(on: Boolean) {}
    }

    private fun editor(tiles: List<HandlebarTile>) = HandlebarTilesUiState(
        bikeName = "Stadtrad", tiles = tiles,
        preview = handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(report), null, null, null, null, tiles, now, counters = counters.copy(homeDepartureOdoKm = null)),
        autoStart = true,
    )

    @Test @Config(qualifiers = "de-w390dp-h1500dp-port-xhdpi")
    fun handlebar_tiles_de() = compose.screenshot("handlebar_tiles_de") { HandlebarTilesContent(editor(HandlebarTiles.DEFAULT), tilesActions) }

    @Test @Config(qualifiers = "en-w390dp-h1500dp-port-xhdpi")
    fun handlebar_tiles_full_en() = compose.screenshot("handlebar_tiles_full_en") {
        HandlebarTilesContent(editor(listOf(HandlebarTile.CLOCK, HandlebarTile.BATTERY, HandlebarTile.RANGE, HandlebarTile.ASSIST, HandlebarTile.TRIP, HandlebarTile.ODO)), tilesActions)
    }

    /** A drive error right now, and a display without lamp switch: no light button. */
    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_drive_error_de() = compose.screenshot("handlebar_drive_error_de") {
        HandlebarContent(live().copy(driveError = dev.donrobo.ebike.errors.CurrentDriveError(0x21, 21, 0L), lampSwitch = false), actions)
    }

    /** The four distance counters side by side, plus the odometer. */
    @Test @Config(qualifiers = "de-$HANDLEBAR_PHONE")
    fun handlebar_counters_de() = compose.screenshot("handlebar_counters_de") {
        HandlebarContent(
            live(nav = null, tiles = listOf(HandlebarTile.RIDE, HandlebarTile.TRIP, HandlebarTile.SINCE_CHARGE, HandlebarTile.SINCE_HOME, HandlebarTile.ODO, HandlebarTile.BATTERY)),
            actions,
        )
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun handlebar_tiles_scrolled_de() = compose.scrolled("handlebar_tiles_scrolled_de", scroll = Scroll.UNDER) { HandlebarTilesContent(editor(HandlebarTiles.DEFAULT), tilesActions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun handlebar_tiles_end_de() = compose.scrolled("handlebar_tiles_end_de", scroll = Scroll.ToEnd) { HandlebarTilesContent(editor(HandlebarTiles.DEFAULT), tilesActions) }
}
