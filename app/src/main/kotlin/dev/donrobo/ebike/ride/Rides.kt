package dev.donrobo.ebike.ride

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.location.LocationPolicy
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.settings.bikes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.Writer

/** Ride CSVs of each bike in `filesDir/bikes/<id>/rides/` (see [dev.donrobo.ebike.settings.Bikes.dir]). */
object RideStore {
    fun dir(ctx: Context, bikeId: String) = File(ctx.bikes.dir(bikeId), "rides").apply { mkdirs() }

    /** Newest first; files with fewer than 2 samples are skipped. Blocking; prefer [RideRepository.summaries]. */
    fun list(ctx: Context, bikeId: String): List<RideSummary> = RideRepository.index(ctx, bikeId).load()

    /** Writes a GPX file into cacheDir/export and returns a FileProvider content Uri (authority <applicationId>.files). */
    fun exportGpx(ctx: Context, ride: RideSummary): Uri {
        val name = ride.file.nameWithoutExtension
        val out = File(File(ctx.cacheDir, "export").apply { mkdirs() }, "$name.gpx")
        out.writeText(RideCsv.gpx(name, RideCsv.parse(ride.file)))
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
    }

    fun delete(ride: RideSummary) { ride.file.delete(); RideFixes.file(ride.file).delete(); RideRepository.notifyChanged() }
}

/**
 * Read access to the ride log for screens (F1, F3-F6, F8, F10, R3). Summaries come from a validated
 * cache ([RideIndex]); the CSV files remain the only source. Lists update when a ride starts, grows
 * (every [RideRecorder.REFRESH_MS]), ends or is deleted.
 */
object RideRepository {
    private val version = MutableStateFlow(0L)
    private val indexes = ConcurrentHashMap<String, RideIndex>()

