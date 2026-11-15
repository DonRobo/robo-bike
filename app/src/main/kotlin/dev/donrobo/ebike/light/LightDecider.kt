package dev.donrobo.ebike.light

/** One evaluation's inputs. Null = unknown (no position yet, no weather data yet). */
data class LightInput(
    val connected: Boolean,
    /** BikeGo only sends the lamp command while the bike is unlocked. */
    val unlocked: Boolean,
    val lightOn: Boolean,
    val mode: LightMode,
    /** The sun curve wants the light on ([curveWantsOn]); null = no position. */
    val curveOn: Boolean?,
    /** Rain at or above the chosen level; false when the rain condition is switched off; null = no weather data. */
    val rainOn: Boolean?,
    val nowMs: Long,
)

/**
 * When to switch the headlight, in both directions (L2, L5). Transition-based so the rider is never fought:
 *
 * - The *target* is what the mode and conditions ask for: ALWAYS_ON = on; AUTO = on if the curve or rain says
 *   so, off only if every condition is known and says off (when in doubt the light stays as it is, the safe
 *   side); OFF = no target, the app leaves the light alone.
 * - A command is sent only when the target changes (curve point passed, rain starts/stops, settings or mode
 *   changed) or once at a new connection. A rider who switches by hand (bike button or app) keeps that state
 *   until the next such change; this is reported as [manual].
 * - Unknown inputs keep their last known value during a connection, so a network or GPS blip cannot fake a
 *   transition; at a new connection everything starts unknown.
 * - While the bike is locked a pending command waits (no attempts are used) and is sent after unlocking,
 *   unless the light already is in the wanted state by then.
 * - A failing command is retried [maxAttempts] times, then given up until the next transition ([failed]).
 */
class LightDecider(private val maxAttempts: Int = 3, private val echoMs: Long = 4_000) {
    private var connected = false
    private var mode: LightMode? = null
    private var curveOn: Boolean? = null
    private var rainOn: Boolean? = null
    private var asserted: Boolean? = null
    private var pending: Boolean? = null
    private var attemptsLeft = 0
    private var sentAtMs: Long? = null
    private var lightOn = false
    private var now = 0L

    /** The last command attempts failed; nothing more is tried until the next transition. */
    var failed = false; private set

    /** The current target (null = none: mode OFF, or AUTO with not enough known to decide). */
    var target: Boolean? = null; private set

    /** The light differs from what the automation last set, and not because the display has yet to report it. */
    val manual: Boolean get() = connected && asserted != null && pending == null && !failed && lightOn != asserted &&
        sentAtMs.let { it == null || now - it >= echoMs }

    /** Returns the state to send with setLight now (then report via [result]), or null. */
    fun update(input: LightInput): Boolean? {
        now = input.nowMs
        lightOn = input.lightOn
        if (!input.connected) {
            connected = false; curveOn = null; rainOn = null
            asserted = null; pending = null; target = null; failed = false; sentAtMs = null
            return null
        }
        if (!connected) { connected = true; curveOn = null; rainOn = null; asserted = null; failed = false }
        input.curveOn?.let { curveOn = it }
        input.rainOn?.let { rainOn = it }
        if (input.mode != mode) { mode = input.mode; asserted = null; failed = false }

        target = when (input.mode) {
            LightMode.OFF -> null
            LightMode.ALWAYS_ON -> true
            LightMode.AUTO -> when {
                curveOn == true || rainOn == true -> true
                curveOn == false && rainOn == false -> false
                else -> null
            }
        }
        val t = target
        if (t == null) {
            pending = null
            if (input.mode == LightMode.OFF) asserted = null
        } else if (t != asserted) {
            asserted = t
            pending = t.takeIf { it != input.lightOn }
            attemptsLeft = maxAttempts
            failed = false
            sentAtMs = null
        }
        if (pending != null && pending == input.lightOn) pending = null
        if (!input.unlocked) return null
        return pending?.takeIf { attemptsLeft > 0 }
    }

    /** Outcome of the setLight call requested by [update]. */
    fun result(ok: Boolean) {
        if (ok) { pending = null; sentAtMs = now; return }
        attemptsLeft--
        if (attemptsLeft <= 0) { pending = null; failed = true }
    }
}
