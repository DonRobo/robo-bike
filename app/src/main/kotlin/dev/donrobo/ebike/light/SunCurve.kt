package dev.donrobo.ebike.light

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The sun-curve rule (L3): while the sun rises the light is on below [morningOffDeg], while it sets it is
 * on below [eveningOnDeg]. "Rising" = the elevation a minute later is higher. The automation and the
 * curve on the Licht screen both use exactly this function.
 */
fun curveWantsOn(elevNow: Double, elevLater: Double, morningOffDeg: Double, eveningOnDeg: Double): Boolean =
    if (elevLater > elevNow) elevNow < morningOffDeg else elevNow < eveningOnDeg

/** A switching point of today's curve. [minute] of the day it switches, null = it does not switch today. */
data class SwitchPoint(
    val minute: Int?,
    /** False if the sun never reaches the chosen elevation on this half of the day (then it switches at noon or not at all). */
    val reached: Boolean,
) {
    val time: LocalTime? get() = minute?.let { LocalTime.of((it / 60) % 24, it % 60) }
}

/** What the curve rule does today for one pair of elevations. */
class SunSchedule(val on: BooleanArray, val morning: SwitchPoint, val evening: SwitchPoint) {
    /** On-intervals as [start, end) minute pairs, for the tinted areas. */
    fun onIntervals(): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (m in on.indices) {
            if (on[m] && start < 0) start = m
            if (!on[m] && start >= 0) { out += start to m; start = -1 }
        }
        if (start >= 0) out += start to on.size - 1
        return out
    }
}

/**
 * Today's sun elevation at one place, sampled every minute of local wall-clock time: [elev] has 1441
 * entries, minute 0 = 00:00 to minute 1440 = 24:00. Pure apart from the sampling in [of].
 */
class SunDay(val elev: DoubleArray) {
    init { require(elev.size == MINUTES + 1) }

    /** Minute of the highest sun; splits the day into the morning (rising) and evening (setting) half. */
    val noon: Int = elev.indices.maxBy { elev[it] }
    /** Lowest sun before / after [noon]: the ends of the range a switching point can slide along. */
    val morningStart: Int = (0..noon).minBy { elev[it] }
    val eveningEnd: Int = (noon..MINUTES).minBy { elev[it] }
    val maxDeg: Double get() = elev[noon]
    val minDeg: Double get() = elev.min()

    private fun later(m: Int) = if (m < MINUTES) elev[m + 1] else elev[m] + (elev[m] - elev[m - 1])

    fun schedule(morningOffDeg: Double, eveningOnDeg: Double): SunSchedule {
        val on = BooleanArray(MINUTES + 1) { curveWantsOn(elev[it], later(it), morningOffDeg, eveningOnDeg) }
        val offAt = (1..MINUTES).firstOrNull { on[it - 1] && !on[it] }
        val onAt = (1..MINUTES).lastOrNull { !on[it - 1] && on[it] && (offAt == null || it > offAt) }
        val top = elev[noon]
        return SunSchedule(
            on,
            SwitchPoint(offAt, morningOffDeg <= top && morningOffDeg >= elev[morningStart]),
            SwitchPoint(onAt, eveningOnDeg <= top && eveningOnDeg >= elev[eveningEnd]),
        )
    }

    /** Where to draw a point: at its switching minute, else at the end of its half the elevation is nearest to. */
    fun morningHandle(s: SunSchedule, deg: Double): Int = s.morning.minute ?: if (deg > elev[noon]) noon else morningStart
    fun eveningHandle(s: SunSchedule, deg: Double): Int = s.evening.minute ?: if (deg > elev[noon]) noon else eveningEnd

    /**
     * The morning elevation that makes the light go off exactly at [minute] (clamped to the rising half and to
     * [LightSettings.MIN_DEG]..[LightSettings.MAX_DEG]). Used by dragging and by the ± steppers.
     */
    fun morningDegFor(minute: Int): Double = elev[minute.coerceIn(morningStart, noon)].coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)

    /** The evening elevation that makes the light go on exactly at [minute] (first minute below it). */
    fun eveningDegFor(minute: Int): Double {
        val m = minute.coerceIn((noon + 1).coerceAtMost(eveningEnd), eveningEnd)
        return elev[(m - 1).coerceAtLeast(0)].coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)
    }

    /** ± stepper: 5 minutes along the curve; 1° if the point does not switch today (time cannot be stepped). */
    fun stepMorning(deg: Double, eveningDeg: Double, forward: Boolean): Double {
        val m = schedule(deg, eveningDeg).morning.minute
        return if (m != null) morningDegFor(m + if (forward) STEP_MIN else -STEP_MIN)
        else (deg + if (forward) 1.0 else -1.0).coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)
    }

    /** Evening "later" = lower sun. */
    fun stepEvening(morningDeg: Double, deg: Double, forward: Boolean): Double {
        val m = schedule(morningDeg, deg).evening.minute
        return if (m != null) eveningDegFor(m + if (forward) STEP_MIN else -STEP_MIN)
        else (deg + if (forward) -1.0 else 1.0).coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)
    }

    companion object {
        const val MINUTES = 1440
        const val STEP_MIN = 5

        fun of(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): SunDay = SunDay(
            DoubleArray(MINUTES + 1) { m ->
                val t = if (m == MINUTES) date.plusDays(1).atStartOfDay(zone) else ZonedDateTime.of(date, LocalTime.of(m / 60, m % 60), zone)
                Sun.elevationDeg(t.toInstant().toEpochMilli(), lat, lon)
            },
        )

        /** Minute of the day of [t] in its zone. */
        fun minuteOf(t: ZonedDateTime): Int = t.hour * 60 + t.minute
    }
}
