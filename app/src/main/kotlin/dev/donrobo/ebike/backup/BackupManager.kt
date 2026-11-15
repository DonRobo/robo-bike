package dev.donrobo.ebike.backup

import android.content.Context
import android.net.Uri
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.settings.AppLanguage
import dev.donrobo.ebike.settings.Bikes
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Migration
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** What a set of app data holds, for the screen: bike names and number of rides. */
data class DataOverview(val bikes: List<String>, val rides: Int)

/** A checked backup waiting for confirmation. */
data class StagedBackup(val createdMs: Long, val appVersion: String, val language: String?, val overview: DataOverview)

/** Android side of backup and restore (F10): system file picker streams, app paths, language, refresh. */
object BackupManager {
    fun fileName(today: LocalDate = LocalDate.now()) = "robo-bike-backup-$today.zip"

    /** Bikes and rides in [settings] / [filesDir] (works for the live data and for a staged backup). */
    fun overview(settings: Settings, filesDir: File): DataOverview {
        val bikes = Bikes(settings, filesDir)
        val ids = bikes.ids()
        val rides = ids.sumOf { id -> File(bikes.dir(id), "rides").listFiles { f -> f.name.endsWith(".csv") }?.size ?: 0 }
        return DataOverview(bikes.all().map { it.name }, rides)
    }

    fun current(ctx: Context): DataOverview = overview(ctx.settings, ctx.filesDir)

    suspend fun export(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("cannot open $uri")
        out.use {
            Backup.write(it, ctx.filesDir, ctx.settings.store, Backup.Meta(System.currentTimeMillis(), Migration.CURRENT, appVersion(ctx), AppLanguage.current().tag))
        }
        Repo.log("Backup written")
    }

    /** Reads and checks the picked file without touching the phone's data. Throws [Backup.Invalid]. */
    suspend fun stage(ctx: Context, uri: Uri): StagedBackup = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Backup.Invalid(Backup.Problem.FOREIGN, "cannot open $uri")
        val c = input.use { restore(ctx).stage(it.buffered(), Migration.CURRENT) }
        StagedBackup(c.meta.createdMs, c.meta.appVersion, c.meta.language, overview(Settings(MemoryStore(c.values)), c.filesRoot))
    }

    fun discard(ctx: Context) = restore(ctx).discard()

    /**
     * Replaces all app data with [staged], then brings an older backup's settings to the current schema
     * and restores the backup's language. Refused while a ride is being recorded (its file is open).
     */
    suspend fun commit(ctx: Context, staged: StagedBackup) {
        withContext(Dispatchers.IO) {
            check(!Repo.recording.value) { "recording" }
            restore(ctx).commit()
            Migration.run(ctx.settings, ctx.filesDir, ctx.getString(R.string.bike_default_name))
            RideRepository.notifyChanged()
            Repo.log("Backup restored")
        }
        AppLanguage.entries.firstOrNull { it.tag == staged.language }?.let { if (it != AppLanguage.current()) AppLanguage.set(it) }
    }

    private fun restore(ctx: Context) = Restore(ctx.filesDir, ctx.settings.store)

    private fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
}
