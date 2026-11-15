package dev.donrobo.ebike

import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings

/** Connection behaviour (E3, E5). Key names of global keys are the pre-redesign ones, so existing values carry over. */
object ConnectionSettings {
    /** Start the connection when a paired display appears (Companion Device presence). One switch for all bikes. */
    val autoConnect = Setting.bool("autoConnect", true)

    /**
     * Per bike (E5): set by Disconnect, cleared by Connect or when that bike's display disappears (bike switched
     * off); presence events of that display are ignored in between. Replaces the pre-redesign global
     * `userStopped`, which is transient and simply no longer read.
     */
    val pausedUntilOff = Setting.bool("conn.pausedUntilOff", false, Scope.PER_BIKE)

    fun pausedBikes(settings: Settings, bikes: List<Bike>): Set<String> =
        bikes.filter { settings.get(pausedUntilOff, it.id) }.map { it.id }.toSet()
}
