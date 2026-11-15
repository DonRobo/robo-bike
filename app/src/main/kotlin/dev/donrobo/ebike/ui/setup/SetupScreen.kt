package dev.donrobo.ebike.ui.setup

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.BikeService
import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.bike.rememberDisplayInfo
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.pairing.SeenDevice
import dev.donrobo.ebike.pairing.Signal
import dev.donrobo.ebike.pairing.pickDisplay
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppListItem
import dev.donrobo.ebike.ui.components.AppTextField
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.IconBadge
import dev.donrobo.ebike.ui.components.LineIcon
import dev.donrobo.ebike.ui.components.ListGroup
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_TIMEOUT_MS = 20_000L

/** User actions of step 1. */
interface PairActions {
    fun setName(name: String)
    fun pair()
    fun searchOther()
    fun allowBluetooth()
    fun enableBluetooth()
}

/** User actions of step 3. */
interface DoneActions {
    fun changePermissions()
    fun finish()
}

/**
 * Guided setup (E1, E2): 1 find and pair the display and name the bike, 2 permissions, 3 summary. First launch
 * without a display re-pairs the existing bike (keeps its data); opened later from the settings it adds a bike.
 */
@Composable
fun SetupScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(1) }
    var bikeId by rememberSaveable { mutableStateOf<String?>(null) } // the bike paired in step 1
    val permissions = rememberPermissionRequests()
    val finish = { nav.restart(Routes.START) }

    when (step) {
        1 -> PairStep(permissions) { id -> bikeId = id; step = 2 }
        2 -> {
            BackHandler { finish() } // the display is paired; leaving here keeps it
            PermissionsStepContent(permissions.items, permissions.backgroundOptionLabel, permissions::request) { step = 3 }
            PermissionDisclosureHost(permissions)
        }
        else -> {
            BackHandler { step = 2 }
            val bike = bikeId?.let { ctx.bikes.get(it) }
            val display = rememberDisplayInfo(bikeId)
            val state = doneUiState(
                bike?.name.orEmpty(), display?.model,
                permissions.items,
                associated = BikeCompanion.associatedMacs(ctx).any { it.equals(bike?.displayMac, ignoreCase = true) },
                autoConnect = ctx.settings[ConnectionSettings.autoConnect],
            )
            DoneContent(
                state,
                object : DoneActions {
                    override fun changePermissions() { step = 2 }
                    override fun finish() = finish()
                },
            )
        }
    }
}

