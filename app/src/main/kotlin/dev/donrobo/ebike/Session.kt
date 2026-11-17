package dev.donrobo.ebike

import android.content.Context
import dev.donrobo.ebike.ble.Ekd01Link
import dev.donrobo.ebike.ble.LinkTransport
import dev.donrobo.ebike.bike.DisplayInfoStore
import dev.donrobo.ebike.clock.ClockCheck
import dev.donrobo.ebike.clock.ClockSettings
import dev.donrobo.ebike.clock.ClockState
import dev.donrobo.ebike.clock.ClockSync
import dev.donrobo.ebike.clock.RegisterIo
import dev.donrobo.ebike.counters.CounterRecorder
import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.display.DisplayCapabilitiesStore
import dev.donrobo.ebike.errors.ErrorLogWriter
import dev.donrobo.ebike.location.LocationProvider
import dev.donrobo.ebike.nav.NavForwarder
import dev.donrobo.ebike.nav.NavSettings
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.MeterConfig
import dev.donrobo.ebike.proto.cString
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * One connection to the active bike's display, from connecting to the link's end: authentication, the setup reads
 * (info, feature block, clock, error register), the navigation loop, and the bike's reports (published as
 * [Repo.live], remembered as its last known state and fed to the counters). Writes the session's part of the link
 * state in [Repo]; [BikeService] resets it after [run] returned.
 */
internal class Session(private val ctx: Context, private val transport: LinkTransport, private val location: LocationProvider) {
    private val settings = ctx.settings
    @Volatile private var link: Ekd01Link? = null

    /** Releases the display at once (service destroyed); [run] then ends. Safe to call more than once. */
    fun close() { link?.close() }

    suspend fun run() = coroutineScope {
        // The bike this session belongs to: reports are remembered as its last known state (U2).
        val bikeId = ctx.bikes.active()?.id
        Repo.connecting(bikeId)
        val lastKnown = bikeId?.let { LastKnownWriter(settings, it) }
        val counters = bikeId?.let { CounterRecorder(ctx, it) } // before the first report
        val published = LivePublisher(bikeId)
        // Reports arrive on the GATT callback thread: only the publish happens there. Persisting them (last known
        // state, counters: settings and log writes) runs on one coroutine of this session, in order.
        val reports = Channel<TimedReport>(Channel.UNLIMITED)
        launch(Dispatchers.IO) { persist(reports, lastKnown, counters) }
        val l = Ekd01Link(transport) { reg, data ->
            if (reg == 1) {
                val r = BikeReport.decode(data)
                published.onReport(r)
                reports.trySend(TimedReport(r, System.currentTimeMillis()))
            }
        }
        link = l
        var wasConnected = false
        try {
            l.connect()
            // Control first, then CONNECTED, then the live report: whatever reacts to the bike being live (light
            // automation, switches) can command it at once. The link answers requests to different registers
            // concurrently, so the setup reads below may run alongside a light command.
            val control = Control(l)
            Repo.connected(control)
            published.connected()
            wasConnected = true
            setup(l, control, bikeId)
            val nav = launch { navLoop(l) }
            l.disconnected.await()
            nav.cancelAndJoin()
        } finally {
            published.close() // no report of this session is published after this point
            reports.close() // nor persisted: persist() drains what came before, then writes the last known state
            l.close()
            if (wasConnected) Repo.connectionLost()
            if (wasConnected && bikeId != null) location.onDisconnected(bikeId, System.currentTimeMillis())
        }
    }

    private class TimedReport(val report: BikeReport, val atMs: Long)

    private fun persistOne(r: TimedReport, lastKnown: LastKnownWriter?, counters: CounterRecorder?) {
        try {
            lastKnown?.record(r.report, r.atMs)
            counters?.onReport(r.report, r.atMs)
        } catch (e: Exception) {
            Repo.log("report handler: $e")
        }
    }

    /** The only user of [lastKnown] and [counters], so neither needs locking. */
    private suspend fun persist(reports: ReceiveChannel<TimedReport>, lastKnown: LastKnownWriter?, counters: CounterRecorder?) {
        try {
            for (r in reports) persistOne(r, lastKnown, counters)
        } finally {
            // Also when cancelled (service stopped): what is still queued counts, so the stored state is the last
            // report received.
            while (true) persistOne(reports.tryReceive().getOrNull() ?: break, lastKnown, counters)
            lastKnown?.flush()
        }
    }

    // --- setup reads, once per connection ---------------------------------------------------------------------------

    private suspend fun setup(l: Ekd01Link, control: Control, bikeId: String?) {
        if (bikeId != null) readInfo(l, bikeId)
        if (bikeId != null) readCapabilities(l, bikeId)
        autoClock(control, bikeId)
        if (bikeId != null) readErrorRegister(l, bikeId)
    }

    /** W5: the display's device info, kept per bike in [DisplayInfoStore] (also shown while the bike is off). */
    private suspend fun readInfo(l: Ekd01Link, bikeId: String) {
        val regs = listOf(
            Triple("hardware", Dev.METER, 0 to 24), Triple("software", Dev.METER, 24 to 24),
            Triple("serial", Dev.METER, 72 to 16), Triple("controller", Dev.CONTROLLER, 24 to 24),
        )
        val info = mutableMapOf<String, String>()
        for ((key, target, r) in regs) {
            try {
                info[key] = cString(l.read(target, r.first, r.second))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Repo.log("info $key: ${e.message}")
            }
        }
        DisplayInfoStore.store(settings, bikeId, info)
    }

