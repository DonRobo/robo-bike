package dev.donrobo.ebike.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.appInForeground
import dev.donrobo.ebike.ride.RideFixes
import dev.donrobo.ebike.ride.RideSettings
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

/**
 * The app's one source of positions. Read from anywhere; written only by [LocationProvider] (inside
 * BikeService) and [requestFix].
 */
object Locations {
    private val _fix = MutableStateFlow<LocationFix?>(null)
    private val _status = MutableStateFlow(LocationStatus.UNKNOWN)

    /** Newest fix received in this process (null until the first one). */
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    /** What the provider currently does and why location is limited, for the UI to explain. */
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    /** Newest known position: this process's fix, else the system's last known location, else the persisted one. */
    fun best(ctx: Context): LocationFix? =
        listOfNotNull(_fix.value, systemLastKnown(ctx), ctx.settings[LocationSettings.lastFix]).maxByOrNull { it.timeMs }

    /** Where [bikeId] was when its last connection ended (W3); null = never recorded. */
    fun parked(ctx: Context, bikeId: String): ParkedPosition? = ctx.settings.get(LocationSettings.parked, bikeId)

    /** Current permissions; usable without the service running (e.g. by setup / settings screens). */
    fun permissions(ctx: Context): LocationPermissions {
        fun g(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return LocationPermissions(
            coarse = g(Manifest.permission.ACCESS_COARSE_LOCATION),
            fine = g(Manifest.permission.ACCESS_FINE_LOCATION),
            background = g(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
    }

    /** The system-wide location switch (false when it cannot be read). */
    fun systemLocationOn(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true }.getOrDefault(false)

    /**
     * One low-power fix right now, e.g. for "use my position as home" while the app is open. Null if
     * there is no permission, location is off, or nothing arrives within [timeoutMs]. Publishes the fix.
     */
    suspend fun requestFix(ctx: Context, timeoutMs: Long = 30_000): LocationFix? {
        val loc = currentLocation(ctx, timeoutMs) ?: return null
        val fix = toFix(ctx, loc)
        publish(ctx, fix)
        return fix
    }

    // --- internal (LocationProvider) -------------------------------------------

    internal fun publish(ctx: Context, fix: LocationFix) {
        val cur = _fix.value
        if (cur != null && cur.timeMs > fix.timeMs) return // never go back to an older fix
        _fix.value = fix
        val s = ctx.settings
        if (LocationPolicy.shouldPersist(s[LocationSettings.lastFix], fix)) s[LocationSettings.lastFix] = fix
    }

    internal fun setStatus(s: LocationStatus) { _status.value = s }

    @SuppressLint("MissingPermission")
    internal fun systemLastKnown(ctx: Context): LocationFix? {
        if (!permissions(ctx).any) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { LocationFix(GeoPoint(it.latitude, it.longitude), it.time, it.accuracy.takeIf { _ -> it.hasAccuracy() }, null) }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Fused (or network) provider at balanced power; never GPS, so it stays cheap. */
    @SuppressLint("MissingPermission")
    internal suspend fun currentLocation(ctx: Context, timeoutMs: Long): Location? {
        if (!permissions(ctx).any) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val req = LocationRequest.Builder(0).setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).setDurationMillis(timeoutMs).build()
        return withTimeoutOrNull(timeoutMs + 1_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    lm.getCurrentLocation(provider, req, cancel, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** Converts to [LocationFix]; on API 34+ the altitude is converted to mean sea level (blocking IO, so off the main thread). */
    internal suspend fun toFix(ctx: Context, loc: Location): LocationFix = withContext(Dispatchers.IO) {
        val alt: Double? = when {
            !loc.hasAltitude() -> null
            Build.VERSION.SDK_INT >= 34 -> try {
                if (!loc.hasMslAltitude()) android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(ctx, loc)
                loc.mslAltitudeMeters
            } catch (e: Exception) {
                null // keep heights within a ride consistent: no fallback to the ellipsoid height on these devices
            }
            else -> loc.altitude
        }
        LocationFix(GeoPoint(loc.latitude, loc.longitude), loc.time, loc.accuracy.takeIf { loc.hasAccuracy() }, alt)
    }
}

/**
 * Runs inside BikeService: applies [LocationPolicy] to the track setting, the connection, permissions
 * and the service type, and records where a bike was when its connection ended (W3).
 */
@SuppressLint("MissingPermission")
class LocationProvider(private val ctx: Context, private val scope: CoroutineScope) {
    // Lazy: the provider is constructed with its Service, before the Service has a base context.
    private val lm by lazy { ctx.getSystemService(LocationManager::class.java) }
    private val serviceType = MutableStateFlow(false)
    private val fixes = Channel<Location>(Channel.UNLIMITED)
    // Main thread only (apply and stop both run there), so no synchronisation is needed.
    private var gps: LocationListener? = null
    private var occasional: Job? = null
    /** [LocationPolicy.periodicFixesWanted], kept current from the settings so toggling it while connected applies. */
    private val periodic = MutableStateFlow(false)
    /** The switch-on fix of the current connection was checked; reset whenever the bike is not connected. */
    @Volatile private var connectFixChecked = false
    /** Whether a fix could be obtained, as last decided while running; [stop] does not reset it (see [onDisconnected]). */
    @Volatile private var lastUsable = false

    /** BikeService reports whether its foreground service currently has the location type. */
    fun setServiceHasLocationType(v: Boolean) { serviceType.value = v }

    fun start() {
        Locations.systemLastKnown(ctx)?.let { Locations.publish(ctx, it) }
        scope.launch { for (loc in fixes) Locations.publish(ctx, Locations.toFix(ctx, loc)) }
        scope.launch {
            val range = dev.donrobo.ebike.range.RangeSettings
            combine(ctx.settings.flow(range.homeEnabled), ctx.settings.flow(range.home), LocationPolicy::periodicFixesWanted)
                .collect { periodic.value = it }
        }
        scope.launch { Repo.conn.collect { if (it != ConnState.CONNECTED) connectFixChecked = false } }
        scope.launch(Dispatchers.Main) {
            // Permissions, the system switch and app visibility have no change callbacks here: re-check every 15 s.
            val ticks = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); delay(15_000) } }
            combine(ctx.settings.flow(RideSettings.gpsTrack), Repo.conn, serviceType, ticks) { track, conn, type, _ ->
                LocationPolicy.decide(
                    LocationInput(
                        trackWanted = track, connected = conn == ConnState.CONNECTED,
                        permissions = Locations.permissions(ctx),
                        systemLocationOn = runCatching { lm.isLocationEnabled }.getOrDefault(false),
                        appVisible = appInForeground(), serviceHasLocationType = type,
                    ),
                )
            }.flowOn(Dispatchers.Default).collectLatest(::apply)
        }
    }

    /** Main thread (BikeService.onDestroy). */
    fun stop() {
        removeGps()
        occasional?.cancel()
        Locations.setStatus(Locations.status.value.copy(mode = LocationMode.OFF))
    }

    private fun apply(s: LocationStatus) {
        lastUsable = s.usable
        val prev = Locations.status.value
        if (s != prev) {
            if (s.mode != prev.mode || s.limit != prev.limit) Repo.log("location: ${s.mode}${s.limit?.let { " (limit: $it)" } ?: ""}")
            Locations.setStatus(s)
        }
        if (s.mode == LocationMode.CONTINUOUS) addGps() else removeGps()
        if (s.mode == LocationMode.OCCASIONAL) {
            if (occasional?.isActive != true) occasional = scope.launch { occasionalLoop() }
        } else {
            occasional?.cancel(); occasional = null
        }
    }

    /**
     * The switch-on fix on the first pass of a connection, then one every [LocationPolicy.OCCASIONAL_INTERVAL_MS] while
     * [periodic]. Checks once a minute, and at once when [periodic] changes (home set or the feature switched on).
     */
    private suspend fun occasionalLoop() {
        while (true) {
            val first = !connectFixChecked
            connectFixChecked = true
            val wanted = periodic.value
            if (LocationPolicy.occasionalFixDue(Locations.fix.value, System.currentTimeMillis(), first, wanted)) {
                Locations.currentLocation(ctx, 30_000)?.let { fixes.trySend(it) }
            }
            withTimeoutOrNull(60_000) { periodic.first { it != wanted } }
        }
    }

    private fun addGps() {
        if (gps != null) return
        val l = LocationListener { fixes.trySend(it) }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, LocationPolicy.GPS_INTERVAL_MS, 0f, l, Looper.getMainLooper())
            gps = l
        } catch (e: Exception) {
            Repo.log("GPS unavailable: $e")
        }
    }

    private fun removeGps() {
        gps?.let { runCatching { lm.removeUpdates(it) } }
        gps = null
    }

    /**
     * The connection to [bikeId]'s display ended at [atMs]: store the newest position now, then try for a
     * fresh one-shot fix (the phone is usually still next to the bike) and replace it if one arrives. The same fix
     * is offered to the bike's newest ride as its end ([RideFixes.offerToLatest], R9).
     * The request runs outside the service scope: on Disconnect the connection ends because the service stops,
     * and a request launched into its (then cancelled) scope would never run.
     */
    fun onDisconnected(bikeId: String, atMs: Long) {
        val known = Locations.fix.value
        ctx.settings.set(LocationSettings.parked, bikeId, ParkedPosition(atMs, known))
        val app = ctx.applicationContext
        if (known != null && known.ageMs(atMs) < LocationPolicy.PARKED_FRESH_MS) {
            parkedScope.launch { RideFixes.offerToLatest(app, bikeId, known) } // R9: also the end of the ride
            return
        }
        if (!lastUsable) return
        parkedScope.launch {
            try {
                val loc = Locations.currentLocation(app, 30_000) ?: return@launch
                val fix = Locations.toFix(app, loc)
                Locations.publish(app, fix)
                if (app.settings.get(LocationSettings.parked, bikeId)?.atMs == atMs) {
                    app.settings.set(LocationSettings.parked, bikeId, ParkedPosition(atMs, fix))
                }
                RideFixes.offerToLatest(app, bikeId, fix) // R9: the bike is switched off where the ride ended
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Repo.log("parked position: $e")
            }
        }
    }

    private companion object {
        /** Process-wide: the parked-position fix outlives the service that requested it. */
        val parkedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