/** Step 1 wiring: own scan, Bluetooth permission, companion pairing. [onPaired] gets the bike's id. */
@Composable
private fun PairStep(permissions: PermissionRequests, onPaired: (String) -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current // resources follow configuration changes
    val bikes = ctx.bikes
    val snackbar = LocalSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scanner = remember { DisplayScanner(ctx) }
    val target = remember { Pairing.setupTarget(bikes.all()) }
    val targetId = (target as? Pairing.Target.Existing)?.bikeId
    val firstName = stringResource(R.string.bike_default_name)
    val numbered = stringResource(R.string.setup_name_numbered)
    var name by rememberSaveable {
        mutableStateOf(targetId?.let { bikes.get(it)?.name } ?: Pairing.defaultName(bikes.all(), firstName) { numbered.format(it) })
    }
    val seen = remember { mutableStateMapOf<String, SeenDevice>() }
    var bluetoothOn by remember { mutableStateOf(scanner.bluetoothOn()) }
    var timedOut by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scanRun by remember { mutableIntStateOf(0) }
    val hasPermission = permissions.facts.bluetoothScan
    val associatedBefore = remember { mutableStateOf(emptyList<String>()) }

    LifecycleResumeEffect(Unit) { bluetoothOn = scanner.bluetoothOn(); onPauseOrDispose { } }
    LaunchedEffect(hasPermission, bluetoothOn, busy, scanRun) {
        scanner.stop()
        if (!hasPermission || !bluetoothOn || busy) return@LaunchedEffect
        seen.clear()
        timedOut = false
        scanner.start { d -> ctx.mainExecutor.execute { seen[d.address] = d } }
        delay(SEARCH_TIMEOUT_MS)
        timedOut = true // the scan keeps running: a display switched on later still shows up
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    fun done(mac: String) {
        val r = BikeCompanion.adopt(ctx, target, mac, name, name.ifBlank { firstName })
        when (r) {
            is Pairing.Assign.Store -> {
                if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) BikeService.start(ctx)
                onPaired(r.bikeId!!)
            }
            is Pairing.Assign.AlreadyOwned -> scope.launch {
                snackbar.showSnackbar(resources.getString(R.string.setup_already_paired, bikes.get(r.bikeId)?.name.orEmpty()))
            }
        }
    }

    val associateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        busy = false
        val mac = if (res.resultCode == Activity.RESULT_OK) BikeCompanion.macFromResult(ctx, res.data, associatedBefore.value) else null
        if (mac != null) done(mac)
        else { scanRun++; scope.launch { snackbar.showSnackbar(resources.getString(R.string.setup_pair_cancelled)) } }
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { bluetoothOn = scanner.bluetoothOn() }

    fun associate(search: BikeCompanion.Search) {
        busy = true
        associatedBefore.value = BikeCompanion.associatedMacs(ctx)
        BikeCompanion.associate(
            ctx, search,
            onPending = { associateLauncher.launch(IntentSenderRequest.Builder(it).build()) },
            onFailure = { err ->
                busy = false
                Repo.log("setup: association failed: $err")
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.setup_pair_failed, err ?: resources.getString(R.string.conn_unknown_error))) }
            },
        )
    }

    val found = pickDisplay(seen.values, bikes.all(), targetId)
    val updateName: (String) -> Unit = { name = it }
    val state = PairUiState(searchState(hasPermission, bluetoothOn, timedOut, found), name, busy, BikeCompanion.supported(ctx))
    PairContent(
        state,
        object : PairActions {
            override fun setName(name: String) = updateName(name)
            override fun pair() {
                val f = state.search as? SearchState.Found ?: return
                scanner.stop()
                // Without companion support the display is simply remembered by its address (no auto-connect).
                if (BikeCompanion.supported(ctx)) associate(BikeCompanion.Search.Device(f.mac)) else done(f.mac)
            }
            override fun searchOther() { scanner.stop(); associate(BikeCompanion.Search.Any) }
            override fun allowBluetooth() = permissions.request(AppPermission.BLUETOOTH)
            override fun enableBluetooth() {
                runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
        },
    )
}

/** Step 1 (artboard Einrichtung), stateless. */
@Composable
fun PairContent(state: PairUiState, actions: PairActions) {
    SetupFrame(
        step = 1,
        bottom = {
            AppButton(stringResource(R.string.setup_pair), actions::pair, size = ButtonSize.HERO, enabled = state.canPair)
            if (state.canSearchOther) {
                AppButton(stringResource(R.string.setup_other_display), actions::searchOther, kind = ButtonKind.TEXT, size = ButtonSize.MEDIUM, enabled = !state.busy)
            }
        },
    ) {
        OrbitIllustration(OrbitCenter.DISPLAY)
        SetupIntro(stringResource(R.string.setup_pair_title), stringResource(R.string.setup_pair_body))
        SearchCard(state.search, actions)
        AppTextField(state.name, actions::setName, label = stringResource(R.string.setup_name_label))
    }
}

private val BluetoothIcon: LineIcon get() = AppIcons.Bluetooth

