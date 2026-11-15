package dev.donrobo.ebike.light

import java.time.LocalTime

/** Why the light is on or off right now (L6). The order of [lightReason] decides which one wins. */
enum class LightReason {
    NOT_CONNECTED,
    /** Bike locked: BikeGo does not switch the lamp, neither do we. */
    LOCKED,
    /** Mode "Aus". */
    MODE_OFF,
    /** The last attempts to switch failed. */
    FAILED,
    /** The rider switched against the automation (bike button or app). */
    MANUAL,
    ALWAYS_ON,
    DARK,
    RAIN,
    DAY,
    /** AUTO without a position: the curve cannot say anything. */
    NO_POSITION,
}

/** The rain condition as the status line describes it. */
enum class RainNow { DISABLED, UNKNOWN, DRY, BELOW_LEVEL, WET }

fun rainNow(enabled: Boolean, level: RainLevel, p: Precipitation?): RainNow = when {
    !enabled -> RainNow.DISABLED
    p == null -> RainNow.UNKNOWN
    level.matches(p) -> RainNow.WET
    p.wet -> RainNow.BELOW_LEVEL
    else -> RainNow.DRY
}

/** Snapshot published by the automation (single writer: LightAutomation) and shown in the UI. */
data class LightStatus(
    val reason: LightReason = LightReason.NOT_CONNECTED,
    val rain: RainNow = RainNow.UNKNOWN,
    /** DARK: dark since this evening point. */
    val since: LocalTime? = null,
    /** DARK in the morning: until the morning point; DAY: until the evening point. */
    val until: LocalTime? = null,
)

/** The automation's status only describes a live connection; otherwise it may be stale. */
fun lightCardStatus(live: Boolean, status: LightStatus): LightStatus = if (live) status else LightStatus()

fun lightReason(input: LightInput, decider: LightDecider): LightReason = when {
    !input.connected -> LightReason.NOT_CONNECTED
    !input.unlocked -> LightReason.LOCKED
    input.mode == LightMode.OFF -> LightReason.MODE_OFF
    decider.failed -> LightReason.FAILED
    decider.manual -> LightReason.MANUAL
    input.mode == LightMode.ALWAYS_ON -> LightReason.ALWAYS_ON
    decider.target == true && input.curveOn == true -> LightReason.DARK
    decider.target == true -> LightReason.RAIN
    input.curveOn == null -> LightReason.NO_POSITION
    else -> LightReason.DAY
}

/** Since/until times of the curve for [reason] at [nowMinute] (see [LightStatus]). */
fun curveEdges(reason: LightReason, schedule: SunSchedule?, nowMinute: Int): Pair<LocalTime?, LocalTime?> {
    if (schedule == null) return null to null
    val morning = schedule.morning.minute
    val evening = schedule.evening.minute
    return when (reason) {
        LightReason.DARK -> when {
            evening != null && nowMinute >= evening -> schedule.evening.time to null
            morning != null && nowMinute < morning -> null to schedule.morning.time
            else -> null to null
        }
        LightReason.DAY -> null to schedule.evening.time?.takeIf { evening != null && nowMinute < evening }
        else -> null to null
    }
}
