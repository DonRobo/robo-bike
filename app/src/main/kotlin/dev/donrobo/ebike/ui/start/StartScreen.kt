package dev.donrobo.ebike.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.counters.CounterSettings
import dev.donrobo.ebike.counters.CounterState
import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.counters.decimals
import dev.donrobo.ebike.counters.labelRes
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.bike.rememberDisplayInfo
import dev.donrobo.ebike.display.rememberDisplayCapabilities
import dev.donrobo.ebike.errors.DriveErrorState
import dev.donrobo.ebike.ui.bike.DriveErrorBanner
import dev.donrobo.ebike.maintenance.MaintenanceState
import dev.donrobo.ebike.nav.NavSettings
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppSwitch
import dev.donrobo.ebike.ui.components.ChipSize
import dev.donrobo.ebike.ui.components.Chevron
import dev.donrobo.ebike.ui.components.HeroCard
import dev.donrobo.ebike.ui.components.IconBadge
import dev.donrobo.ebike.ui.components.NumberWithUnit
import dev.donrobo.ebike.ui.components.ProgressBar
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.StatusChip
import dev.donrobo.ebike.ui.components.StatusDot
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.ValueTile
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.textRes
import dev.donrobo.ebike.ui.connection.rememberSnapshot
import dev.donrobo.ebike.ui.light.lightShortText
import dev.donrobo.ebike.ui.connection.rememberConnectionController
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.ui.components.rememberActiveBike
import dev.donrobo.ebike.ui.components.currentLocale
import dev.donrobo.ebike.ui.components.formatAsOf

/** User actions on the Start screen. */
interface StartActions {
    fun openRoute(route: String)
    fun selectBike(id: String)
    fun setLight(on: Boolean)
    fun openConnection()
}

/** Start (artboard Main), wired to [Repo], the bike's settings and the feature state holders. */
@Composable
fun StartScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bikes = ctx.bikes
    val snackbar = LocalSnackbar.current
    val connection = rememberConnectionController(snackbar)

    val bike by rememberActiveBike()
    val allBikes by remember { bikes.allFlow() }.collectAsStateWithLifecycle(bikes.all())
    val bikeId = bike?.id
    val snapshot = rememberSnapshot(bikeId)
    val forwardNav by remember { settings.flow(NavSettings.forward) }.collectAsStateWithLifecycle(settings[NavSettings.forward])
    val running by Repo.running.collectAsStateWithLifecycle()
    val display = rememberDisplayInfo(bikeId)
    val light by Repo.light.collectAsStateWithLifecycle()
    val mapsNav by Repo.mapsNav.collectAsStateWithLifecycle()
    val testNav by Repo.testNav.collectAsStateWithLifecycle()
    val range by RangeState.estimate.collectAsStateWithLifecycle()
    val rangeFull by RangeState.full.collectAsStateWithLifecycle()
    val home by RangeState.home.collectAsStateWithLifecycle()
    val maintenance by MaintenanceState.next.collectAsStateWithLifecycle()
    val capabilities = rememberDisplayCapabilities(bikeId)
    val driveError by DriveErrorState.current.collectAsStateWithLifecycle()
    val counter by remember { settings.flow(CounterSettings.start) }.collectAsStateWithLifecycle(settings[CounterSettings.start])
    val counters by CounterState.inputs.collectAsStateWithLifecycle()

    val state = startUiState(
        bike, allBikes, connection.status(), display, snapshot, light,
        range, home, mapsNav, testNav, forwardNav, maintenance, capabilities, rangeFull, driveError, counter, counters,
    )
    var sheet by remember { mutableStateOf(false) }
    val actions = remember(nav, connection) {
        object : StartActions {
            // Tab roots (the maintenance card leads to the Rad tab) switch tabs instead of stacking a second copy.
            override fun openRoute(route: String) = dev.donrobo.ebike.ui.Tab.entries.firstOrNull { it.route == route }?.let(nav::switchTab) ?: nav.open(route)
            override fun selectBike(id: String) = bikes.setActive(id)
            override fun setLight(on: Boolean) = connection.setLight(on, "start")
            override fun openConnection() { sheet = true }
        }
    }
    StartContent(state, actions)
    if (sheet) ConnectionSheet(connection, state.connection, running, onDismiss = { sheet = false })
}

/** Stateless Start screen (used by the screenshot test with fixed data). */
@Composable
fun StartContent(state: StartUiState, actions: StartActions, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
        StartHeader(state, actions)
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.driveError?.let { DriveErrorBanner(it, { actions.openRoute(Routes.BIKE) }) }
            RangeCard(state, fmt) { actions.openRoute(Routes.RANGE) }
            Tiles(state, fmt)
            LightCard(state, actions)
            state.nav?.let { NavCard(it, state.navSource, fmt) { actions.openRoute(Routes.NAVIGATION) } }
            state.maintenance?.let { MaintenanceCard(it, fmt) { actions.openRoute(Routes.BIKE) } }
        }
    }
}

