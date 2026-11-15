package dev.donrobo.ebike.maintenance

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The most urgent maintenance item of the active bike (W1); [dueInKm] < 0 means overdue. [name] is the rider's
 * name of a custom item; built-ins ([kind] != CUSTOM) are named by the UI in the current language
 * (`ui.bike.maintenanceName`).
 */
data class MaintenanceDue(val name: String, val dueInKm: Double, val kind: MaintenanceKind = MaintenanceKind.CUSTOM)

/**
 * Published by [MaintenanceMonitor] (its single writer) for the Start screen's card. null = nothing due soon or
 * overdue, or the odometer is not known yet.
 */
object MaintenanceState {
    val next = MutableStateFlow<MaintenanceDue?>(null)
}
