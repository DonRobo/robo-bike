package dev.donrobo.ebike.ui.connection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.BikeControl
import dev.donrobo.ebike.BikeService
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.connectionStatus
import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.R
import dev.donrobo.ebike.light.ManualLight
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val BT_PERMS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** String resources for the optional permissions that are currently denied. */
private fun Context.missingOptional(): List<Int> = buildList {
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) add(R.string.conn_missing_location)
    else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(R.string.conn_coarse_location)
    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(R.string.conn_missing_notifications)
}

/**
 * [connectionStatus] of [bike], following the link state in [Repo] (also when the link changes bike, A2).
 * [autoArmed]: auto-connect would start the service for this bike ([autoConnectArmed]).
 */
@Composable
fun rememberConnectionStatus(bike: Bike?, activeBikeId: String?, autoArmed: Boolean): ConnectionStatus {
    val conn by Repo.conn.collectAsStateWithLifecycle()
    val running by Repo.running.collectAsStateWithLifecycle()
    val linkLost by Repo.linkLost.collectAsStateWithLifecycle()
    val session by Repo.sessionBikeId.collectAsStateWithLifecycle()
    return connectionStatus(bike?.id, activeBikeId, session, conn, running, linkLost, autoArmed)
}

/**
 * Connect / disconnect / pairing of the active bike: Connect asks for the Bluetooth permission (the others are
 * asked one by one on the permissions page, E2), then, if the bike has no paired display, the companion chooser
 * (E6: "the next connect searches again"), then starts the service. Obtain with [rememberConnectionController];
 * state refreshes on resume and when the active bike changes.
 */
@Stable
class ConnectionController internal constructor(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val launchPermissions: () -> Unit,
) {
    internal var bike by mutableStateOf(ctx.bikes.active())
    internal var associatedMacs by mutableStateOf(BikeCompanion.associatedMacs(ctx))
    private var pausedTick by mutableStateOf(0) // re-read of the per-bike pause flag (no flow for it here)
    var autoConnect by mutableStateOf(ctx.settings[ConnectionSettings.autoConnect]); private set
    var missingPermissions by mutableStateOf(ctx.missingOptional()); internal set
    val companionSupported = BikeCompanion.supported(ctx)

    /** The active bike's display is paired with the system (auto-connect possible). */
    val associated: Boolean get() = bike?.displayMac?.let { m -> associatedMacs.any { it.equals(m, ignoreCase = true) } } == true

    /** E5 for the active bike. */
    val userStopped: Boolean get() { pausedTick; return bike?.let { ctx.settings.get(ConnectionSettings.pausedUntilOff, it.id) } == true }

    internal fun refresh() {
        missingPermissions = ctx.missingOptional()
        bike = ctx.bikes.active()
        associatedMacs = BikeCompanion.associatedMacs(ctx)
        pausedTick++ // the notification's Disconnect may have changed it
        autoConnect = ctx.settings[ConnectionSettings.autoConnect]
    }

    fun connect() {
        bike?.let { ctx.settings.set(ConnectionSettings.pausedUntilOff, it.id, false) }
        pausedTick++
        launchPermissions()
    }

    fun disconnect() { BikeService.disconnect(ctx); pausedTick++ }

    fun updateAutoConnect(on: Boolean) {
        autoConnect = on
        BikeCompanion.setAutoConnect(ctx, on)
    }

    /** E6 for the active bike: unpair; its next Connect searches for a display again. Other bikes keep theirs. */
    fun forgetDisplay() {
        bike?.let { BikeCompanion.forget(ctx, it.id) }
        refresh()
    }

    /** Runs a display command, reporting "not connected" and failures in the snackbar. [what] names the action. */
    fun command(what: String, block: suspend BikeControl.() -> Unit) {
        val bike = Repo.bike ?: run { scope.launch { snackbar.showSnackbar(ctx.getString(R.string.status_off)) }; return }
        scope.launch {
            try { bike.block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Repo.log("$what failed: $e")
                snackbar.showSnackbar(ctx.getString(R.string.conn_command_failed, what, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** The rider's light switch on a screen (L1): the same path and rules as widget, tile and notification. */
    fun setLight(on: Boolean, from: String) {
        scope.launch { if (!ManualLight.set(ctx, on, from)) snackbar.showSnackbar(ctx.getString(R.string.conn_light_failed)) }
    }

    /** Auto-connect would start the service for the active bike ([autoConnectArmed]), from the state refreshed on resume. */
    val autoArmed: Boolean get() =
        autoConnect && !userStopped && (associated || (!companionSupported && bike?.displayMac != null))

    /** Status of the active bike's connection (E4), reactive to the link state. */
    @Composable
    fun status(): ConnectionStatus = rememberConnectionStatus(bike, bike?.id, autoArmed)

    internal fun showError(text: String) { scope.launch { snackbar.showSnackbar(text) } }
}

@Composable
fun rememberConnectionController(snackbar: SnackbarHostState): ConnectionController {
    val ctx = LocalContext.current
    val resources = LocalResources.current // resources follow configuration changes
    val scope = rememberCoroutineScope()
    var holder: ConnectionController? = null
    val associatedBefore = remember { mutableStateOf(emptyList<String>()) } // to find the new association in the result

    val associateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val mac = if (res.resultCode == Activity.RESULT_OK) BikeCompanion.macFromResult(ctx, res.data, associatedBefore.value) else null
        val bike = ctx.bikes.active()
        if (mac != null && bike != null) {
            val r = BikeCompanion.adopt(ctx, Pairing.Target.Existing(bike.id), mac, name = null, defaultName = bike.name)
            if (r is Pairing.Assign.AlreadyOwned) holder?.showError(resources.getString(R.string.setup_already_paired, ctx.bikes.get(r.bikeId)?.name.orEmpty()))
        }
        holder?.refresh()
        BikeService.start(ctx) // connects either way; without an association there is just no auto-connect
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        holder?.missingPermissions = ctx.missingOptional()
        if (!BT_PERMS.all(ctx::granted)) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.conn_bt_required)) }
        } else if (BikeCompanion.needsAssociation(ctx)) {
            // One-time system dialog; afterwards the system reports when the display is switched on.
            associatedBefore.value = BikeCompanion.associatedMacs(ctx)
            BikeCompanion.associate(
                ctx, BikeCompanion.Search.Known,
                onPending = { associateLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                onFailure = { err ->
                    Repo.log("association failed: $err")
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.conn_pair_failed, err ?: resources.getString(R.string.conn_unknown_error))) }
                    BikeService.start(ctx)
                },
            )
        } else {
            BikeService.start(ctx)
        }
    }
    val controller = remember { ConnectionController(ctx, scope, snackbar) { permLauncher.launch(BT_PERMS.toTypedArray()) } }
    holder = controller
    LaunchedEffect(controller) { ctx.bikes.activeFlow().collect { b: Bike? -> controller.bike = b } }
    LifecycleResumeEffect(Unit) {
        controller.refresh()
        // A service started in the background has no GPS; restarting it while visible upgrades it to the location type.
        if (Repo.running.value) BikeService.start(ctx)
        onPauseOrDispose { }
    }
    return controller
}
