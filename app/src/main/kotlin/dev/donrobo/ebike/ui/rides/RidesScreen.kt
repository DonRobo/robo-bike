package dev.donrobo.ebike.ui.rides

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.ride.RecurringRoutes
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.ride.RideSettings
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.ChipSize
import dev.donrobo.ebike.ui.components.Chevron
import dev.donrobo.ebike.ui.components.PageHeader
import dev.donrobo.ebike.ui.components.ScreenFrame
import dev.donrobo.ebike.ui.components.pagePadding
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.SegmentedStyle
import dev.donrobo.ebike.ui.components.StatColumn
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.StatusChip
import dev.donrobo.ebike.ui.components.SwitchCard
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitSystem
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import kotlin.math.roundToInt
import dev.donrobo.ebike.ui.components.rememberActiveBike

/** Header shared by the Fahrten list and Statistik: title + Liste/Statistik switch. */
@Composable
fun RidesHeader(nav: AppNavigator, statsSelected: Boolean) {
    PageHeader(stringResource(R.string.rides_title)) {
        SegmentedControl(
            listOf(stringResource(R.string.rides_tab_list), stringResource(R.string.rides_tab_stats)),
            selectedIndex = if (statsSelected) 1 else 0,
            onSelect = { i -> if ((i == 1) != statsSelected) nav.open(if (i == 1) Routes.STATS else Routes.RIDES) },
            style = SegmentedStyle.HEADER,
        )
    }
}

/** User actions on the Fahrten list. */
interface RidesActions {
    fun openRide(id: String)
    fun setGpsTrack(on: Boolean)
    fun fixGps(limit: LocationLimit)
}

/** Fahrten (artboard Fahrten): week summary, GPS track switch (F2), rides by day (F1) with their route (F5). */
@Composable
fun RidesScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bike by rememberActiveBike()
    val bikeId = bike?.id
    val rides by remember(bikeId) { bikeId?.let { RideRepository.summaries(ctx, it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle<List<RideSummary>?>(null)
    val groups by remember(bikeId) { bikeId?.let { RecurringRoutes.groups(ctx, it) } ?: flowOf(RouteGroups.EMPTY) }
        .collectAsStateWithLifecycle(RouteGroups.EMPTY)
    val gpsTrack by remember { settings.flow(RideSettings.gpsTrack) }.collectAsStateWithLifecycle(settings[RideSettings.gpsTrack])
    val status by Locations.status.collectAsStateWithLifecycle()
    val recording by remember(bikeId) { bikeId?.let(Repo::recordingFor) ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    val limit = trackLimit(status.limit, Locations.permissions(ctx))

    val state = remember(rides, groups, gpsTrack, limit, recording) {
        ridesUiState(rides.orEmpty(), groups, gpsTrack, limit, recording, LocalDate.now(), loaded = rides != null)
    }
    val actions = remember(nav) {
        object : RidesActions {
            override fun openRide(id: String) = nav.open(Routes.ride(id))
            override fun setGpsTrack(on: Boolean) { settings[RideSettings.gpsTrack] = on }
            override fun fixGps(limit: LocationLimit) {
                if (limit == LocationLimit.SYSTEM_LOCATION_OFF) runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                else nav.open(Routes.PERMISSIONS)
            }
        }
    }
    RidesContent(state, actions, nav)
}

/** Stateless Fahrten list (screenshot tests). A lazy list, so years of rides stay smooth. */
@Composable
fun RidesContent(state: RidesUiState, actions: RidesActions, nav: AppNavigator, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    val today = LocalDate.now()
    ScreenFrame(header = { RidesHeader(nav, statsSelected = false) }, modifier) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pagePadding(inner),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "week") { WeekCard(state, fmt.system) }
            item(key = "gps") {
                SwitchCard(
                    stringResource(R.string.rides_gps_title), state.gpsTrack, actions::setGpsTrack,
                    subtitle = stringResource(R.string.rides_gps_sub), titleStyle = AppTheme.type.itemTitleStrong,
                    subtitleStyle = AppTheme.type.caption, verticalPadding = 10.dp,
                )
            }
            state.gpsLimit?.let { limit -> item(key = "gpsLimit") { GpsLimitBanner(limit) { actions.fixGps(limit) } } }
            if (state.loaded && state.days.isEmpty()) item(key = "empty") { EmptyCard() }
            for (day in state.days) {
                item(key = "d" + day.date) { SectionLabel(dayText(day.date, today), large = true) }
                items(day.items, key = { it.ride.id }) { RideCard(it) { actions.openRide(it.ride.id) } }
            }
        }
    }
}

