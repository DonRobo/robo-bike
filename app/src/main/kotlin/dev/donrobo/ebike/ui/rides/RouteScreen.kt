package dev.donrobo.ebike.ui.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ride.RecurringRoute
import dev.donrobo.ebike.ride.RecurringRoutes
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppListItem
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.currentLocale
import dev.donrobo.ebike.ui.components.TextInputDialog
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.Chevron
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.InsetPanel
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.ValueRow
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

interface RouteActions {
    fun back()
    fun rename()
    fun openRide(id: String)
}

/**
 * Route comparison (F5, no artboard; opened from "Mit früheren Fahrten vergleichen" and the Statistik
 * route card): name the route, last ride vs. average, and every ride on it.
 */
@Composable
fun RouteScreen(nav: AppNavigator, routeId: String) {
    val ctx = LocalContext.current
    val bikeId = remember { ctx.bikes.active()?.id }
    val groups by remember(bikeId) { bikeId?.let { RecurringRoutes.groups(ctx, it) } ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    // naming an unnamed route changes its id: follow it through one of its rides
    var rideIds by remember { mutableStateOf(emptyList<String>()) }
    val g = groups
    val route = g?.route(routeId) ?: g?.let { gg -> rideIds.firstNotNullOfOrNull { gg.byRide[it] } }
    if (route != null) rideIds = route.rides.map { it.id }
    var naming by remember { mutableStateOf(false) }
    val actions = object : RouteActions {
        override fun back() = nav.back()
        override fun rename() { naming = true }
        override fun openRide(id: String) = nav.open(Routes.ride(id))
    }
    if (route == null) {
        Screen(header = { BackHeader(stringResource(R.string.route_unnamed), nav::back) }) {
            Text(stringResource(if (g == null) R.string.ride_loading else R.string.route_gone), style = AppTheme.type.body, color = AppTheme.colors.textMuted)
        }
        return
    }
    RouteContent(route, actions)
    if (naming && bikeId != null) TextInputDialog(
        stringResource(R.string.route_name_title), route.name.orEmpty(),
        onSave = { RecurringRoutes.rename(ctx, bikeId, route, it); naming = false },
        onDismiss = { naming = false },
        placeholder = stringResource(R.string.route_name_hint),
    )
}

@Composable
fun RouteContent(route: RecurringRoute, actions: RouteActions, zone: ZoneId = ZoneId.systemDefault()) {
    val c = AppTheme.colors
    Screen(header = { BackHeader(route.name ?: stringResource(R.string.route_unnamed), actions::back) }, bodyTop = 4.dp) {
        AppCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), gap = 10.dp) {
            RouteComparison(route)
            Text(stringResource(R.string.route_rides_count, route.rides.size), style = AppTheme.type.bodySmall, color = c.textMuted)
            ValueRow(stringResource(R.string.route_name_row), route.name ?: stringResource(R.string.route_name_set), actions::rename, Modifier.bleed(16.dp))
        }
        AppCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            Text(stringResource(R.string.route_rides), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), style = AppTheme.type.cardTitle)
            val avg = route.avgDurationS
            route.rides.forEachIndexed { i, r ->
                if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
                val date = Instant.ofEpochMilli(r.startMs).atZone(zone).toLocalDate()
                val day = java.time.format.DateTimeFormatter.ofPattern(stringResource(R.string.route_day_pattern), currentLocale()).format(date)
                val delta = r.durationS - avg
                AppListItem(
                    "$day, ${stringResource(R.string.rides_time, clockText(r.startMs, zone))}",
                    Modifier.clickable(role = Role.Button) { actions.openRide(r.id) },
                    subtitle = when {
                        abs(delta) < 30 -> stringResource(R.string.route_delta_same)
                        delta < 0 -> stringResource(R.string.route_delta_faster, durationText(abs(delta).toLong()))
                        else -> stringResource(R.string.route_delta_slower, durationText(delta.toLong()))
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(durationText(r.durationS), style = AppTheme.type.bodyStrong)
                                Text(stringResource(R.string.percent_value, r.batteryUsedPct.roundToInt()), style = AppTheme.type.caption, color = c.textMuted)
                            }
                            Chevron()
                        }
                    },
                )
            }
        }
        Text(stringResource(R.string.route_how), Modifier.padding(horizontal = 4.dp), style = AppTheme.type.caption, color = c.textMuted)
    }
}

/** Name + typical length and the "Zuletzt" / "Im Schnitt" panels (artboard Statistik, Wiederkehrende Strecken). */
@Composable
fun RouteComparison(route: RecurringRoute, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    val c = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(route.name ?: stringResource(R.string.route_unnamed), Modifier.weight(1f), style = AppTheme.type.itemTitleStrong)
            Text(fmt.distance(route.distanceKm).text(), style = AppTheme.type.bodySmall, color = c.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComparisonPanel(stringResource(R.string.route_last), route.last.durationS, route.last.batteryUsedPct, Modifier.weight(1f))
            ComparisonPanel(stringResource(R.string.route_average), route.avgDurationS.toLong(), route.avgBatteryPct, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComparisonPanel(label: String, durationS: Long, batteryPct: Double, modifier: Modifier) {
    InsetPanel(modifier, PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = AppTheme.type.caption, color = AppTheme.colors.textMuted)
        Text(stringResource(R.string.route_time_battery, durationText(durationS), batteryPct.roundToInt()), style = AppTheme.type.bodyStrong.copy(fontWeight = AppTheme.type.cardTitle.fontWeight))
    }
}
