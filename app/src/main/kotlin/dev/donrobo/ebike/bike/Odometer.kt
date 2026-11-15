package dev.donrobo.ebike.bike

import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A bike's odometer: from its [dev.donrobo.ebike.Snapshot] (live while connected, else last known); null = never connected. */
object Odometer {
    fun flow(settings: Settings, bikeId: String): Flow<Double?> =
        Repo.snapshotFlow(settings, bikeId).map { it?.report?.odoKm }.distinctUntilChanged()

    fun now(settings: Settings, bikeId: String): Double? = Repo.snapshot(settings, bikeId)?.report?.odoKm
}
