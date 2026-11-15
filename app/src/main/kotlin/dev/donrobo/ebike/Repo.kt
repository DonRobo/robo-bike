package dev.donrobo.ebike

import android.content.Context
import android.graphics.Bitmap
import dev.donrobo.ebike.clock.ClockCheck
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.nav.DisplayNav
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ConnState { OFF, SCANNING, CONNECTING, CONNECTED }

/** What the running [BikeService] offers to the rest of the app. Null in [Repo.bike] while not connected. */
interface BikeControl {
    /** Headlight on/off (0xA5 reg 166). Throws on rejection/timeout. */
    suspend fun setLight(on: Boolean)
    /** Display clock (see [dev.donrobo.ebike.clock.ClockSync]); byte order is unresolved, hence the flag. Reads back the result. */
    suspend fun setClock(bigEndian: Boolean): ClockCheck
    /**
     * Raw register read for diagnostics: asks for [len] bytes and returns the whole answer, which may be longer
     * (the display's own register map wins, e.g. 32 bytes for `0xA5:120`).
     */
    suspend fun readFull(target: Int, reg: Int, len: Int): ByteArray
}

/**
 * Raw view of the latest Google Maps notification, for the debug screen. [summary] = the few lines the
 * Diagnose screen shows collapsed; [shapeIcon]/[textIcon] = the classifier's and the text's reading.
 */
class NavDebug(
    val texts: List<String>,
    val icon: Bitmap?,
    val parsed: NavState?,
    val summary: List<String> = emptyList(),
    val shapeIcon: Icon? = null,
    val textIcon: Icon? = null,
)

/**
 * Process-wide state, like [dev.donrobo.ebike.location.Locations]: everyone reads the public flows, each flow has
 * exactly one writer, which publishes through the internal functions of its section below.
 */
object Repo {
    // --- link state: BikeService -------------------------------------------------------------------------------

    private val _running = MutableStateFlow(false)
    private val _conn = MutableStateFlow(ConnState.OFF)
    private val _linkLost = MutableStateFlow(false)
    private val _sessionBikeId = MutableStateFlow<String?>(null)
    private val _live = MutableStateFlow<Live?>(null)

    /** True while the service exists. */
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val conn: StateFlow<ConnState> = _conn.asStateFlow()
    /** A connection of the running service dropped and is not back yet (bike switched off or out of range). */
    val linkLost: StateFlow<Boolean> = _linkLost.asStateFlow()
    /** The bike of the current connect/session, null between sessions. */
    val sessionBikeId: StateFlow<String?> = _sessionBikeId.asStateFlow()
    /** The connected bike and its latest report; null unless CONNECTED (and a report arrived). Read via [snapshot]. */
    val live: StateFlow<Live?> = _live.asStateFlow()
    /** Commands to the connected bike; non-null only while CONNECTED. */
    @Volatile var bike: BikeControl? = null
        private set

    /** The link is up and belongs to [bikeId] (A2: several bikes, one link). Live data only counts for that bike. */
    fun linkedTo(bikeId: String): Flow<Boolean> =
        combine(conn, sessionBikeId) { c, session -> c == ConnState.CONNECTED && (session == null || session == bikeId) }.distinctUntilChanged()

    /** [bikeId]'s live report, null unless that bike is the connected one. */
    fun liveReport(bikeId: String?): BikeReport? = live.value?.takeIf { bikeId != null && it.bikeId == bikeId }?.report

    /** [bikeId] as shown now: live if it is the connected bike, else its last known state ([Snapshot.of]). */
    fun snapshot(settings: Settings, bikeId: String): Snapshot? = Snapshot.of(bikeId, live.value, settings.get(LastKnown.setting, bikeId))

    /** Every change of [snapshot]. */
    fun snapshotFlow(settings: Settings, bikeId: String): Flow<Snapshot?> =
        combine(live, settings.flow(LastKnown.setting, bikeId)) { l, lk -> Snapshot.of(bikeId, l, lk) }.distinctUntilChanged()

    internal fun serviceStarted() { _linkLost.value = false; _running.value = true }
    internal fun serviceStopped() { _linkLost.value = false; _running.value = false }
    internal fun scanning() { _conn.value = ConnState.SCANNING }
    internal fun connecting(bikeId: String?) { _sessionBikeId.value = bikeId; _conn.value = ConnState.CONNECTING }

    /** [control] is published before CONNECTED: whatever reacts to the bike being live can command it at once. */
    internal fun connected(control: BikeControl) {
        bike = control
        _conn.value = ConnState.CONNECTED
        _linkLost.value = false
    }

    /** An established connection ended; the service keeps trying (until [linkReset] / [serviceStopped]). */
    internal fun connectionLost() { _linkLost.value = true }

    /** Every reset of a session's link state, after the session itself has ended. */
    internal fun linkReset() {
        bike = null
        _conn.value = ConnState.OFF
        _sessionBikeId.value = null
    }

    internal fun publishLive(l: Live?) { _live.value = l }

    // --- navigation ----------------------------------------------------------------------------------------------

    private val _mapsNav = MutableStateFlow<NavState?>(null)
    private val _navDebug = MutableStateFlow<NavDebug?>(null)
    private val _testNav = MutableStateFlow<NavState?>(null)
    private val _displayNav = MutableStateFlow<DisplayNav?>(null)

    /** Google Maps' current maneuver; null = no navigation running. Writer: MapsListener (debug: the simulator). */
    val mapsNav: StateFlow<NavState?> = _mapsNav.asStateFlow()
    /** Writer: MapsListener. */
    val navDebug: StateFlow<NavDebug?> = _navDebug.asStateFlow()
    /** Fake maneuver from Diagnose; takes priority over [mapsNav]. Writer: [dev.donrobo.ebike.nav.TestManeuver]. */
    val testNav: StateFlow<NavState?> = _testNav.asStateFlow()
    /** The hint on the display / the last one sent. Writer: BikeService's session. */
    val displayNav: StateFlow<DisplayNav?> = _displayNav.asStateFlow()

    internal fun publishMapsNav(s: NavState?) { _mapsNav.value = s }
    internal fun publishNavDebug(d: NavDebug?) { _navDebug.value = d }
    internal fun publishTestNav(s: NavState?) { _testNav.value = s }
    internal fun publishDisplayNav(d: DisplayNav?) { _displayNav.value = d }

    // --- ride recording: RideRecorder ------------------------------------------------------------------------------

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** A ride of [bikeId] is being recorded now: its newest ride file is still growing. */
    fun recordingFor(bikeId: String): Flow<Boolean> =
        combine(recording, sessionBikeId) { rec, session -> rec && (session == null || session == bikeId) }.distinctUntilChanged()

    internal fun publishRecording(v: Boolean) { _recording.value = v }

    // --- light: LightAutomation ----------------------------------------------------------------------------------------

    private val _light = MutableStateFlow(LightStatus())
    val light: StateFlow<LightStatus> = _light.asStateFlow()

    internal fun publishLight(s: LightStatus) { _light.value = s }

    // --- log: anyone -------------------------------------------------------------------------------------------------

    private val appLog = AppLog()

    /** The newest log lines, oldest first. */
    val log: StateFlow<List<String>> get() = appLog.lines

    /** Called once per process (App.onCreate): keeps the log across process restarts for field debugging. */
    fun init(ctx: Context) {
        appLog.init(ctx)
        log("--- app process started ---")
    }

    /** From any thread; the file is written on the log's own thread ([AppLog]). */
    fun log(msg: String) = appLog.log(msg)
}
