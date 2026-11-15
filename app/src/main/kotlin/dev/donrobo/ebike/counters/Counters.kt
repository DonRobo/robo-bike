package dev.donrobo.ebike.counters

import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.battery.BatteryReadings
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.range.HomeCalc
import dev.donrobo.ebike.ride.OpenRide
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import java.util.Locale

/**
 * The distances the app can show. [TOTAL] and [DISPLAY_TRIP] are the display's own counters; the others are
 * the app's, each the odometer now minus the odometer at an event ([Counters.value]). Stored by name: never
 * rename a constant.
 */
enum class DistanceCounter {
    /** The current ride as the ride log defines it; between rides the most recent ride (shown as not live). */
    THIS_RIDE,
    /** The display's trip counter; only reset by hand on the display. */
    DISPLAY_TRIP,
    /** Since the battery was last seen fully charged ([FullCharge]). */
    SINCE_CHARGE,
    /** Since the rider last set off from home ([HomeDeparture]). */
    SINCE_HOME,
    /** The odometer. */
    TOTAL,
}

object CounterSettings {
    /** Counter of the Start tile that used to be the display trip. Global: a choice about this phone's screens. */
    val start = Setting.enum("counters.start", DistanceCounter.THIS_RIDE)

    /**
     * Counter shown on the home-screen widget. Default "since full charge": the widget is about the battery,
     * and how far this charge has carried the bike belongs next to it.
     */
    val widget = Setting.enum("counters.widget", DistanceCounter.SINCE_CHARGE)

    /** Per bike: odometer (km) when the battery was last seen fully charged; null = never seen. */
    val fullChargeOdo = odo("counters.fullChargeOdo")

    /** Per bike: odometer (km) at the start of the last ride that set off from home; null = never seen. */
    val homeDepartureOdo = odo("counters.homeDepartureOdo")

    private fun odo(key: String) = Setting.mapped<Double?>(
        key, null, Scope.PER_BIKE, { it.toDoubleOrNull() }, { v -> v?.let { String.format(Locale.ROOT, "%.2f", it) } },
    )
}

/**
 * Everything the counters are computed from, for one bike. [odoKm]/[tripKm] are live while [live], else the
 * bike's last known state. [openRide] = the ride being recorded for this bike (its start odometer comes from
 * the recorder); [lastRideKm] = distance of the newest ride in the log. The event odometers are the stored
 * [CounterSettings] values.
 */
data class CounterInputs(
    val live: Boolean,
    val odoKm: Double?,
    val tripKm: Double?,
    val openRide: OpenRide?,
    val lastRideKm: Double?,
    val fullChargeOdoKm: Double?,
    val homeDepartureOdoKm: Double?,
) {
    companion object {
        val NONE = CounterInputs(false, null, null, null, null, null, null)
    }
}

/** One counter's value; [km] null = not known (no data yet, event never seen). [stale] = not a live value. */
data class CounterReading(val km: Double?, val stale: Boolean)

/** The one place for what each counter shows (pure, unit-tested). */
object Counters {
    fun value(c: DistanceCounter, i: CounterInputs): CounterReading {
        val stale = !i.live
        return when (c) {
            DistanceCounter.TOTAL -> CounterReading(i.odoKm, stale)
            DistanceCounter.DISPLAY_TRIP -> CounterReading(i.tripKm, stale)
            DistanceCounter.SINCE_CHARGE -> CounterReading(since(i.odoKm, i.fullChargeOdoKm), stale)
            DistanceCounter.SINCE_HOME -> CounterReading(since(i.odoKm, i.homeDepartureOdoKm), stale)
            DistanceCounter.THIS_RIDE -> {
                val open = i.openRide
                if (open != null && i.odoKm != null) CounterReading(since(i.odoKm, open.startOdoKm), stale)
                else CounterReading(i.lastRideKm, true) // between rides: the last ride, never shown as live
            }
        }
    }

    /**
     * Odometer difference. Null without both values, and when the odometer is below the event's (display
     * replaced or reset): an old event of another odometer says nothing about this one.
     */
    fun since(odoKm: Double?, eventOdoKm: Double?): Double? {
        if (odoKm == null || eventOdoKm == null) return null
        val d = odoKm - eventOdoKm
        return if (d < -0.005) null else d.coerceAtLeast(0.0)
    }
}

