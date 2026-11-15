package dev.donrobo.ebike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dev.donrobo.ebike.ble.GattTransport
import dev.donrobo.ebike.ble.LinkTransport
import dev.donrobo.ebike.bike.BikeReminders
import dev.donrobo.ebike.errors.DriveErrorWatcher
import dev.donrobo.ebike.errors.ErrorLogWriter
import dev.donrobo.ebike.handlebar.HandlebarAutoStart
import dev.donrobo.ebike.location.LocationPolicy
import dev.donrobo.ebike.location.LocationProvider
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.notify.ConnectionNotice
import dev.donrobo.ebike.notify.DriveErrorAlerts
import dev.donrobo.ebike.notify.NotificationIds
import dev.donrobo.ebike.notify.NotifySettings
import dev.donrobo.ebike.notify.RequestCodes
import dev.donrobo.ebike.notify.connectionNotice
import dev.donrobo.ebike.notify.connectionNotification
import dev.donrobo.ebike.ride.RideRecorder
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.sim.SimulatorHook
import dev.donrobo.ebike.widget.BikeWidget
import dev.donrobo.ebike.widget.currentGlance
import dev.donrobo.ebike.widget.glanceFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Foreground service: keeps the BLE link to the display alive (one [Session] per connection, the display found by
 * [DisplayFinder]), runs the location provider and the features that work while the bike is connected.
 */
