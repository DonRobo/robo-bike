package dev.donrobo.ebike.ui.setup

import android.content.Context
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.pairing.SeenDevice
import dev.donrobo.ebike.pairing.Signal
import dev.donrobo.ebike.pairing.signal
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.ui.Routes

const val SETUP_STEPS = 3

/** Where the app opens: the setup while no bike has a display (after giving orphaned associations back to bikes). */
fun firstRoute(ctx: Context): String {
    BikeCompanion.reconcile(ctx)
    return if (Pairing.needsSetup(ctx.bikes.all())) Routes.SETUP else Routes.START
}

/** Step 1: what the search card shows. */
sealed interface SearchState {
    data object NeedsPermission : SearchState
    data object BluetoothOff : SearchState
    data object Searching : SearchState
    data class Found(val model: String, val mac: String, val signal: Signal) : SearchState
    data object NotFound : SearchState
}

/** Pure: a found display wins (also after the timeout, the scan keeps running); otherwise the blocker or progress. */
fun searchState(hasPermission: Boolean, bluetoothOn: Boolean, timedOut: Boolean, found: SeenDevice?): SearchState = when {
    !hasPermission -> SearchState.NeedsPermission
    !bluetoothOn -> SearchState.BluetoothOff
    found != null -> SearchState.Found(Pairing.modelName(found.name) ?: found.address, found.address, signal(found.rssi))
    timedOut -> SearchState.NotFound
    else -> SearchState.Searching
}

data class PairUiState(
    val search: SearchState,
    val name: String,
    /** The system pairing dialog is open. */
    val busy: Boolean = false,
    /** Companion pairing exists on this phone ("Anderes Display suchen" uses its chooser). */
    val canSearchOther: Boolean = true,
) {
    val canPair: Boolean get() = search is SearchState.Found && name.isNotBlank() && !busy
}

/** One line of the closing step. */
sealed interface DoneLine {
    data class Bike(val name: String, val displayModel: String?) : DoneLine
    data class Permission(val item: PermissionItem) : DoneLine
}

data class DoneUiState(
    val ready: List<DoneLine>,
    val skipped: List<PermissionItem>,
    /** The bike will connect by itself when switched on (paired with the system, auto-connect on, Bluetooth allowed). */
    val autoConnects: Boolean,
)

/** Pure: what was set up and what was skipped (with what does not work then). */
fun doneUiState(bikeName: String, displayModel: String?, items: List<PermissionItem>, associated: Boolean, autoConnect: Boolean): DoneUiState {
    val bluetooth = items.firstOrNull { it.permission == AppPermission.BLUETOOTH }?.granted == true
    return DoneUiState(
        ready = listOf<DoneLine>(DoneLine.Bike(bikeName, displayModel)) + items.filter { it.granted }.map { DoneLine.Permission(it) },
        skipped = items.filter { !it.granted },
        autoConnects = associated && autoConnect && bluetooth,
    )
}