@Composable
private fun StartHeader(state: StartUiState, actions: StartActions) {
    val c = AppTheme.colors
    // Only the bike name shares its row with the handlebar button; the status lines below use the full width.
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { BikeSwitcher(state, actions) }
            AppButton(
                stringResource(R.string.start_handlebar), { actions.openRoute(Routes.HANDLEBAR) },
                kind = ButtonKind.OUTLINED, size = ButtonSize.SMALL, icon = AppIcons.HandlebarMode, fillWidth = false,
            )
        }
        // A text button (ripple, 48 dp target) whose label is the status line; opens the connection sheet.
        TextButton(
            actions::openConnection, Modifier.offset(x = (-12).dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            StatusDot(
                connectionText(state),
                tone = when (state.connection) {
                    ConnectionStatus.CONNECTED -> Tone.POSITIVE
                    ConnectionStatus.CONNECTING, ConnectionStatus.WAITING -> Tone.WARNING
                    ConnectionStatus.LOST, ConnectionStatus.OFF -> Tone.NEUTRAL
                },
            )
        }
        state.lastKnownAtMs?.let { at ->
            Text(
                stringResource(R.string.start_as_of, formatAsOf(at, currentLocale())),
                Modifier.padding(start = 16.dp), style = AppTheme.type.caption, color = c.textMuted,
            )
        }
    }
}

@Composable
private fun BikeSwitcher(state: StartUiState, actions: StartActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.start_switch_bike)) { open = true }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.bike?.name.orEmpty(), style = AppTheme.type.bikeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AppIcon(AppIcons.ChevronDown, null, size = 20.dp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            state.bikes.forEach { b ->
                val active = b.id == state.bike?.id
                DropdownMenuItem(
                    text = { Text(b.name, style = if (active) AppTheme.type.itemTitleStrong else AppTheme.type.itemTitle) },
                    onClick = { open = false; actions.selectBike(b.id) },
                    trailingIcon = if (active) ({ AppIcon(AppIcons.Check, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

@Composable
private fun connectionText(state: StartUiState): String {
    return when (state.connection) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.conn_connected, state.displayModel ?: stringResource(R.string.conn_display_generic))
        else -> stringResource(state.connection.textRes(detail = true))
    }
}

@Composable
private fun RangeCard(state: StartUiState, fmt: UnitFormatter, onClick: () -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val range = state.range
    val battery = state.report?.batteryPct
    HeroCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.start_range_label), style = t.label, color = c.textMuted)
                val m = range?.let { fmt.distance(it.remainingKm, 0) }
                if (m != null) NumberWithUnit(m.value, unitLabel(m.unit), t.numberHero, t.unitLarge, gap = 6.dp)
                else dev.donrobo.ebike.ui.range.UnknownHeroNumber(unitLabel(fmt.distanceUnit))
            }
            if (battery != null) StatusChip(stringResource(R.string.percent_value, battery), icon = AppIcons.Battery, size = ChipSize.LARGE)
        }
        ProgressBar((battery ?: 0) / 100f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val full = state.rangeFull
            if (full != null) {
                Text(stringResource(R.string.start_range_full, fmt.distance(full.fullKm, 0).text()), style = t.captionMedium, color = c.textMuted)
                val pct = fmt.number(fmt.perDistance(full.pctPerKm), 1)
                Text(stringResource(R.string.start_range_consumption, pct, unitLabel(fmt.distanceUnit)), style = t.captionMedium, color = c.textMuted)
            } else {
                Text(stringResource(R.string.start_range_learning), style = t.captionMedium, color = c.textMuted)
            }
        }
        state.home?.let { h ->
            val dist = fmt.distance(h.distanceKm, 1).text()
            StatusBanner(
                stringResource(if (h.reachable) R.string.start_home_ok else R.string.start_home_tight, dist),
                tone = if (h.reachable) Tone.POSITIVE else Tone.WARNING,
                icon = if (h.reachable) AppIcons.HomeCheck else AppIcons.Warning,
            )
        }
    }
}