@SuppressLint("MissingPermission")
class BikeService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    @Volatile private var session: Session? = null
    private val location = LocationProvider(this, scope)
    private var hasLocationType = false
    private var firstCommand = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // startForeground first: a service started with startForegroundService must call it, even when it stops at once.
        val reason = when {
            !startInForeground() -> "cannot start in the foreground"
            !granted(Manifest.permission.BLUETOOTH_CONNECT) -> "BLUETOOTH_CONNECT not granted"
            else -> null
        }
        if (reason != null) {
            Repo.log("BikeService: $reason, stopping")
            stopSelf()
            return
        }
        // The link state in Repo (running, conn, session, live, bike, linkLost) is written by the connection loop only,
        // which first waits until the previous instance's coroutines (and so its teardown) have finished.
        val previous = lastJob
        lastJob = job
        location.start()
        // Strings carry the rounded values, so the notification only changes when what it shows changes. Posted on the
        // main thread, like onDestroy: no update can land after onDestroy removed the notification (a late one would
        // stay as an orphan "waiting for connection" notification after the service stopped).
        scope.launch(Dispatchers.Main) {
            combine(glanceFlow(this@BikeService, forService = true), settings.flow(NotifySettings.connectionDetails)) { g, details -> connectionNotice(this@BikeService, g, details) }
                .distinctUntilChanged().flowOn(Dispatchers.Default).collect { nm.notify(NotificationIds.CONNECTION, notification(it)) }
        }
        scope.launch { connectionLoop(previous) }
        runGuarded("widget updater") { BikeWidget.Updater(this@BikeService).run() }
        runGuarded("DriveErrorWatcher") { DriveErrorWatcher(listOf(ErrorLogWriter(this@BikeService), DriveErrorAlerts(this@BikeService))).run() }
        runGuarded("RideRecorder") { RideRecorder(this@BikeService).run() }
        runGuarded("LightAutomation") { LightAutomation(this@BikeService).run() }
        runGuarded("HandlebarAutoStart") { HandlebarAutoStart(this@BikeService).run() }
        runGuarded("BikeReminders") { BikeReminders(this@BikeService).run() }
    }

    /** One feature for the service's lifetime; a crash is logged and ends only that feature. */
    private fun runGuarded(name: String, block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Repo.log("$name crashed: $e") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            pauseCurrentBike(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // Later starts (e.g. from the visible UI) may upgrade a background-started service to the location type.
        if (!firstCommand && !hasLocationType) startInForeground()
        firstCommand = false
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancelling runs the connection loop's finally blocks: the session's end, then every reset of the link state
        // (linkReset, finishLoop). Nothing here writes that state, so no reset can race with them.
        scope.cancel()
        // The notification goes with the service; cancel explicitly in case an update re-posted it after it left the
        // foreground.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NotificationIds.CONNECTION)
        session?.close() // release the display at once; the session's own close is then a no-op
        location.stop()
        super.onDestroy()
    }

    // --- foreground / notification -----------------------------------------

    /**
     * Location type only when its prerequisites hold right now ([LocationPolicy.locationServiceTypeAllowed]): a service
     * started from the background (presence event, sticky restart) gets it only with "allow all the time", since the
     * companion exemption does not cover while-in-use permissions. Falls back to connectedDevice alone if the system
     * still refuses it.
     */
    private fun startInForeground(): Boolean {
        val locationAllowed = LocationPolicy.locationServiceTypeAllowed(Locations.permissions(this), Locations.systemLocationOn(this), appInForeground())
        for (t in LocationPolicy.serviceTypeAttempts(locationAllowed)) {
            try {
                ServiceCompat.startForeground(this, NotificationIds.CONNECTION, notification(connectionNotice(this, currentGlance(this, forService = true), settings[NotifySettings.connectionDetails])), t)
                hasLocationType = t and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
                location.setServiceHasLocationType(hasLocationType)
                return true
            } catch (e: Exception) {
                Repo.log("startForeground(type $t) failed: $e")
            }
        }
        return false
    }

    /** K3: content and actions come from the notifications module ([connectionNotice]); the stop intent is ours. */
    private fun notification(content: ConnectionNotice): Notification {
        val disconnect = PendingIntent.getService(
            this, RequestCodes.SERVICE_DISCONNECT, Intent(this, BikeService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return connectionNotification(this, content, disconnect)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // --- connection loop ---------------------------------------------------

    /** The only writer of the link state in [Repo]; [previous] is the job of the service instance before this one. */
    private suspend fun connectionLoop(previous: Job?) {
        previous?.join() // its teardown (finishLoop) must not land after our start
        Repo.serviceStarted()
        try {
            loop()
        } finally {
            finishLoop()
        }
    }

    /** Service end (stopped, idle stop or cancelled): back to "no service", then the widget shows the last known state. */
    private fun finishLoop() {
        resetLink()
        Repo.serviceStopped()
        runCatching { BikeWidget.refresh(this) } // the updater is gone with the scope
    }

    /** After every connection attempt, once its session (if any) has ended. */
    private fun resetLink() {
        session?.close()
        session = null
        Repo.linkReset()
    }

    private suspend fun loop() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val finder = DisplayFinder(this)
        var btOffLogged = false
        var idleSince = SystemClock.elapsedRealtime()
        while (true) {
            try {
                // Debug builds only: the active bike's display is the simulator (release: always null).
                val simulated = SimulatorHook.transport(this)
                if (simulated != null) {
                    runSession(simulated)
                } else if (adapter == null || !adapter.isEnabled) {
                    if (!btOffLogged) Repo.log("Bluetooth is off, waiting")
                    btOffLogged = true
                } else {
                    btOffLogged = false
                    finder.find(adapter)?.let { runSession(GattTransport(this, it)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Repo.log("connection: ${e.message ?: e}")
            } finally {
                if (Repo.conn.value == ConnState.CONNECTED) idleSince = SystemClock.elapsedRealtime()
                resetLink()
            }
            if (SystemClock.elapsedRealtime() - idleSince > IDLE_STOP_MS) {
                // Bike off or out of range: stop scanning and GPS. Presence observation restarts us when it advertises.
                Repo.log("no connection for ${IDLE_STOP_MS / 1000} s, stopping")
                stopSelf()
                return
            }
            delay(RETRY_MS)
        }
    }

    private suspend fun runSession(transport: LinkTransport) {
        val s = Session(this, transport, location)
        session = s
        s.run()
    }

    companion object {
        private const val ACTION_DISCONNECT = "dev.donrobo.ebike.DISCONNECT"
        private const val IDLE_STOP_MS = 60_000L
        private const val RETRY_MS = 5_000L

        /** Job of the newest service instance; the next one waits for it to finish (see connectionLoop). */
        @Volatile private var lastJob: Job? = null

        /** Requires BLUETOOTH_CONNECT/SCAN to be granted already. */
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, BikeService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BikeService::class.java)) }
        /** User-initiated stop: also pauses auto-connect of this bike until its display disappears (bike off) or a manual Connect. */
        fun disconnect(ctx: Context) { pauseCurrentBike(ctx); stop(ctx) }

        /** E5: pauses the bike of the current session (else the active bike). */
        private fun pauseCurrentBike(ctx: Context) {
            val id = Repo.sessionBikeId.value ?: ctx.bikes.active()?.id ?: return
            ctx.settings.set(ConnectionSettings.pausedUntilOff, id, true)
        }
    }
}