@Composable
private fun WeekCard(state: RidesUiState, units: UnitSystem) {
    val c = AppTheme.colors
    val fmt = rememberUnitFormatter()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.primaryStrong).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.rides_week), style = AppTheme.type.label, color = c.onPrimaryStrongMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val w = state.week
            StatColumn(
                fmt.distance(w.distanceKm).value,
                stringResource(if (units == UnitSystem.METRIC) R.string.rides_km_caption else R.string.rides_mi_caption),
                Modifier.weight(1f), color = c.onPrimary, captionColor = c.onPrimaryStrongMuted,
            )
            StatColumn(hoursMinutes(w.durationS), stringResource(R.string.rides_hours_caption), Modifier.weight(1f), color = c.onPrimary, captionColor = c.onPrimaryStrongMuted)
            StatColumn(
                stringResource(R.string.percent_value, w.batteryPct.roundToInt()), stringResource(R.string.rides_battery_used_caption),
                Modifier.weight(1f), color = c.onPrimary, captionColor = c.onPrimaryStrongMuted,
            )
        }
    }
}

@Composable
private fun GpsLimitBanner(limit: LocationLimit, onFix: () -> Unit) {
    val text = stringResource(
        when (limit) {
            LocationLimit.NO_PERMISSION -> R.string.rides_gps_no_permission
            LocationLimit.SYSTEM_LOCATION_OFF -> R.string.rides_gps_system_off
            LocationLimit.NEEDS_BACKGROUND -> R.string.rides_gps_needs_background
            LocationLimit.TRACK_NEEDS_PRECISE -> R.string.rides_gps_needs_precise
        },
    )
    StatusBanner(text, tone = Tone.WARNING, icon = AppIcons.Warning) {
        TextButton(onClick = onFix) {
            Text(
                stringResource(if (limit == LocationLimit.SYSTEM_LOCATION_OFF) R.string.rides_gps_open_settings else R.string.common_allow),
                style = AppTheme.type.labelStrong, color = AppTheme.colors.onAccentTint,
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    AppCard(contentPadding = PaddingValues(16.dp), gap = 4.dp) {
        Text(stringResource(R.string.rides_empty_title), style = AppTheme.type.cardTitle)
        Text(stringResource(R.string.rides_empty_body), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
    }
}

@Composable
private fun RideCard(item: RideItem, onClick: () -> Unit) {
    val fmt = rememberUnitFormatter()
    val r = item.ride
    AppCard(contentPadding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RideThumbnail(r.id, r.shape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rides_time, clockText(r.startMs)), style = AppTheme.type.cardTitle, maxLines = 1)
                    when {
                        item.recording -> StatusChip(stringResource(R.string.rides_recording), tone = Tone.WARNING, size = ChipSize.SMALL)
                        item.route != null -> StatusChip(item.route.name ?: stringResource(R.string.rides_recurring), tone = if (item.route.name != null) Tone.POSITIVE else Tone.NEUTRAL, size = ChipSize.SMALL)
                    }
                }
                Text(stringResource(R.string.rides_distance_in, fmt.distance(r.distanceKm).text(), durationText(r.durationS)), style = AppTheme.type.body)
                Text(stringResource(R.string.rides_battery_from_to, r.batteryStartPct, r.batteryEndPct), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
            }
            Chevron()
        }
    }
}