/** Search status card (padding 14/16, radius 20; found = 2 dp green border). */
@Composable
private fun SearchCard(search: SearchState, actions: PairActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val shape = RoundedCornerShape(20.dp)
    val found = search is SearchState.Found
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(if (found) 2.dp else 1.dp, if (found) c.primary else c.border, shape)
            .padding(if (found) 2.dp else 1.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (search) {
            is SearchState.Found -> IconBadge(AppIcons.Check, strokeWidth = 2f, container = c.primaryTint, content = c.primaryStrong)
            SearchState.Searching -> Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.track), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = c.primary, strokeWidth = 2.5.dp, trackColor = c.track)
            }
            SearchState.NotFound -> IconBadge(AppIcons.Warning, strokeWidth = 2f)
            SearchState.NeedsPermission, SearchState.BluetoothOff -> IconBadge(BluetoothIcon, strokeWidth = 2f, container = c.track, content = c.text)
        }
        val (title, sub) = when (search) {
            is SearchState.Found -> stringResource(R.string.setup_found, search.model) to stringResource(
                when (search.signal) {
                    Signal.GOOD -> R.string.setup_signal_good
                    Signal.OK -> R.string.setup_signal_ok
                    Signal.WEAK -> R.string.setup_signal_weak
                },
            )
            SearchState.Searching -> stringResource(R.string.setup_searching) to stringResource(R.string.setup_searching_sub)
            SearchState.NotFound -> stringResource(R.string.setup_not_found) to stringResource(R.string.setup_not_found_sub)
            SearchState.NeedsPermission -> stringResource(R.string.setup_need_bluetooth) to stringResource(R.string.setup_need_bluetooth_sub)
            SearchState.BluetoothOff -> stringResource(R.string.setup_bluetooth_off) to stringResource(R.string.setup_bluetooth_off_sub)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = t.cardTitle)
            Text(sub, style = t.bodySmall, color = c.textMuted)
        }
        when (search) {
            SearchState.NeedsPermission -> AppButton(stringResource(R.string.common_allow), actions::allowBluetooth, kind = ButtonKind.TONAL, size = ButtonSize.SMALL, fillWidth = false)
            SearchState.BluetoothOff -> AppButton(stringResource(R.string.setup_bluetooth_on), actions::enableBluetooth, kind = ButtonKind.TONAL, size = ButtonSize.SMALL, fillWidth = false)
            else -> {}
        }
    }
}


/** Step 3 (no artboard): what is set up, what was skipped and what does not work then. */
@Composable
fun DoneContent(state: DoneUiState, actions: DoneActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    SetupFrame(
        step = 3,
        bottom = {
            AppButton(stringResource(R.string.setup_done_start), actions::finish, size = ButtonSize.HERO)
            if (state.skipped.isNotEmpty()) {
                AppButton(stringResource(R.string.setup_done_change), actions::changePermissions, kind = ButtonKind.TEXT, size = ButtonSize.MEDIUM)
            }
        },
    ) {
        OrbitIllustration(OrbitCenter.CHECK)
        SetupIntro(
            stringResource(R.string.setup_done_title),
            stringResource(if (state.autoConnects) R.string.setup_done_body else R.string.setup_done_body_manual),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.setup_done_ready), Modifier.padding(bottom = 0.dp))
            ListGroup {
                state.ready.forEach { line ->
                    item {
                        when (line) {
                            is DoneLine.Bike -> DoneRow(
                                line.name.ifBlank { stringResource(R.string.bike_default_name) },
                                stringResource(R.string.setup_done_paired, line.displayModel ?: stringResource(R.string.setup_done_display_generic)),
                                ok = true,
                            )
                            is DoneLine.Permission -> DoneRow(permissionTitle(line.item.permission), null, ok = true)
                        }
                    }
                }
            }
            if (state.skipped.isNotEmpty()) {
                SectionLabel(stringResource(R.string.setup_done_skipped))
                ListGroup {
                    state.skipped.forEach { item -> item { DoneRow(permissionTitle(item.permission), skippedConsequence(item), ok = false) } }
                }
            }
        }
    }
}

@Composable
private fun skippedConsequence(item: PermissionItem): String = stringResource(
    when (item.permission) {
        AppPermission.BLUETOOTH -> R.string.setup_done_skip_bluetooth
        AppPermission.NOTIFICATION_ACCESS -> R.string.setup_done_skip_listener
        AppPermission.LOCATION -> if (item.state == PermissionState.PARTIAL) R.string.setup_done_skip_location_partial else R.string.setup_done_skip_location
        AppPermission.BACKGROUND_LOCATION -> R.string.setup_done_skip_bg_location
        AppPermission.BACKGROUND_RUN -> R.string.setup_done_skip_background
        AppPermission.NOTIFICATIONS -> R.string.setup_done_skip_notifications
    },
)

@Composable
private fun DoneRow(title: String, subtitle: String?, ok: Boolean) {
    val c = AppTheme.colors
    AppListItem(
        title, subtitle = subtitle,
        leading = {
            IconBadge(
                if (ok) AppIcons.Check else AppIcons.Minus, size = 32.dp, radius = 10.dp, iconSize = 18.dp, strokeWidth = 2.2f,
                container = if (ok) c.primaryTint else c.track, content = if (ok) c.primaryStrong else c.textMuted,
            )
        },
    )
}
