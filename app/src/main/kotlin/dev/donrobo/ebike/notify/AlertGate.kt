package dev.donrobo.ebike.notify

/**
 * What was last alerted for one kind and bike, persisted so a process restart does not alert again.
 * [key] identifies the episode (see [Alert.key]); [atMs] is when it was alerted, or resolved if not [active].
 */
data class AlertMemory(val key: String, val active: Boolean, val atMs: Long, val contentHash: Int) {
    fun encode(): String = "${if (active) 1 else 0};$atMs;$contentHash;$key"

    companion object {
        fun decode(s: String): AlertMemory? = runCatching {
            val f = s.split(';', limit = 4)
            if (f.size != 4) return null
            AlertMemory(f[3], f[0] == "1", f[1].toLong(), f[2].toInt())
        }.getOrNull()
    }
}

enum class AlertAction {
    /** New episode: post with sound/vibration as the channel says. */
    ALERT,
    /** Same episode, the text changed, or it came back shortly after it ended: post, but silently. */
    UPDATE_SILENTLY,
    /** Same episode, same text: nothing to do (also when the rider dismissed it: it stays dismissed). */
    NONE,
}

/**
 * De-duplication of alerts (pure). Callers report their condition as often as they like (every report is
 * fine); the rider is alerted once per episode:
 *
 * - [onShow] with the same key as the active episode never alerts again; changed text updates silently.
 * - [onResolve] ends the episode. If the same key comes back within the kind's quiet time (a value
 *   flapping around a threshold, a sensor error coming and going), it is shown again silently.
 * - A different key (another maintenance item, another error code, the next ride) is a new episode.
 */
object AlertGate {
    fun onShow(mem: AlertMemory?, key: String, contentHash: Int, nowMs: Long, quietMs: Long): Pair<AlertAction, AlertMemory?> {
        if (mem != null && mem.key == key) {
            if (mem.active) {
                return if (mem.contentHash == contentHash) AlertAction.NONE to mem
                else AlertAction.UPDATE_SILENTLY to mem.copy(contentHash = contentHash)
            }
            if (nowMs - mem.atMs < quietMs) return AlertAction.UPDATE_SILENTLY to AlertMemory(key, true, nowMs, contentHash)
        }
        return AlertAction.ALERT to AlertMemory(key, true, nowMs, contentHash)
    }

    /** Returns the memory after the condition ended (unchanged if nothing was active). */
    fun onResolve(mem: AlertMemory?, nowMs: Long): AlertMemory? =
        if (mem?.active == true) mem.copy(active = false, atMs = nowMs) else mem
}
