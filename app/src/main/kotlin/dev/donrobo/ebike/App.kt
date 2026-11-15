package dev.donrobo.ebike

import android.app.Application
import dev.donrobo.ebike.backup.Restore
import dev.donrobo.ebike.settings.Migration
import dev.donrobo.ebike.settings.settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        // A backup restore interrupted by a process death is completed (or an unconfirmed one discarded) first.
        try {
            if (Restore(filesDir, settings.store).recover()) Repo.log("interrupted backup restore completed")
        } catch (e: Exception) {
            Repo.log("backup restore recovery failed: $e")
        }
        // Before anything reads settings: brings pre-redesign single-bike data into the per-bike layout.
        try {
            Migration.run(settings, filesDir, getString(R.string.bike_default_name))
        } catch (e: Exception) {
            Repo.log("settings migration failed: $e")
        }
        dev.donrobo.ebike.range.RangeEngine.start(this) // single writer of RangeState, for UI, widget and service alike
        dev.donrobo.ebike.maintenance.MaintenanceMonitor.start(this)
        dev.donrobo.ebike.counters.CounterEngine.start(this) // single writer of CounterState
        dev.donrobo.ebike.widget.BikeWidget.followActiveBike(this) // the widget names the active bike, also while the service is off
    }
}