    /** Q4: the display's feature block (0xF1 reg 1), stored per bike; read once per connection like BikeGo does. */
    private suspend fun readCapabilities(l: Ekd01Link, bikeId: String) {
        try {
            val raw = l.read(Dev.NAV, 1, MeterConfig.SIZE)
            val ok = DisplayCapabilitiesStore.store(settings, bikeId, raw)
            Repo.log("feature block ${raw.hex()}: ${if (ok) MeterConfig.decode(raw)?.features?.joinToString() else "too short, ignored"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.log("feature block: ${e.message}")
        }
    }

    /** E7: set the display clock once per connection, if enabled; the result is logged and kept in [ClockState]. */
    private suspend fun autoClock(control: Control, bikeId: String?) {
        val caps = bikeId?.let { DisplayCapabilitiesStore.get(settings, it) } ?: DisplayCapabilities.UNKNOWN
        if (!ClockSettings.autoWanted(settings[ClockSettings.auto], caps)) {
            if (settings[ClockSettings.auto]) Repo.log("clock: not set, the display reports no clock (DATE_TIME)")
            return
        }
        try {
            control.syncClock(settings[ClockSettings.bigEndian], automatic = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.log("clock: automatic sync failed: ${e.message}")
        }
    }

    /** Error register 0xA5:120, once per connection: errors that happened while the app was not connected. */
    private suspend fun readErrorRegister(l: Ekd01Link, bikeId: String) {
        try {
            ErrorLogWriter(ctx).onRegister(bikeId, l.read(ErrorCodes.HISTORY_TARGET, ErrorCodes.HISTORY_REG, ErrorCodes.HISTORY_LEN), System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.log("error register: ${e.message}")
        }
    }

    // --- navigation ---------------------------------------------------------------------------------------------------

    /** Once a second: [NavForwarder] decides the frames; [Repo.displayNav] mirrors what the display shows. */
    private suspend fun navLoop(l: Ekd01Link) = coroutineScope {
        val fwd = NavForwarder()
        try {
            while (isActive) {
                val out = fwd.tick(
                    Repo.testNav.value, Repo.mapsNav.value, settings[NavSettings.forward],
                    System.currentTimeMillis(),
                )
                try {
                    for (o in out) {
                        when (o.kind) {
                            NavForwarder.Kind.START -> Repo.log("nav: start (${fwd.shown?.origin})")
                            NavForwarder.Kind.END -> Repo.log("nav: end")
                            NavForwarder.Kind.UPDATE -> {}
                        }
                        l.send(o.frame, log = o.kind != NavForwarder.Kind.UPDATE || o.changed) // repeated identical updates are not logged
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Repo.log("nav send failed: ${e.message}")
                }
                Repo.publishDisplayNav(fwd.shown)
                delay(1_000)
            }
        } finally {
            // The link is gone: whatever the display showed is no longer ours to update.
            Repo.publishDisplayNav(fwd.shown?.copy(active = false))
        }
    }

    // --- what the session offers and publishes ----------------------------------------------------------------------

    /** [Repo.bike] of this session; also the [RegisterIo] the clock sync talks through. */
    private class Control(private val l: Ekd01Link) : BikeControl, RegisterIo {
        // switchLamp is a 2-byte register: [state, 0] (MeterManager.switchLamp in the app)
        override suspend fun setLight(on: Boolean) = l.write(Dev.METER, 166, byteArrayOf(if (on) 1 else 0, 0))
        override suspend fun setClock(bigEndian: Boolean): ClockCheck = syncClock(bigEndian, automatic = false)

        /** [automatic] = the once-per-connection sync (E7), as opposed to the rider's button. */
        suspend fun syncClock(bigEndian: Boolean, automatic: Boolean): ClockCheck {
            val now = System.currentTimeMillis()
            val offsetS = TimeZone.getDefault().getOffset(now) / 1000
            return ClockSync.sync(this, now, offsetS, bigEndian, automatic, Repo::log).also { ClockState.last.value = it }
        }

        override suspend fun readFull(target: Int, reg: Int, len: Int): ByteArray = l.readFull(target, reg, len)
        override suspend fun read(target: Int, reg: Int, len: Int): ByteArray = l.read(target, reg, len)
        override suspend fun write(target: Int, reg: Int, data: ByteArray) = l.write(target, reg, data)
    }

    /**
     * [Repo.live] for one session: published only between [connected] and [close], so it is never set while not
     * CONNECTED, and a report arriving late on the GATT thread cannot bring it back after the session ended.
     * A session without a bike (none set up) publishes nothing: live data always belongs to a bike.
     */
    private class LivePublisher(private val bikeId: String?) {
        private var latest: BikeReport? = null
        private var open = false
        private var closed = false

        @Synchronized fun onReport(r: BikeReport) {
            latest = r
            if (open) publish(r)
        }

        @Synchronized fun connected() {
            if (closed) return
            open = true
            latest?.let(::publish)
        }

        @Synchronized fun close() {
            open = false
            closed = true
            Repo.publishLive(null)
        }

        private fun publish(r: BikeReport) { bikeId?.let { Repo.publishLive(Live(it, r)) } }
    }
}
