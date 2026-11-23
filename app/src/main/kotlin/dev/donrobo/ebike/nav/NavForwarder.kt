package dev.donrobo.ebike.nav

import dev.donrobo.ebike.proto.NavSession
import dev.donrobo.ebike.proto.NavUpdate

/**
 * Where a turn hint comes from. Google Maps is the only app source for now (N2 is postponed); another
 * navigation app becomes a new entry plus its own writer next to [dev.donrobo.ebike.Repo.mapsNav].
 */
enum class NavOrigin { GOOGLE_MAPS, TEST }

/**
 * What the bike display shows (or showed last). [active] = a navigation session is open on the display;
 * false keeps the last hint for "Zuletzt gesendet". [sinceMs] = when this hint was first sent.
 */
data class DisplayNav(val state: NavState, val origin: NavOrigin, val sinceMs: Long, val active: Boolean)

/**
 * Decides, once per tick, which navigation frames go to the display (docs/PROTOCOL.md "Navigation payload":
 * start, updates, end). Pure apart from the [NavSession] sequence counter; one instance per BLE session.
 *
 *  - Source: a test maneuver wins over Google Maps; Maps only while forwarding is on (N6).
 *  - Always on: while there is a hint, the display shows the next turn, however far away it is; without
 *    navigation it shows its normal screen.
 *  - Dropouts (Q2): when the Maps notification disappears while forwarding is on, the last hint is kept
 *    (and re-sent) for [graceMs] before the session ends. Stopping a test or switching forwarding off
 *    ends it at once.
 */
class NavForwarder(private val graceMs: Long = GRACE_MS) {
    enum class Kind { START, UPDATE, END }

    /** One frame to send. [changed] = an update whose content differs from the previous one (for logging). */
    class Out(val kind: Kind, val frame: ByteArray, val update: NavUpdate? = null, val changed: Boolean = false)

    private val session = NavSession()
    private var lostAtMs: Long? = null

    /** The display's state after the last [tick]; null until something was sent. */
    var shown: DisplayNav? = null
        private set

    fun tick(test: NavState?, maps: NavState?, forward: Boolean, nowMs: Long): List<Out> {
        val hint = when {
            test != null -> test to NavOrigin.TEST
            forward && maps != null -> maps to NavOrigin.GOOGLE_MAPS
            else -> null
        }
        val cur = shown?.takeIf { it.active }
        if (hint == null) {
            if (cur == null) return emptyList()
            if (forward && cur.origin == NavOrigin.GOOGLE_MAPS) {
                val since = lostAtMs ?: nowMs.also { lostAtMs = it }
                if (nowMs - since < graceMs) return listOf(update(cur.state, cur.origin, nowMs))
            }
            return listOf(end())
        }
        lostAtMs = null
        val (state, origin) = hint
        val start = if (cur == null) listOf(Out(Kind.START, session.start())) else emptyList()
        return start + update(state, origin, nowMs)
    }

    private fun update(state: NavState, origin: NavOrigin, nowMs: Long): Out {
        val prev = shown?.takeIf { it.active }
        val changed = prev == null || prev.state != state || prev.origin != origin
        shown = DisplayNav(state, origin, if (changed) nowMs else prev!!.sinceMs, active = true)
        return Out(Kind.UPDATE, session.update(displayUpdate(state)), displayUpdate(state), changed)
    }

    private fun end(): Out {
        shown = shown?.copy(active = false)
        lostAtMs = null
        return Out(Kind.END, session.end())
    }

    companion object {
        const val GRACE_MS = 10_000L

        /**
         * The display shows the first distance in km (one decimal) next to the arrow and the second one in a
         * box below. Maps gives no distance for the step after the turn, so the box gets the same distance,
         * which the display can show in metres.
         */
        fun displayUpdate(s: NavState) = NavUpdate(distToTurnM = s.distM, icon = s.icon, distTurnToNextM = s.distM, remainingM = s.remainingM)
    }
}
