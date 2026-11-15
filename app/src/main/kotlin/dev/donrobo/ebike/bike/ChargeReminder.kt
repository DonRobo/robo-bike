package dev.donrobo.ebike.bike

import dev.donrobo.ebike.battery.BatteryReadings
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting

/**
 * W4 threshold, per bike. Whether charge reminders are on at all is the app-wide
 * [dev.donrobo.ebike.notify.NotifySettings.charge] (the Rad tab's switch writes it; [dev.donrobo.ebike.notify.Alerts]
 * honours it), so there is one switch, not two.
 */
object ChargeSettings {
    val thresholdPct = Setting.int("charge.thresholdPct", 30, Scope.PER_BIKE)

    const val MIN_PCT = 10
    const val MAX_PCT = 60
    const val STEP_PCT = 5
}

/** Pure W4 decisions (unit-tested). */
object ChargeReminderLogic {
    /**
     * The battery rose by at least this many percent since its lowest value: the bike is (or was) on the charger.
     * The reading is voltage-derived and climbs back after load by several points by itself, so this is the
     * shared bound that recovery never reaches ([BatteryReadings.CHARGE_RISE_PCT]), not mere jitter.
     */
    const val CHARGED_RISE_PCT = BatteryReadings.CHARGE_RISE_PCT

    /**
     * A ride has ended: remind when the battery now (after the standing phase that ends a ride, i.e. at rest) is
     * below the threshold, unless it rose since the lowest value seen during/after the ride by more than recovery
     * can explain (charged meanwhile).
     */
    fun shouldRemind(thresholdPct: Int, lowestPct: Int, nowPct: Int): Boolean =
        nowPct < thresholdPct && !charged(lowestPct, nowPct)

    fun charged(lowestPct: Int, nowPct: Int) = nowPct >= lowestPct + CHARGED_RISE_PCT
}

/**
 * Follows rides as the ride recorder defines them ([dev.donrobo.ebike.Repo.recording]: true from the first movement
 * until 5 minutes without movement, or until the recorder stops with the service) and decides W4 at each ride's
 * end. Pure state machine, one per service lifetime; [thresholdPct] reads the bike's current threshold.
 */
class ChargeReminderTracker(private val thresholdPct: (bikeId: String) -> Int) {
    sealed interface Event {
        /** Condition holds for the ride that started at [rideStartMs] (its id for the alert). */
        data class Remind(val bikeId: String, val rideStartMs: Long, val batteryPct: Int) : Event
        /** The reminded bike has been charged since: the condition no longer holds. */
        data class Charged(val bikeId: String) : Event
    }

    private var rideBike: String? = null
    private var rideStartMs = 0L
    private var lowest = Int.MAX_VALUE
    private var lastPct: Int? = null
    private var reminded: Event.Remind? = null

    /**
     * Every change of recording state or report. [bikeId]/[batteryPct] are those of the current connection
     * (null while disconnected; the last value seen is kept).
     */
    fun onSample(recording: Boolean, bikeId: String?, batteryPct: Int?, nowMs: Long): Event? {
        if (batteryPct != null && bikeId != null && (rideBike == null || bikeId == rideBike)) lastPct = batteryPct
        reminded?.let { r ->
            if (batteryPct != null && bikeId == r.bikeId && ChargeReminderLogic.charged(r.batteryPct, batteryPct)) {
                reminded = null
                return Event.Charged(r.bikeId)
            }
        }
        if (recording && rideBike == null && bikeId != null) {
            rideBike = bikeId
            rideStartMs = nowMs
            lowest = batteryPct ?: Int.MAX_VALUE
            lastPct = batteryPct
        }
        if (rideBike != null && bikeId == rideBike && batteryPct != null) lowest = minOf(lowest, batteryPct)
        return if (!recording && rideBike != null) endRide() else null
    }

    /** The service stops (the recorder closes an open ride then too). */
    fun onStop(): Event? = if (rideBike != null) endRide() else null

    private fun endRide(): Event? {
        val bike = rideBike ?: return null
        val now = lastPct
        val low = lowest
        rideBike = null
        lowest = Int.MAX_VALUE
        if (now == null) return null
        if (!ChargeReminderLogic.shouldRemind(thresholdPct(bike), minOf(low, now), now)) return null
        return Event.Remind(bike, rideStartMs, now).also { reminded = it }
    }
}