@Composable
private fun Tiles(state: StartUiState, fmt: UnitFormatter) {
    val r = state.report
    val none = stringResource(R.string.start_no_value)
    // Speed and assist level are only meaningful live (like the Lenkermodus tiles); distances keep the last known value.
    val live = r?.takeIf { state.live }
    val speed = live?.let { fmt.speed(it.speedKmh) }
    val counter = state.counterReading
    val trip = counter.km?.let { fmt.distance(it, state.counter.decimals()) }
    val total = r?.let { fmt.distance(it.odoKm, 0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueTile(stringResource(R.string.start_speed), speed?.value ?: none, unitLabel(speed?.unit ?: fmt.speedUnit), Modifier.weight(1f))
            ValueTile(stringResource(R.string.start_assist), live?.gear?.toString() ?: none, live?.let { stringResource(R.string.start_assist_of, it.gears) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The rider's chosen counter (Einstellungen); between rides "Diese Fahrt" is the last ride, shown muted.
            ValueTile(
                stringResource(state.counter.labelRes()), trip?.value ?: none, unitLabel(trip?.unit ?: fmt.distanceUnit), Modifier.weight(1f),
                muted = trip != null && counter.stale && state.counter == DistanceCounter.THIS_RIDE,
            )
            ValueTile(stringResource(R.string.start_total), total?.value ?: none, unitLabel(total?.unit ?: fmt.distanceUnit), Modifier.weight(1f))
        }
    }
}

@Composable
private fun LightCard(state: StartUiState, actions: StartActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val on = state.lightOn
    AppCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), gap = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                AppIcons.Bulb,
                container = if (on) c.accentTint else c.track,
                content = if (on) c.onAccentTint else c.textMuted,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(if (on) R.string.common_light_is_on else R.string.common_light_is_off), style = t.cardTitle)
                Text(
                    if (!state.canSwitchLight) stringResource(R.string.setup_light_display_only) else lightShortText(state.light, on),
                    style = t.bodySmall, color = c.textMuted,
                )
            }
            if (state.canSwitchLight) {
                AppSwitch(on, actions::setLight, enabled = state.lightControl.switchable, contentDescription = stringResource(R.string.start_light_toggle))
            }
        }
        // Q4: without a lamp switch there is no automation to set up either.
        if (state.canSwitchLight) {
            TextButton({ actions.openRoute(Routes.LIGHT) }, Modifier.fillMaxWidth().bleed(12.dp)) {
                Text(stringResource(R.string.start_sun_curve), Modifier.weight(1f), style = t.label)
                AppIcon(AppIcons.ChevronRight, null, size = 20.dp)
            }
        }
    }
}

@Composable
private fun NavCard(nav: dev.donrobo.ebike.nav.NavState, source: NavSource, fmt: UnitFormatter, onClick: () -> Unit) {
    val c = AppTheme.colors
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                AppIcons.maneuver(nav.icon), size = 52.dp, radius = 16.dp, iconSize = 30.dp, strokeWidth = 2.2f,
                container = c.primary, content = c.onPrimary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.start_nav_turn, fmt.shortDistance(nav.distM.toDouble()).text(), maneuverPhrase(nav.icon)),
                    style = AppTheme.type.cardTitle,
                )
                Text(
                    stringResource(
                        when (source) {
                            NavSource.MAPS -> R.string.start_nav_from_maps
                            NavSource.MAPS_NOT_SENT -> R.string.start_nav_not_sent
                            NavSource.TEST -> R.string.start_nav_test
                        },
                    ),
                    style = AppTheme.type.bodySmall, color = c.textMuted,
                )
            }
            Chevron()
        }
    }
}

@Composable
fun maneuverPhrase(icon: Icon): String = stringResource(
    when (icon) {
        Icon.UNKNOWN -> R.string.nav_maneuver_unknown
        Icon.STRAIGHT -> R.string.nav_maneuver_straight
        Icon.LEFT -> R.string.nav_maneuver_left
        Icon.RIGHT -> R.string.nav_maneuver_right
        Icon.SLIGHT_LEFT -> R.string.nav_maneuver_slight_left
        Icon.SLIGHT_RIGHT -> R.string.nav_maneuver_slight_right
        Icon.SHARP_LEFT -> R.string.nav_maneuver_sharp_left
        Icon.SHARP_RIGHT -> R.string.nav_maneuver_sharp_right
        Icon.UTURN -> R.string.nav_maneuver_uturn
        Icon.ARRIVED -> R.string.nav_maneuver_arrived
    },
)

@Composable
private fun MaintenanceCard(m: dev.donrobo.ebike.maintenance.MaintenanceDue, fmt: UnitFormatter, onClick: () -> Unit) {
    val c = AppTheme.colors
    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(AppIcons.Wrench, null, size = 22.dp, strokeWidth = 1.8f, tint = c.onAccentTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dev.donrobo.ebike.ui.bike.maintenanceName(m), style = AppTheme.type.body.copy(fontWeight = AppTheme.type.cardTitle.fontWeight))
                Text(dev.donrobo.ebike.ui.bike.maintenanceDueText(m.dueInKm, fmt), style = AppTheme.type.bodySmall, color = c.textMuted)
            }
            Chevron()
        }
    }
}
