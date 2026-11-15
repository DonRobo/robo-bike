package dev.donrobo.ebike.ui.start

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.bike.DisplayInfo
import dev.donrobo.ebike.counters.CounterInputs
import dev.donrobo.ebike.counters.CounterReading
import dev.donrobo.ebike.counters.Counters
import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.errors.CurrentDriveError
import dev.donrobo.ebike.light.lightCardStatus
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.maintenance.MaintenanceDue
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.range.FullRange
import dev.donrobo.ebike.range.HomeReach
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.settings.Bike

/** Where the navigation card's maneuver comes from. */
enum class NavSource { MAPS, MAPS_NOT_SENT, TEST }

/** Everything the Start screen shows; built by [startUiState], rendered by [StartContent]. */
data class StartUiState(
    val bike: Bike?,
    val bikes: List<Bike>,
    val connection: ConnectionStatus,
    /** Display model for "Verbunden mit EKD01"; null = unknown (generic text). */
    val displayModel: String?,
    /** Live report, or the last known one when not connected (then [lastKnownAtMs] is set). */
    val report: BikeReport?,
    val lastKnownAtMs: Long?,
    /** Why the light is on or off (L6); NOT_CONNECTED unless live. */
    val light: LightStatus,
    val range: RangeEstimate?,
    val home: HomeReach?,
    val nav: NavState?,
    val navSource: NavSource,
    val maintenance: MaintenanceDue?,
    /** Q4: the display lets the app switch the lamp (feature block); otherwise the card has no switch. */
    val canSwitchLight: Boolean = true,
    /**
     * Full-battery range and consumption, known as soon as a rate is learned, also while [range] (which needs
     * the battery level) is not: then the card shows these instead of "wird gelernt".
     */
    val rangeFull: FullRange? = range?.let { FullRange(it.fullKm, it.pctPerKm) },
    /** The drive error the display reports right now (only while connected); shown as a banner. */
    val driveError: CurrentDriveError? = null,
    /** The counter the rider chose for the distance tile (Einstellungen), and its value. */
    val counter: DistanceCounter = DistanceCounter.THIS_RIDE,
    val counterReading: CounterReading = CounterReading(null, true),
) {
    val live: Boolean get() = report != null && lastKnownAtMs == null
    private val liveReport: BikeReport? get() = report?.takeIf { live }
    /** The lamp is on; only known while live (a last known lamp state is not shown as current). */
    val lightOn: Boolean get() = LightControl.lampOn(liveReport)
    /** The card's switch; without a lamp switch ([canSwitchLight]) the card has none. */
    val lightControl: LightControl get() = LightControl.of(liveReport, canSwitchLight)
}

/** Pure assembly of [StartUiState] from the bike's [Snapshot]: live while it is connected, else its last known state (U2). */
fun startUiState(
    bike: Bike?,
    bikes: List<Bike>,
    connection: ConnectionStatus,
    display: DisplayInfo?,
    snapshot: Snapshot?,
    light: LightStatus,
    range: RangeEstimate?,
    home: HomeReach?,
    mapsNav: NavState?,
    testNav: NavState?,
    forwardNav: Boolean,
    maintenance: MaintenanceDue?,
    capabilities: DisplayCapabilities = DisplayCapabilities.UNKNOWN,
    rangeFull: FullRange? = range?.let { FullRange(it.fullKm, it.pctPerKm) },
    driveError: CurrentDriveError? = null,
    counter: DistanceCounter = DistanceCounter.THIS_RIDE,
    counters: CounterInputs = CounterInputs.NONE,
): StartUiState {
    val live = snapshot?.live == true
    val report = snapshot?.report
    return StartUiState(
        bike = bike, bikes = bikes, connection = connection,
        displayModel = display?.model,
        report = report,
        lastKnownAtMs = snapshot?.asOfMs,
        light = lightCardStatus(live, light),
        range = range, home = home,
        nav = testNav ?: mapsNav,
        navSource = when { testNav != null -> NavSource.TEST; forwardNav -> NavSource.MAPS; else -> NavSource.MAPS_NOT_SENT },
        maintenance = maintenance,
        canSwitchLight = capabilities.supports(DisplayFeature.LAMP_SWITCH),
        rangeFull = rangeFull,
        driveError = driveError.takeIf { live },
        counter = counter,
        // distances as this screen shows them (live or last known), the rest from the counter engine
        counterReading = Counters.value(counter, counters.copy(live = live, odoKm = report?.odoKm, tripKm = report?.tripKm)),
    )
}
