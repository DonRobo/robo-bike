package dev.donrobo.ebike.counters

import android.content.Context
import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.battery.BatteryReadings
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.range.RangeSettings
import dev.donrobo.ebike.ride.OpenRide
import dev.donrobo.ebike.ride.RideRecorder
import dev.donrobo.ebike.settings.settings

/**
 * Event detection for one connection session of one bike (pure state machine, unit-tested): a full charge
 * ([FullCharge], once, at the first plausible battery reading) and departures from home ([HomeDeparture],
 * once per ride). [before] = the bike's last known state from before this session, [parked] = its parked fix
 * at session start.
 */
class CounterTracker(
    private val bikeId: String,
    private val before: LastKnown?,
    private val sessionStartMs: Long,
    private val parked: LocationFix?,
) {
    sealed interface Event {
        data class FullyCharged(val odoKm: Double) : Event
        data class LeftHome(val rideStartOdoKm: Double) : Event
    }

    private var batteryChecked = false
    private var decidedRideMs: Long? = null
    private var ridesSeen = 0

    fun onReport(r: BikeReport, nowMs: Long, openRide: OpenRide?, fix: LocationFix?, home: GeoPoint?): List<Event> {
        val out = mutableListOf<Event>()
        if (!batteryChecked && BatteryReadings.plausible(r.batteryPct)) {
            batteryChecked = true
            if (FullCharge.detected(before, sessionStartMs, r.batteryPct)) out += Event.FullyCharged(r.odoKm)
        }
        val ride = openRide?.takeIf { it.bikeId == bikeId }
        if (ride != null && ride.startMs != decidedRideMs) {
            val firstOfSession = ridesSeen == 0 && ride.startMs >= sessionStartMs
            val d = HomeDeparture.decide(home, ride.startMs, r.odoKm - ride.startOdoKm, fix, parked.takeIf { firstOfSession })
            if (d != HomeDeparture.Decision.UNDECIDED) {
                decidedRideMs = ride.startMs
                ridesSeen++
                if (d == HomeDeparture.Decision.DEPARTURE) out += Event.LeftHome(ride.startOdoKm)
            }
        }
        return out
    }
}

/**
 * Runs a [CounterTracker] for one BikeService session and stores its events. Create it before the first
 * report of the session: it reads the last known state and parked position as they were before.
 */
class CounterRecorder(ctx: Context, private val bikeId: String, nowMs: Long = System.currentTimeMillis()) {
    private val settings = ctx.settings
    private val tracker = CounterTracker(
        bikeId, settings.get(LastKnown.setting, bikeId), nowMs, Locations.parked(ctx, bikeId)?.fix,
    )

    fun onReport(r: BikeReport, nowMs: Long) {
        val events = tracker.onReport(r, nowMs, RideRecorder.open.value, Locations.fix.value, settings[RangeSettings.home])
        for (e in events) when (e) {
            is CounterTracker.Event.FullyCharged -> {
                settings.set(CounterSettings.fullChargeOdo, bikeId, e.odoKm)
                Repo.log("counters: full charge at ${"%.1f".format(e.odoKm)} km")
            }
            is CounterTracker.Event.LeftHome -> {
                settings.set(CounterSettings.homeDepartureOdo, bikeId, e.rideStartOdoKm)
                Repo.log("counters: left home at ${"%.1f".format(e.rideStartOdoKm)} km")
            }
        }
    }
}