    /** Summaries of [bikeId]'s rides, newest first; emits again after every change. */
    fun summaries(ctx: Context, bikeId: String): Flow<List<RideSummary>> {
        val app = ctx.applicationContext
        return version.map { index(app, bikeId).load() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    /** One-shot list (newest first). */
    suspend fun list(ctx: Context, bikeId: String): List<RideSummary> = withContext(Dispatchers.IO) { index(ctx, bikeId).load() }

    /** All samples of one ride, in time order. */
    suspend fun samples(ride: RideSummary): List<RideSample> = withContext(Dispatchers.IO) { RideCsv.parse(ride.file) }

    /** Positioned samples of each of [rides] (rides without a track are skipped), e.g. for the heatmap (F8). */
    suspend fun tracks(rides: List<RideSummary>): List<List<RideSample>> = withContext(Dispatchers.IO) {
        rides.filter { it.hasTrack }.map { r -> RideCsv.parse(r.file).filter { it.lat != null && it.lon != null } }
    }

    suspend fun delete(ride: RideSummary) = withContext(Dispatchers.IO) { RideStore.delete(ride) }

    /** GPX share Uri (F9), see [RideStore.exportGpx]. */
    suspend fun exportGpx(ctx: Context, ride: RideSummary): Uri = withContext(Dispatchers.IO) { RideStore.exportGpx(ctx, ride) }

    internal fun index(ctx: Context, bikeId: String): RideIndex {
        val dir = RideStore.dir(ctx, bikeId)
        return indexes.getOrPut(dir.path) { RideIndex(dir) }
    }

    /** Called by the writers of ride files (recorder, delete). */
    fun notifyChanged() { version.update { it + 1 } }
}

class RideRecorder(private val ctx: Context) {
    private val tracker = RideTracker()
    private var out: Writer? = null
    private var outFile: File? = null
    private var lastRefreshMs = 0L

    /** Collects [Repo.live] / Locations.fix until cancelled; maintains Repo.recording. Rides go to the connected bike. */
    suspend fun run() = withContext(Dispatchers.IO) {
        // ticks (null) let the 5-minute rule end a ride while no reports arrive (disconnected)
        val ticks = flow { while (true) { delay(5_000); emit(null) } }
        try {
            merge(Repo.live.filterNotNull(), ticks).collect { live ->
                val now = System.currentTimeMillis()
                if (live != null) {
                    val r = live.report
                    when (tracker.onSample(now, r.speedKmh, live.bikeId)) {
                        RideTracker.Action.START -> { start(now, live.bikeId, r); write(now, r) }
                        RideTracker.Action.APPEND -> write(now, r)
                        RideTracker.Action.SKIP -> {}
                    }
                }
                if (!tracker.isActive(now) && out != null) { val f = outFile; close("ride ended (no movement for 5 min)"); f?.let(::recordEnd) }
                Repo.publishRecording(tracker.isActive(now))
            }
        } finally {
            close("recorder stopped")
            Repo.publishRecording(false)
        }
    }

    private fun start(now: Long, bikeId: String, r: BikeReport) {
        close("new ride")
        val f = File(RideStore.dir(ctx, bikeId), RideCsv.fileName(now))
        out = f.bufferedWriter().apply { write(RideCsv.HEADER + "\n"); flush() }
        outFile = f
        // R9: the newest fix, if taken next to the standing bike just before it moved, is the ride's start
        Locations.fix.value?.takeIf { it.ageMs(now) <= RideEndpoints.STILL_MS }?.let { RideFixes.append(f, it) }
        _open.value = OpenRide(bikeId, now, r.odoKm)
        Repo.log("Ride started: ${f.name}")
        lastRefreshMs = now
        RideRepository.notifyChanged()
    }

    private fun write(now: Long, r: BikeReport) {
        // only positions younger than 30 s; an old fix is worse than none
        val fix = Locations.fix.value?.takeIf { it.ageMs(now) < 30_000 }
        val s = RideSample(now, fix?.point?.lat, fix?.point?.lon, r.speedKmh, r.batteryPct, r.gear, r.tripKm, r.odoKm, fix?.altitudeM)
        runCatching { out?.apply { write(RideCsv.line(s) + "\n"); flush() } }
            .onFailure { Repo.log("Ride write failed: $it") }
        if (now - lastRefreshMs >= REFRESH_MS) { lastRefreshMs = now; RideRepository.notifyChanged() }
    }

    private fun close(why: String) {
        val w = out ?: return
        out = null
        outFile = null
        _open.value = null
        runCatching { w.close() }
        Repo.log("Ride closed: $why")
        RideRepository.notifyChanged()
    }

    /**
     * R9: the bike has stood still since the ride's last movement, so a fix now is the ride's end. The newest fix
     * if it is fresh, else one short low-power query (Q3: no continuous GPS for this). It runs in [endScope], so a
     * service stopping right now does not cancel it. (A ride that ends because the connection ends gets its end
     * from [dev.donrobo.ebike.location.LocationProvider.onDisconnected] instead.)
     */
    private fun recordEnd(csv: File) {
        val app = ctx.applicationContext
        endScope.launch {
            val now = System.currentTimeMillis()
            val fix = Locations.fix.value?.takeIf { it.ageMs(now) < LocationPolicy.PARKED_FRESH_MS }
                ?: if (Locations.status.value.usable) Locations.requestFix(app) else null
            fix?.let { RideFixes.append(csv, it); RideRepository.notifyChanged() }
        }
    }

    companion object {
        /** How often the growing current ride refreshes [RideRepository.summaries]. */
        const val REFRESH_MS = 30_000L

        /** Process-wide: the end-of-ride fix outlives the recorder that asked for it. */
        private val endScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _open = MutableStateFlow<OpenRide?>(null)
        /** The ride being recorded now (null between rides); its start odometer is the ride file's first sample. */
        val open: StateFlow<OpenRide?> = _open
    }
}

/** A ride in progress: bike, start time and the odometer at its first sample. */
data class OpenRide(val bikeId: String, val startMs: Long, val startOdoKm: Double)
