package dev.donrobo.ebike.ui.handlebar

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.handlebar.HandlebarTile.ASSIST
import dev.donrobo.ebike.handlebar.HandlebarTile.BATTERY
import dev.donrobo.ebike.handlebar.HandlebarTile.CLOCK
import dev.donrobo.ebike.handlebar.HandlebarTile.HOME
import dev.donrobo.ebike.handlebar.HandlebarTile.LIGHT
import dev.donrobo.ebike.handlebar.HandlebarTile.ODO
import dev.donrobo.ebike.handlebar.HandlebarTile.RANGE
import dev.donrobo.ebike.handlebar.HandlebarTile.RIDE
import dev.donrobo.ebike.handlebar.HandlebarTile.SINCE_CHARGE
import dev.donrobo.ebike.handlebar.HandlebarTile.SINCE_HOME
import dev.donrobo.ebike.handlebar.HandlebarTile.SPEED
import dev.donrobo.ebike.handlebar.HandlebarTile.TRIP
import dev.donrobo.ebike.handlebar.HandlebarTiles
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class HandlebarStateTest {
    private val fmt = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 23.2, tripKm = 10.6, odoKm = 1284.3)
    private val now = LocalTime.of(14, 5)

    private fun live(r: BikeReport = report, nav: NavState? = null) =
        handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(r), nav, null, RangeEstimate(64.0, 86.0, 1.2), null, HandlebarTiles.DEFAULT, now)

    private fun offline(status: ConnectionStatus = ConnectionStatus.WAITING) =
        handlebarUiState(status, Snapshot(report.copy(speedKmh = 0.0), 1_000), null, null, null, null, HandlebarTiles.DEFAULT, now)

    @Test fun liveReadings() {
        val s = live()
        assertEquals(TileReading.Value("75", TileUnit.Percent), tileReading(BATTERY, s, fmt))
        assertEquals(TileReading.Value("64", TileUnit.Of(UnitLabel.KM)), tileReading(RANGE, s, fmt))
        assertEquals(TileReading.Value("3", TileUnit.OfGears(5)), tileReading(ASSIST, s, fmt))
        assertEquals(TileReading.Value("10,6", TileUnit.Of(UnitLabel.KM)), tileReading(TRIP, s, fmt))
        assertEquals(TileReading.Value("1.284", TileUnit.Of(UnitLabel.KM)), tileReading(ODO, s, fmt))
        assertEquals(TileReading.Value("23", TileUnit.Of(UnitLabel.KMH)), tileReading(SPEED, s, fmt))
        assertEquals(TileReading.Light(true), tileReading(LIGHT, s, fmt))
        assertEquals(TileReading.Value("14:05", TileUnit.None), tileReading(CLOCK, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.Of(UnitLabel.KM)), tileReading(HOME, s, fmt))
        assertEquals(23.2, s.speedKmh!!, 0.0)
    }

    @Test fun offlineShowsLastKnownMutedAndLiveOnlyValuesEmpty() {
        val s = offline()
        assertEquals(TileReading.Value("75", TileUnit.Percent, stale = true), tileReading(BATTERY, s, fmt))
        assertEquals(TileReading.Value("10,6", TileUnit.Of(UnitLabel.KM), stale = true), tileReading(TRIP, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.None), tileReading(ASSIST, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.Of(UnitLabel.KMH)), tileReading(SPEED, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.None), tileReading(LIGHT, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.Of(UnitLabel.KM)), tileReading(RANGE, s, fmt)) // nothing learned yet
        assertNull(s.speedKmh)
        assertEquals(1_000L, s.lastKnownAtMs)
    }

    @Test fun counterTiles() {
        val inputs = dev.donrobo.ebike.counters.CounterInputs(
            live = false, odoKm = null, tripKm = null, openRide = dev.donrobo.ebike.ride.OpenRide("b", 0, 1270.0),
            lastRideKm = 9.0, fullChargeOdoKm = 1250.0, homeDepartureOdoKm = null,
        )
        val s = handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(report), null, null, null, null, HandlebarTiles.DEFAULT, now, counters = inputs)
        // odometer and live flag come from the screen's own report, not from the (older) inputs
        assertEquals(TileReading.Value("14,3", TileUnit.Of(UnitLabel.KM)), tileReading(RIDE, s, fmt))
        assertEquals(TileReading.Value("34,3", TileUnit.Of(UnitLabel.KM)), tileReading(SINCE_CHARGE, s, fmt))
        assertEquals(TileReading.Empty(TileUnit.Of(UnitLabel.KM)), tileReading(SINCE_HOME, s, fmt))
        // between rides: the last ride, muted
        val between = handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(report), null, null, null, null, HandlebarTiles.DEFAULT, now, counters = inputs.copy(openRide = null))
        assertEquals(TileReading.Value("9,0", TileUnit.Of(UnitLabel.KM), stale = true), tileReading(RIDE, between, fmt))
    }

    @Test fun milesFollowTheUnitSetting() {
        val mi = UnitFormatter(UnitSystem.IMPERIAL, Locale.US)
        assertEquals(TileReading.Value("6.6", TileUnit.Of(UnitLabel.MI)), tileReading(TRIP, live(), mi))
    }

    @Test fun topArea() {
        assertEquals(HandlebarTop.NONE, live().top)
        assertEquals(HandlebarTop.NAV, live(nav = NavState(130, Icon.LEFT, 2500)).top)
        assertEquals(HandlebarTop.WAITING, offline().top)
        assertEquals(HandlebarTop.OFFLINE, offline(ConnectionStatus.OFF).top)
        assertEquals(HandlebarTop.CONNECTING, offline(ConnectionStatus.CONNECTING).top)
        assertEquals(HandlebarTop.LOST, offline(ConnectionStatus.LOST).top)
        // Navigation runs on the phone and is worth showing even without the bike.
        val navOffline = handlebarUiState(ConnectionStatus.OFF, null, NavState(80, Icon.RIGHT, 900), null, null, null, emptyList(), now)
        assertEquals(HandlebarTop.NAV, navOffline.top)
    }

    @Test fun fillingTilesGrowWithTheHeightUpToTwice() {
        assertEquals(1f, tileGrow(0.8f), 0f) // never shrinks by height: the unscaled tile is the minimum
        assertEquals(1.5f, tileGrow(1.5f), 0f)
        assertEquals(MaxTileGrow, tileGrow(5f), 0f)
    }

    @Test fun testManeuverWinsOverMaps() {
        val s = handlebarUiState(ConnectionStatus.CONNECTED, Snapshot.live(report), NavState(500, Icon.LEFT, 1), NavState(50, Icon.UTURN, 1), null, null, emptyList(), now)
        assertEquals(Icon.UTURN, s.nav!!.icon)
    }

    @Test fun lightButtonReflectsLockAndLink() {
        assertEquals(LightControl.ON, live().light)
        assertEquals(LightControl.OFF, live(report.copy(lightOn = false)).light)
        assertEquals(LightControl.LOCKED, live(report.copy(unlocked = false)).light)
        assertEquals(LightControl.NOT_CONNECTED, offline().light)
        // Connected but no report yet (or the link belongs to another bike): no live snapshot, not live.
        assertTrue(handlebarUiState(ConnectionStatus.CONNECTED, null, null, null, null, null, emptyList(), now).light == LightControl.NOT_CONNECTED)
    }
}
