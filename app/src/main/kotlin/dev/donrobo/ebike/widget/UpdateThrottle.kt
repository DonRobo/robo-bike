package dev.donrobo.ebike.widget

/**
 * Rate limit for redrawing a surface (Q3). [urgent] changes (what the rider just did or must know at once:
 * connection, light) go out immediately; any other change at most every [minIntervalMs], and then the
 * newest value. Nothing is lost: a held value is delivered by [poll] once it is due.
 */
class UpdateThrottle<T : Any>(
    private val minIntervalMs: Long,
    private val urgent: (shown: T, next: T) -> Boolean,
) {
    private var shown: T? = null
    private var shownAtMs = 0L
    private var held: T? = null

    /** When the held value becomes due (for [poll]); null = nothing held. */
    val nextDueMs: Long? get() = held?.let { shownAtMs + minIntervalMs }

    /** A new value: returns it if it should be shown now, else null (it is held, or equals what is shown). */
    fun offer(value: T, nowMs: Long): T? {
        val s = shown
        if (s == value) { held = null; return null }
        if (s == null || urgent(s, value) || nowMs - shownAtMs >= minIntervalMs) return show(value, nowMs)
        held = value
        return null
    }

    /** The held value if it is due now, else null. */
    fun poll(nowMs: Long): T? {
        val h = held ?: return null
        return if (nowMs - shownAtMs >= minIntervalMs) show(h, nowMs) else null
    }

    private fun show(value: T, nowMs: Long): T {
        shown = value
        shownAtMs = nowMs
        held = null
        return value
    }
}

/** Widget: connection, light, lock and bike changes are urgent; battery, range and the as-of time can wait. */
fun glanceUrgent(a: BikeGlance, b: BikeGlance): Boolean =
    a.link != b.link || a.lightOn != b.lightOn || a.light != b.light || a.locked != b.locked ||
        a.bikeName != b.bikeName || (a.asOfMs == null) != (b.asOfMs == null)
