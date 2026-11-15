package dev.donrobo.ebike.ui.handlebar

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.counters.CounterInputs
import dev.donrobo.ebike.counters.Counters
import dev.donrobo.ebike.counters.decimals
import dev.donrobo.ebike.handlebar.HandlebarTile
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.errors.CurrentDriveError
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.range.HomeReach
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the area above the speed shows. Navigation wins; without it, only a missing link is worth the space. */
enum class HandlebarTop { NAV, CONNECTING, WAITING, LOST, OFFLINE, NONE }

/** Everything the handlebar mode shows; built by [handlebarUiState], rendered by [HandlebarContent]. */
data class HandlebarUiState(
    val connection: ConnectionStatus,
    /** Live report, or the last known one when not connected (then [lastKnownAtMs] is set). */
    val report: BikeReport?,
    val lastKnownAtMs: Long?,
    val nav: NavState?,
    val range: RangeEstimate?,
    val home: HomeReach?,
    val tiles: List<HandlebarTile>,
    val now: LocalTime,
    /** Q4: the display has a lamp switch; without one the light button is left out. */
    val lampSwitch: Boolean = true,
    /** The drive error the display reports right now; shown as a banner above everything else. */
    val driveError: CurrentDriveError? = null,
    /** Inputs of the distance counters, with odometer and trip as this screen shows them. */
    val counters: CounterInputs = CounterInputs.NONE,
) {
    val live: Boolean get() = report != null && lastKnownAtMs == null
    val speedKmh: Double? get() = report?.takeIf { live }?.speedKmh

    val top: HandlebarTop get() = when {
        nav != null -> HandlebarTop.NAV
        connection == ConnectionStatus.CONNECTED -> HandlebarTop.NONE
        connection == ConnectionStatus.CONNECTING -> HandlebarTop.CONNECTING
        connection == ConnectionStatus.WAITING -> HandlebarTop.WAITING
        connection == ConnectionStatus.LOST -> HandlebarTop.LOST
        else -> HandlebarTop.OFFLINE
    }

    /** The light button; without a lamp switch ([lampSwitch]) it is left out. */
    val light: LightControl get() = LightControl.of(report?.takeIf { live }, lampSwitch)
}

/** Pure assembly from the bike's [Snapshot]: live while it is connected, otherwise its last known state (shown muted, U2). */
fun handlebarUiState(
    connection: ConnectionStatus,
    snapshot: Snapshot?,
    mapsNav: NavState?,
    testNav: NavState?,
    range: RangeEstimate?,
    home: HomeReach?,
    tiles: List<HandlebarTile>,
    now: LocalTime,
    lampSwitch: Boolean = true,
    driveError: CurrentDriveError? = null,
    counters: CounterInputs = CounterInputs.NONE,
): HandlebarUiState {
    val live = snapshot?.live == true
    val report = snapshot?.report
    return HandlebarUiState(
        connection = connection,
        report = report,
        counters = counters.copy(live = live, odoKm = report?.odoKm, tripKm = report?.tripKm),
        lastKnownAtMs = snapshot?.asOfMs,
        nav = testNav ?: mapsNav,
        range = range, home = home, tiles = tiles, now = now, lampSwitch = lampSwitch,
        driveError = driveError.takeIf { live },
    )
}

/** Unit shown next to a tile's number; resolved to text in the UI. */
sealed interface TileUnit {
    data class Of(val label: UnitLabel) : TileUnit
    data object Percent : TileUnit
    /** "von 5" next to the assist level. */
    data class OfGears(val gears: Int) : TileUnit
    data object None : TileUnit
}

/** What one tile shows. */
sealed interface TileReading {
    /** [stale] = from the last known state, not live. */
    data class Value(val text: String, val unit: TileUnit, val stale: Boolean = false) : TileReading
    data class Light(val on: Boolean) : TileReading
    /** No data (not connected, nothing learned yet): a dash plus the unit it would have. */
    data class Empty(val unit: TileUnit) : TileReading
}

/**
 * The value of [tile]. Speed, assist level and light are only meaningful live; battery and distances fall
 * back to the last known state; range and home come from the range feature (null = not learned yet).
 */
fun tileReading(tile: HandlebarTile, s: HandlebarUiState, fmt: UnitFormatter): TileReading {
    val r = s.report
    val stale = !s.live
    val liveReport = r?.takeIf { s.live }
    val distUnit = TileUnit.Of(fmt.distanceUnit)
    fun dist(km: Double, decimals: Int, stale: Boolean) = fmt.distance(km, decimals).let { TileReading.Value(it.value, TileUnit.Of(it.unit), stale) }
    return when (tile) {
        HandlebarTile.BATTERY -> r?.let { TileReading.Value(it.batteryPct.toString(), TileUnit.Percent, stale) } ?: TileReading.Empty(TileUnit.Percent)
        HandlebarTile.RANGE -> s.range?.let { dist(it.remainingKm, 0, stale) } ?: TileReading.Empty(distUnit)
        HandlebarTile.ASSIST -> liveReport?.let { TileReading.Value(it.gear.toString(), TileUnit.OfGears(it.gears)) } ?: TileReading.Empty(TileUnit.None)
        HandlebarTile.RIDE, HandlebarTile.TRIP, HandlebarTile.SINCE_CHARGE, HandlebarTile.SINCE_HOME, HandlebarTile.ODO -> {
            val c = tile.counter!!
            val v = Counters.value(c, s.counters)
            v.km?.let { dist(it, c.decimals(), v.stale) } ?: TileReading.Empty(distUnit)
        }
        HandlebarTile.SPEED -> liveReport?.let { fmt.speed(it.speedKmh).let { m -> TileReading.Value(m.value, TileUnit.Of(m.unit)) } }
            ?: TileReading.Empty(TileUnit.Of(fmt.speedUnit))
        HandlebarTile.CLOCK -> TileReading.Value(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(fmt.locale).format(s.now), TileUnit.None)
        HandlebarTile.LIGHT -> liveReport?.let { TileReading.Light(it.lightOn) } ?: TileReading.Empty(TileUnit.None)
        HandlebarTile.HOME -> s.home?.let { dist(it.distanceKm, 1, false) } ?: TileReading.Empty(distUnit)
    }
}
