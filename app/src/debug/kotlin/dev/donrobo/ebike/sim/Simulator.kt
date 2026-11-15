package dev.donrobo.ebike.sim

import android.content.Context
import android.content.SharedPreferences
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.ble.Ekd01Exception
import dev.donrobo.ebike.ble.LinkTransport
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.unhex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The simulated bike of debug builds: one [FakeDisplay] driven by a [SimEngine], reachable through
 * [LinkTransport]s that [SimulatorHook] hands to `BikeService` when the active bike's display is [MAC].
 *
 * Power: while [powered] the scenario runs (also while no phone is connected, as on the real bike) and a
 * connected link gets report 1 every [TICK_MS] (report 9 every second). While off, a connection attempt waits
 * like `connectGatt` to a switched-off bike and fails with "connect timeout" after [offConnectTimeoutMs].
 *
 * All access to the display goes through [lock]. The display's memory (odometer, trip, battery, error history)
 * is kept in its own preferences file across process restarts, like the real display keeps it across power cycles.
 */
object Simulator {
    /** The simulated display's address: locally administered, so it can never be a real display. */
    const val MAC = "02:00:00:00:51:4D"
    const val TICK_MS = 500L

    @Volatile var connectDelayMs = 800L
    @Volatile var replyDelayMs = 40L
    @Volatile var offConnectTimeoutMs = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val display = FakeDisplay(log = { Repo.log("sim display: $it") })
    private val engine = SimEngine(display)
    private val _powered = MutableStateFlow(false)
    val powered: StateFlow<Boolean> = _powered
    private var ticker: Job? = null
    private var link: SimTransport? = null
    private var prefs: SharedPreferences? = null
    private var ride: Scenario.Replay? = null

    /** Loads the bundled ride and the display's memory once per process. */
    fun init(ctx: Context) = synchronized(lock) {
        if (prefs != null) return@synchronized
        val p = ctx.applicationContext.getSharedPreferences("sim_display", Context.MODE_PRIVATE)
        prefs = p
        ride = runCatching {
            Scenario.Replay("ride", RideScript.parse(ctx.assets.open("sim/ride.csv").bufferedReader().use { it.readText() }))
        }.onFailure { Repo.log("sim: bundled ride unreadable: $it") }.getOrNull()
        display.odoKm = p.getFloat("odoKm", display.odoKm.toFloat()).toDouble()
        display.tripKm = p.getFloat("tripKm", display.tripKm.toFloat()).toDouble()
        display.batteryPct = p.getInt("batteryPct", display.batteryPct)
        p.getString("history", null)?.let { h -> display.history = h.unhex().copyOf(16) }
    }

    private fun save() {
        val p = prefs ?: return
        p.edit().putFloat("odoKm", display.odoKm.toFloat()).putFloat("tripKm", display.tripKm.toFloat())
            .putInt("batteryPct", display.batteryPct).putString("history", display.history.hex("")).apply()
    }

    /** Runs [block] on the display under the lock (manual values, errors, ...). */
    fun <T> edit(block: (FakeDisplay, SimEngine) -> T): T = synchronized(lock) { block(display, engine) }

    /** Scenario by name ("ride", "still"); null if unknown or the bundled ride is missing. */
    fun scenario(name: String): Scenario? = Scenario.byName(name) { ride ?: error("no bundled ride") }.takeIf {
        it !is Scenario.Replay || ride != null
    }

    fun play(s: Scenario, speedup: Double) {
        synchronized(lock) { engine.play(s, speedup) }
        Repo.log("sim: scenario ${engine.describe()}")
    }

    /** Bike switched on: the scenario clock starts, connections succeed. */
    fun powerOn() {
        synchronized(lock) {
            if (_powered.value) return
            _powered.value = true
            ticker = scope.launch { tickLoop() }
        }
        Repo.log("sim: display switched on (${engine.describe()})")
    }

    /** Bike switched off: the link drops (as when the bike goes out of range or off), the scenario stops. */
    fun powerOff() {
        val l = synchronized(lock) {
            if (!_powered.value) return
            _powered.value = false
            ticker?.cancel()
            ticker = null
            display.speedKmh = 0.0
            display.lightOn = false // the headlight goes off with the bike
            save()
            link
        }
        Repo.log("sim: display switched off")
        l?.drop("display switched off")
    }

    /** Abrupt link loss while the bike stays on (out of range); BikeService reconnects by itself. */
    fun dropLink(): Boolean {
        val l = synchronized(lock) { link } ?: return false
        l.drop("simulated link loss")
        return true
    }

    fun connected(): Boolean = synchronized(lock) { link != null }

    fun describe(): String = synchronized(lock) {
        "sim: ${if (_powered.value) "on" else "off"}, ${if (link != null) "phone connected" else "no phone"}, " +
            "scenario ${engine.describe()}; display: ${display.describe()}"
    }

    fun transport(): LinkTransport = SimTransport()

    private suspend fun tickLoop() {
        var last = System.nanoTime()
        var n = 0L
        while (scope.isActive) {
            delay(TICK_MS)
            val now = System.nanoTime()
            val dtMs = (now - last) / 1_000_000
            last = now
            n++
            val (finished, frames, target) = synchronized(lock) {
                val f = engine.tick(dtMs)
                val out = listOfNotNull(display.report1Frame(), if (n % 2 == 0L) display.report9Frame() else null)
                if (n % 60 == 0L) save()
                Triple(f, out, link)
            }
            frames.forEach { target?.push(it) }
            if (finished) Repo.log("sim: replay finished (${engine.describe()})")
            if (n % 120 == 0L) Repo.log(describe()) // once a minute
        }
    }

    /** One phone connection to the simulated display. */
    private class SimTransport : LinkTransport {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        private val closed = AtomicBoolean(false)
        private var pump: Job? = null
        @Volatile private var onClosed: () -> Unit = {}

        override suspend fun open(onData: (ByteArray) -> Unit, onClosed: () -> Unit) {
            this.onClosed = onClosed
            Repo.log("sim: connecting to the simulated display $MAC")
            // A switched-off display does not answer; connectGatt gives up after 20 s (GattTransport).
            withTimeoutOrNull(offConnectTimeoutMs) { powered.first { it } } ?: throw Ekd01Exception("connect timeout")
            delay(connectDelayMs)
            if (!powered.value || closed.get()) throw Ekd01Exception("connect timeout")
            pump = scope.launch { for (b in out) if (!closed.get()) onData(b) }
            val previous = synchronized(lock) { link.also { link = this } }
            previous?.drop("another connection") // the display serves one phone
            Repo.log("sim: link up")
        }

        override suspend fun write(frame: ByteArray) {
            if (closed.get()) throw Ekd01Exception("not connected")
            val replies = synchronized(lock) { display.receive(frame) }
            if (replies.isEmpty()) return
            scope.launch {
                delay(replyDelayMs) // the real display answers within ~50-90 ms
                replies.forEach { push(it) }
            }
        }

        fun push(frame: ByteArray) { if (!closed.get()) out.trySend(frame) }

        /** Closed by the phone (Ekd01Link.close): no onClosed callback. */
        override fun close() { if (detach()) Repo.log("sim: link closed by the app") }

        /** Lost from the display's side: the app learns it through onClosed, like a GATT disconnect. */
        fun drop(why: String) {
            if (!detach()) return
            Repo.log("sim: link lost ($why)")
            onClosed()
        }

        private fun detach(): Boolean {
            if (closed.getAndSet(true)) return false
            synchronized(lock) { if (link === this) link = null }
            pump?.cancel()
            out.close()
            return true
        }
    }
}
