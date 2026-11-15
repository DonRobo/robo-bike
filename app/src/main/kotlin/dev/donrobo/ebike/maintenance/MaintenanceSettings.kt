package dev.donrobo.ebike.maintenance

import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings

/** Per-bike maintenance data (A2: each bike has its own plan). */
object MaintenanceSettings {
    /**
     * The bike's items. null = never edited: [Maintenance.defaults] in the current units apply. Written by the Rad
     * screen only (edits persist the whole list).
     */
    val items = Setting.mapped<List<MaintenanceItem>?>("maintenance.items", null, Scope.PER_BIKE, Maintenance::decode, { it?.let(Maintenance::encode) })

    /** First odometer the app knew for the bike (km); items never marked done count from here. < 0 = none yet. Written by [MaintenanceMonitor]. */
    val baselineKm = Setting.double("maintenance.baselineKm", -1.0, Scope.PER_BIKE)

    fun plan(settings: Settings, bikeId: String): List<MaintenanceItem> =
        settings.get(items, bikeId) ?: Maintenance.defaults(settings[GeneralSettings.units])

    fun baseline(settings: Settings, bikeId: String): Double? = settings.get(baselineKm, bikeId).takeIf { it >= 0 }

    /** The bike's plan evaluated at [odoKm]: the one status path of the Rad screen, the Start card and the alert. */
    fun statuses(settings: Settings, bikeId: String, odoKm: Double?): List<MaintenanceStatus> =
        Maintenance.statuses(plan(settings, bikeId), odoKm, baseline(settings, bikeId))

    /** Read-modify-write of the plan (defaults are written out on the first edit). */
    fun edit(settings: Settings, bikeId: String, change: (List<MaintenanceItem>) -> List<MaintenanceItem>) =
        settings.set(items, bikeId, change(plan(settings, bikeId)))
}