/**
 * When the battery counts as fully charged. The app only sees the battery while connected, and an e-bike is
 * charged while it is switched off, so the charge itself is never observed; what is observed is its result at
 * the start of a connection. The rule:
 *
 * A connection's **first plausible battery reading** is at the display's maximum ([FULL_PCT]) **and** the
 * bike's last known state from before this connection either does not exist (first connection ever; the
 * battery is full now), has no plausible battery level, or shows a lower level and is at least [MIN_OFF_MS]
 * old (the bike was off long enough to be charged).
 *
 * Why only the first reading: the percent is derived from the pack voltage; a full pack can flicker to 100
 * while riding, after sag recovery, or right after a connection drop and reconnect in the middle of a ride.
 * None of those passes: readings after the first never count, and a reconnect within [MIN_OFF_MS] does not
 * either. A battery that still reads 100 after a short ride ("last known 100") is not a new charge, so those
 * kilometres are kept.
 *
 * Known limit: a last known level a few points under 100 only because of sag at the end of a ride, followed by
 * a reconnect at 100 after half an hour without charging, counts as a charge. The pack was effectively full
 * then, so at most the few kilometres since the real charge are lost.
 */
object FullCharge {
    const val FULL_PCT = 100
    const val MIN_OFF_MS = 30 * 60_000L

    fun detected(before: LastKnown?, sessionStartMs: Long, firstPct: Int): Boolean {
        if (firstPct < FULL_PCT) return false
        if (before == null || !BatteryReadings.plausible(before.report.batteryPct)) return true
        if (before.report.batteryPct >= FULL_PCT) return false
        return sessionStartMs - before.atMs >= MIN_OFF_MS
    }
}

/**
 * When a ride counts as setting off from home ("Seit daheim" restarts at its start odometer). A departure is a
 * ride (as the ride log defines it) whose **start position** is at home as the range feature defines it
 * ([HomeCalc.atHome]: within [HomeCalc.AT_HOME_M] straight line).
 *
 * The start position is, in this order:
 * 1. a phone fix taken in the [STANDING_MS] before the ride started: a ride only starts after at least that
 *    long without movement (or with the bike switched off), so the bike stood where the fix was taken;
 * 2. for the first ride of a connection, the bike's parked position (where its previous connection ended):
 *    a switched-off bike stays where it was parked;
 * 3. the first fix during the ride while it is at most [DECIDE_WITHIN_KM] long; the radius then grows by the
 *    distance ridden so far (the bike may already have left the home radius when the fix arrives).
 *
 * Without any of these (no location permission, location off, no home set) the ride is no departure and the
 * counter keeps counting from the last known departure; it is never reset on a guess.
 */
object HomeDeparture {
    /** The ride log's end-of-ride gap ([dev.donrobo.ebike.ride.RideTracker]). */
    const val STANDING_MS = 5 * 60_000L
    const val DECIDE_WITHIN_KM = 0.5

    enum class Decision { DEPARTURE, NO_DEPARTURE, UNDECIDED }

    /**
     * [fix] = newest phone fix, [parked] = parked fix (only pass it for the first ride of a connection),
     * [riddenKm] = distance since the ride started.
     */
    fun decide(home: GeoPoint?, rideStartMs: Long, riddenKm: Double, fix: LocationFix?, parked: LocationFix?): Decision {
        if (home == null) return Decision.NO_DEPARTURE
        fun result(departed: Boolean) = if (departed) Decision.DEPARTURE else Decision.NO_DEPARTURE
        val ridden = riddenKm.coerceAtLeast(0.0)
        if (fix != null && fix.timeMs in (rideStartMs - STANDING_MS)..rideStartMs) return result(HomeCalc.atHome(fix.point, home))
        if (parked != null) return result(HomeCalc.atHome(parked.point, home))
        if (ridden > DECIDE_WITHIN_KM) return Decision.NO_DEPARTURE
        if (fix != null && fix.timeMs > rideStartMs) return result(fix.point.distanceM(home) <= HomeCalc.AT_HOME_M + ridden * 1000)
        return Decision.UNDECIDED
    }
}
