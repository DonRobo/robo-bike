package dev.donrobo.ebike.errors

import dev.donrobo.ebike.Live
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.proto.ErrorCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** The drive error the display reports right now (report 1, byte 0), decoded, with when its episode began. */
data class CurrentDriveError(val raw: Int, val code: Int?, val sinceMs: Long) {
    /** In the Bafang table, so the alert and the banner have a specific text. */
    val known: Boolean get() = code != null && code in ErrorCodes.KNOWN
}

/** For screens (e.g. a banner on Start). Null = no error, or not connected. Single writer: [DriveErrorWatcher]. */
object DriveErrorState {
    internal val mutable = MutableStateFlow<CurrentDriveError?>(null)
    val current: StateFlow<CurrentDriveError?> = mutable
}

/** What the error log and the alert react to; one [Appeared] per episode, one [Ended] when it is over. */
sealed interface DriveErrorEvent {
    val bikeId: String

    /** A new episode on [bikeId]; [odoKm] = the odometer in that report. */
    data class Appeared(override val bikeId: String, val error: CurrentDriveError, val odoKm: Double) : DriveErrorEvent

    data class Ended(override val bikeId: String) : DriveErrorEvent
}

/** Subscribers of [DriveErrorWatcher]: the error log ([ErrorLogWriter]) and the alert. */
fun interface DriveErrorListener {
    fun onEvent(e: DriveErrorEvent, nowMs: Long)
}

/**
 * Pure: the one rule for drive-error episodes (W2 log, alert, banner), fed with [Repo.live]. An episode starts
 * when a non-zero byte appears that is not the open episode's code. It stays the same episode while that code is
 * reported, also across short gaps: a code that flickers 21 -> 0 -> 21 within [quietMs] counts once. It ends after
 * [quietMs] without the code, when another code appears, or when the link is lost (a new connection starts afresh).
 * [current] is what the display reports right now, so a banner disappears as soon as the byte goes back to 0.
 */
class DriveErrorTracker(private val quietMs: Long = 60_000) {
    private class Episode(val bikeId: String, val raw: Int, val startMs: Long, var lastSeenMs: Long)

    private var episode: Episode? = null
    var current: CurrentDriveError? = null; private set

    fun update(live: Live?, nowMs: Long): List<DriveErrorEvent> {
        val events = mutableListOf<DriveErrorEvent>()
        val open = episode
        if (open != null && (live == null || live.bikeId != open.bikeId)) end(events)
        val byte = live?.report?.errorRaw ?: 0
        if (live == null || byte == 0) {
            current = null
            episode?.let { if (nowMs - it.lastSeenMs > quietMs) end(events) }
            return events
        }
        val e = episode
        if (e != null && e.raw == byte && nowMs - e.lastSeenMs <= quietMs) {
            e.lastSeenMs = nowMs
            if (current == null) current = error(byte, e.startMs)
            return events
        }
        if (e != null) end(events)
        episode = Episode(live.bikeId, byte, nowMs, nowMs)
        val err = error(byte, nowMs)
        current = err
        events += DriveErrorEvent.Appeared(live.bikeId, err, live.report.odoKm)
        return events
    }

    private fun end(events: MutableList<DriveErrorEvent>) {
        episode?.let { events += DriveErrorEvent.Ended(it.bikeId) }
        episode = null
    }

    private fun error(raw: Int, sinceMs: Long) =
        CurrentDriveError(raw, ErrorCodes.currentFromReport(byteArrayOf(raw.toByte()))?.code, sinceMs)
}

/**
 * Runs [DriveErrorTracker] over [Repo.live] while the service runs: publishes [DriveErrorState] and hands every
 * episode event to [listeners]. Ticks let an episode end after its quiet time even while the reports do not change.
 */
class DriveErrorWatcher(private val listeners: List<DriveErrorListener>) {
    suspend fun run() {
        val tracker = DriveErrorTracker()
        val ticks = flow { while (true) { delay(TICK_MS); emit(Unit) } }
        fun feed(live: Live?) {
            val now = System.currentTimeMillis()
            val events = tracker.update(live, now)
            DriveErrorState.mutable.value = tracker.current
            events.forEach { e -> listeners.forEach { it.onEvent(e, now) } }
        }
        try {
            merge(Repo.live, ticks.map { Repo.live.value }).collect(::feed)
        } finally {
            // The service stops: nothing is known any more, so an open episode is over.
            runCatching { feed(null) }.onFailure { Repo.log("drive errors: ending the episode failed: $it") }
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
