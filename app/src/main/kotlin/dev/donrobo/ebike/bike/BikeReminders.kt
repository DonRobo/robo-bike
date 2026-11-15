package dev.donrobo.ebike.bike

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.maintenance.Maintenance
import dev.donrobo.ebike.maintenance.displayName
import dev.donrobo.ebike.maintenance.MaintenanceSettings
import dev.donrobo.ebike.notify.Alert
import dev.donrobo.ebike.notify.AlertKind
import dev.donrobo.ebike.notify.Alerts
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Background evaluation of the maintenance (W1) and charge (W4) conditions while BikeService runs. This class only
 * decides whether a condition holds and under which key; [Alerts] owns posting, de-duplication, quiet times and the
 * rider's on/off settings.
 */
class BikeReminders(private val ctx: Context) {
    private val settings = ctx.settings

    suspend fun run() = coroutineScope {
        launch { maintenance() }
        launch { charge() }
    }

    /** Re-evaluated whenever the connected bike's odometer passes another 100 m (it only moves while connected). */
    private suspend fun maintenance() {
        Repo.live.filterNotNull().map { it.bikeId to floor(it.report.odoKm * 10) / 10 }.distinctUntilChanged()
            .collect { (bikeId, odo) -> checkMaintenance(bikeId, odo) }
    }

    private fun checkMaintenance(bikeId: String, odoKm: Double) {
        val due = Maintenance.mostUrgent(MaintenanceSettings.statuses(settings, bikeId, odoKm))
        if (due == null) Alerts.resolve(ctx, bikeId, AlertKind.MAINTENANCE)
        else Alerts.show(ctx, bikeId, Alert.MaintenanceDue(Maintenance.alertKey(due), due.item.displayName(ctx), due.remainingKm ?: 0.0))
    }

    private suspend fun charge() {
        val tracker = ChargeReminderTracker { id -> settings.get(ChargeSettings.thresholdPct, id) }
        try {
            combine(Repo.recording, Repo.sessionBikeId, Repo.live) { rec, id, l -> Triple(rec, id, l?.takeIf { it.bikeId == id }?.report?.batteryPct) }
                .distinctUntilChanged()
                .collect { (rec, id, pct) -> tracker.onSample(rec, id, pct, System.currentTimeMillis())?.let(::deliver) }
        } finally {
            // The service stops: the recorder closes an open ride at the same moment, so this is that ride's end.
            tracker.onStop()?.let(::deliver)
        }
    }

    private fun deliver(e: ChargeReminderTracker.Event) {
        when (e) {
            is ChargeReminderTracker.Event.Remind -> Alerts.show(ctx, e.bikeId, Alert.ChargeReminder(e.batteryPct, e.rideStartMs.toString()))
            is ChargeReminderTracker.Event.Charged -> Alerts.resolve(ctx, e.bikeId, AlertKind.CHARGE)
        }
    }
}
