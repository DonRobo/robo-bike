package dev.donrobo.ebike.ui.bike

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.R
import dev.donrobo.ebike.bike.ChargeSettings
import dev.donrobo.ebike.bike.DisplayInfoStore
import dev.donrobo.ebike.bike.Odometer
import dev.donrobo.ebike.display.rememberDisplayCapabilities
import dev.donrobo.ebike.errors.ErrorLogStore
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationSettings
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.maintenance.Maintenance
import dev.donrobo.ebike.maintenance.MaintenanceSettings
import dev.donrobo.ebike.errors.DriveErrorState
import dev.donrobo.ebike.notify.NotifySettings
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.settings.BikeKeys
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.connection.rememberConnectionController
import dev.donrobo.ebike.ui.connection.rememberSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import dev.donrobo.ebike.ui.components.rememberActiveBike

/** Rad (artboard "Rad und Wartung"): W1-W5, bike management (A2) and "Display vergessen" (E6) for the active bike. */
@Composable
fun BikeScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bikes = ctx.bikes
    val snackbar = LocalSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val connection = rememberConnectionController(snackbar)

    val bike by rememberActiveBike()
    val allBikes by remember { bikes.allFlow() }.collectAsStateWithLifecycle(bikes.all())
    val id = bike?.id
    // Every per-bike value below is a stored setting: one flow of "anything changed" keeps them current.
    val tick by remember { settings.anyChange().map { System.nanoTime() } }.collectAsStateWithLifecycle(0L)
    val snapshot = rememberSnapshot(id)
    val driveError by DriveErrorState.current.collectAsStateWithLifecycle()
    val entries by remember(id) { id?.let { ErrorLogStore.entries(ctx, it) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val caps = rememberDisplayCapabilities(id)
    val units by remember { settings.flow(GeneralSettings.units) }.collectAsStateWithLifecycle(settings[GeneralSettings.units])
    var dialog by remember { mutableStateOf<BikeDialog?>(null) }

    tick; units // read so the state below is rebuilt on every settings change
    val connectedNow = snapshot?.live == true // this bike is the connected one (A2)
    val odo = snapshot?.report?.odoKm
    val state = bikeUiState(
        bike = bike, bikes = allBikes, odoKm = odo,
        parked = parkedUi(connectedNow, id?.let { settings.get(LocationSettings.parked, it) }, Locations.permissions(ctx)),
        maintenance = id?.let { MaintenanceSettings.statuses(settings, it, odo) }.orEmpty(),
        errors = errorSection(
            supported = caps.supports(DisplayFeature.ERROR_CODE), entries = entries,
            currentRaw = snapshot?.report?.errorRaw,
            live = connectedNow, currentSinceMs = driveError?.sinceMs,
        ),
        chargeRemind = settings[NotifySettings.charge],
        chargeThresholdPct = id?.let { settings.get(ChargeSettings.thresholdPct, it) } ?: ChargeSettings.thresholdPct.default,
        batteryWh = id?.let { settings.get(BikeKeys.batteryWh, it) } ?: 0,
        display = id?.let { DisplayInfoStore.get(settings, it) },
    )

    val noMapsApp = stringResource(R.string.bike_parked_no_app)
    val actions = remember(nav, connection) {
        object : BikeActions {
            fun current() = bikes.active()
            override fun selectBike(id: String) { bikes.setActive(id); dialog = null }
            override fun renameBike(name: String) { current()?.let { if (name.isNotBlank()) bikes.rename(it.id, name) }; dialog = null }
            override fun addBike() = nav.open(Routes.SETUP)
            override fun removeBike() {
                val b = current() ?: return
                BikeCompanion.removeBike(ctx, b.id)
                if (bikes.all().isEmpty()) nav.restart(Routes.SETUP)
            }
            override fun navigateTo(point: GeoPoint) {
                val label = Uri.encode(current()?.name.orEmpty())
                val uri = Uri.parse(String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", point.lat, point.lon, point.lat, point.lon, label))
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar(noMapsApp) } }
            }
            override fun fixLocation() = nav.open(Routes.PERMISSIONS)
            override fun markDone(itemId: String) {
                val b = current() ?: return
                val o = Odometer.now(settings, b.id) ?: return
                MaintenanceSettings.edit(settings, b.id) { Maintenance.markDone(it, itemId, o) }
            }
            override fun saveItem(itemId: String?, name: String?, intervalKm: Double) {
                val b = current() ?: return
                MaintenanceSettings.edit(settings, b.id) { items ->
                    if (itemId == null) Maintenance.add(items, name.orEmpty(), intervalKm, Odometer.now(settings, b.id))
                    else Maintenance.setInterval(items, itemId, intervalKm).let { if (name != null) Maintenance.rename(it, itemId, name) else it }
                }
            }
            override fun removeItem(itemId: String) { current()?.let { b -> MaintenanceSettings.edit(settings, b.id) { Maintenance.remove(it, itemId) } } }
            override fun setChargeRemind(on: Boolean) { settings[NotifySettings.charge] = on }
            override fun setChargeThreshold(pct: Int) { current()?.let { settings.set(ChargeSettings.thresholdPct, it.id, pct) } }
            override fun setBatteryWh(wh: Int) { current()?.let { settings.set(BikeKeys.batteryWh, it.id, wh) } }
            override fun forgetDisplay() { current()?.let { BikeCompanion.forget(ctx, it.id) }; connection.refresh() }
            override fun pairDisplay() = connection.connect()
        }
    }
    BikeContent(state, actions, dialog, { dialog = it })
}
