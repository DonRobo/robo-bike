package dev.donrobo.ebike.maintenance

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.bike.Odometer
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Process-wide: publishes the active bike's most urgent item to [MaintenanceState.next] (its single writer) and
 * records each bike's maintenance baseline (first odometer known). Started once from App.onCreate, so the Start
 * screen's card is right whether or not the Rad tab was ever opened.
 */
object MaintenanceMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val settings = app.settings
        val bikes = app.bikes
        scope.launch {
            // Any settings change (plan edits, units, active bike, last known state) or a new odometer value.
            combine(settings.anyChange(), Repo.live) { _, _ -> bikes.active().let { b -> Input(b, b?.let { Odometer.now(settings, it.id) }) } }
                .distinctUntilChanged()
                .collect { evaluate(settings, it) }
        }
    }

    /** The active bike and its odometer as shown ([Odometer]: live only if it is the connected bike). */
    private data class Input(val bike: Bike?, val odo: Double?)

    private fun evaluate(settings: Settings, i: Input) {
        val bike = i.bike ?: run { MaintenanceState.next.value = null; return }
        val odo = i.odo
        if (odo != null && MaintenanceSettings.baseline(settings, bike.id) == null) settings.set(MaintenanceSettings.baselineKm, bike.id, odo)
        MaintenanceState.next.value = next(MaintenanceSettings.statuses(settings, bike.id, odo))
    }

    /** Pure: what the Start card shows. */
    fun next(statuses: List<MaintenanceStatus>): MaintenanceDue? =
        Maintenance.mostUrgent(statuses)?.let { MaintenanceDue(it.item.name, it.remainingKm!!, it.item.kind) }
}
