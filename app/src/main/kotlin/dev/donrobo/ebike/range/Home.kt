package dev.donrobo.ebike.range

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.ParkedPosition
import dev.donrobo.ebike.units.UnitSystem
import kotlin.math.roundToInt

/** Where the bike is, as far as the range feature knows. */
data class BikePosition(val point: GeoPoint, val atMs: Long, val source: Source) {
    enum class Source {
        /** A fresh fix while connected (the phone rides along). */
        LIVE,
        /** Where the connection ended last time: the bike has not been ridden with the app since. */
        PARKED,
    }
}

/** How tight the way home is. */
enum class HomeLevel {
    /** Remaining range covers the way home plus the reserve. */
    OK,
    /** It covers the way home, but less than the reserve is left. */
    TIGHT,
    /** It does not cover the way home. */
    SHORT,
}

/** "Komme ich heim?" (R5) for the screen; [HomeStatus.Known] also feeds [RangeState.home]. */
sealed interface HomeStatus {
    /** The rider switched the feature off. */
    data object Off : HomeStatus
    data object NoHome : HomeStatus
    /** No current position. [limit] tells why, when the location provider knows (e.g. NEEDS_BACKGROUND). */
    data class NoPosition(val limit: LocationLimit?) : HomeStatus
    /** Position known but no remaining range: nothing learned yet, or ([learned]) the battery level is unknown. */
    data class NoRange(val distanceKm: Double, val learned: Boolean = false) : HomeStatus
    data class Known(val distanceKm: Double, val remainingKm: Double, val reserveKm: Double, val level: HomeLevel, val atHome: Boolean = false) : HomeStatus {
        /** Range left after getting home ("es bleiben 60 km übrig"); negative = not enough. */
        val leftoverKm: Double get() = remainingKm - distanceKm
    }
}

/** Pure decisions of "Komme ich heim?" (unit-tested). */
object HomeCalc {
    /** A live fix older than this is not "where I am now" (occasional fixes come every 10 min while connected). */
    const val MAX_FIX_AGE_MS = 20 * 60_000L

    /** Closer to home than this (straight line) counts as at home: the way-home check says nothing there. */
    const val AT_HOME_M = 200.0

    fun atHome(from: GeoPoint, home: GeoPoint): Boolean = from.distanceM(home) <= AT_HOME_M

    /**
     * Connected: only a fresh live fix (a stale one would give a stale answer shown as current).
     * Not connected: the bike stands where the last connection ended, so the parked position is right
     * however old it is; without one, a fresh phone fix.
     */
    fun position(connected: Boolean, live: LocationFix?, parked: ParkedPosition?, nowMs: Long): BikePosition? {
        // A fix newer than [nowMs] is fresh too: the caller's clock ticks once a minute, GPS fixes arrive every second.
        val fresh = live?.takeIf { it.ageMs(nowMs) <= MAX_FIX_AGE_MS }?.let { BikePosition(it.point, it.timeMs, BikePosition.Source.LIVE) }
        if (connected) return fresh
        return parked?.fix?.let { BikePosition(it.point, it.timeMs.takeIf { t -> t > 0 } ?: parked.atMs, BikePosition.Source.PARKED) } ?: fresh
    }

    /** Road distance home: straight line times the [detour] factor (R9). */
    fun distanceKm(from: GeoPoint, home: GeoPoint, detour: Double): Double = from.distanceM(home) / 1000 * detour

    fun level(remainingKm: Double, distanceKm: Double, reserveKm: Double): HomeLevel = when {
        remainingKm < distanceKm -> HomeLevel.SHORT
        remainingKm < distanceKm + reserveKm -> HomeLevel.TIGHT
        else -> HomeLevel.OK
    }

    fun status(
        enabled: Boolean, home: GeoPoint?, position: BikePosition?, limit: LocationLimit?,
        estimate: RangeEstimate?, reserveKm: Double, detour: Double, learned: Boolean = estimate != null,
    ): HomeStatus {
        if (!enabled) return HomeStatus.Off
        if (home == null) return HomeStatus.NoHome
        if (position == null) return HomeStatus.NoPosition(limit)
        val d = distanceKm(position.point, home, detour)
        if (estimate == null) return HomeStatus.NoRange(d, learned)
        return HomeStatus.Known(d, estimate.remainingKm, reserveKm, level(estimate.remainingKm, d, reserveKm), atHome(position.point, home))
    }

    /** What other screens get ([RangeState.home]): only a real, current answer. */
    /** The way-home result for Start, widget and Lenkermodus; none at home ("0,0 km bis nach Hause" says nothing). */
    fun reach(s: HomeStatus): HomeReach? = (s as? HomeStatus.Known)?.takeIf { !it.atHome }?.let { HomeReach(it.distanceKm, it.level == HomeLevel.OK) }

    /** Reserve stepper bounds, in the rider's unit. */
    const val RESERVE_MAX_UNITS = 50

    /**
     * Next reserve in km after one −/+ step ([delta] = ±1) of one whole unit (km or mi) in the rider's unit
     * system, so 10 km shown as "6 mi" steps to exactly 5 or 7 mi.
     */
    fun stepReserve(reserveKm: Double, delta: Int, system: UnitSystem): Double {
        val perUnit = system.kmPerUnit
        val units = ((reserveKm / perUnit).roundToInt() + delta).coerceIn(0, RESERVE_MAX_UNITS)
        return units * perUnit
    }
}

/**
 * Whether the R5 warning condition "Der Akku wird knapp für den Heimweg" holds right now; evaluated on every
 * status change while riding. De-duplication (one alert per episode, quiet time, the rider's on/off setting)
 * belongs to [dev.donrobo.ebike.notify.Alerts], so this only answers the question: the episode ends when the
 * situation recovered (closer to home, more reserve) or the bike was charged, because then it no longer holds.
 */
object HomeWarning {
    enum class Condition {
        /** Remaining range is below the way home plus the reserve: show. */
        HOLDS,
        /** Covered again, or the feature was switched off / no home is set: resolve. */
        CLEAR,
        /** No current answer (no position or nothing learned yet): leave whatever is shown as it is. */
        UNKNOWN,
    }

    fun condition(status: HomeStatus): Condition = when (status) {
        is HomeStatus.Known -> if (status.level == HomeLevel.OK) Condition.CLEAR else Condition.HOLDS
        HomeStatus.Off, HomeStatus.NoHome -> Condition.CLEAR
        is HomeStatus.NoPosition, is HomeStatus.NoRange -> Condition.UNKNOWN
    }
}
