package dev.donrobo.ebike.ui.rides

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.ride.RecurringRoute
import dev.donrobo.ebike.ride.RecurringRoutes
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.ConfirmDialog
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ScreenFrame
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.IconBadge
import dev.donrobo.ebike.ui.components.RoundIconButton
import dev.donrobo.ebike.ui.components.SegmentBar
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.ValueTile
import dev.donrobo.ebike.ui.components.ValueTileStyle
import dev.donrobo.ebike.ui.components.chart.AxisTick
import dev.donrobo.ebike.ui.components.chart.ChartPoint
import dev.donrobo.ebike.ui.components.chart.ChartScale
import dev.donrobo.ebike.ui.components.chart.LineChart
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.map.MapMode
import dev.donrobo.ebike.ui.components.map.MapTrack
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fahrtdetail data. [charts] is null while the samples are read. */
data class RideDetailUiState(val ride: RideSummary, val route: RecurringRoute?, val charts: RideCharts?)

interface RideActions {
    fun back()
    fun expandMap()
    fun compare(routeId: String)
    fun shareGpx()
    fun delete()
}

enum class RideChart { SPEED, BATTERY, ALTITUDE, LEVEL }

/** Fahrtdetail (artboard Fahrt). [rideId] = ride CSV name without extension. */
@Composable
fun RideScreen(nav: AppNavigator, rideId: String) {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val bikeId = remember { ctx.bikes.active()?.id }
    val rides by remember(bikeId) { bikeId?.let { RideRepository.summaries(ctx, it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle<List<RideSummary>?>(null)
    val groups by remember(bikeId) { bikeId?.let { RecurringRoutes.groups(ctx, it) } ?: flowOf(RouteGroups.EMPTY) }
        .collectAsStateWithLifecycle(RouteGroups.EMPTY)
    val ride = rides?.firstOrNull { it.id == rideId }
    // re-read when the file changed (a ride still being recorded grows)
    val charts by produceState<RideCharts?>(null, ride?.file, ride?.endMs) {
        value = ride?.let { r -> withContext(Dispatchers.Default) { rideCharts(RideRepository.samples(r)) } }
    }
    var fullMap by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.ride_share_title)
    val shareFailed = stringResource(R.string.ride_share_failed)

    if (ride == null) {
        Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
            BackHeader(stringResource(R.string.ride_title), nav::back)
            Text(
                stringResource(if (rides == null) R.string.ride_loading else R.string.ride_not_found),
                Modifier.padding(20.dp), style = AppTheme.type.body, color = AppTheme.colors.textMuted,
            )
        }
        return
    }
    val actions = object : RideActions {
        override fun back() = nav.back()
        override fun expandMap() { fullMap = true }
        override fun compare(routeId: String) = nav.open(Routes.route(routeId))
        override fun shareGpx() {
            scope.launch {
                try {
                    val uri = RideRepository.exportGpx(ctx, ride)
                    val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, shareTitle))
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Repo.log("GPX export failed: $e")
                    snackbar.showSnackbar(shareFailed)
                }
            }
        }
        override fun delete() { confirmDelete = true }
    }
    if (fullMap) {
        BackHandler { fullMap = false }
        FullScreenMap(MapContent(tracks = listOf(MapTrack(charts?.track ?: ride.shape?.points.orEmpty())))) { fullMap = false }
        return
    }
    RideDetailContent(RideDetailUiState(ride, groups.byRide[ride.id], charts), actions)
    if (confirmDelete) ConfirmDialog(
        stringResource(R.string.ride_delete_title), stringResource(R.string.ride_delete_body), stringResource(R.string.ride_delete_confirm),
        onConfirm = {
            confirmDelete = false
            scope.launch { RideRepository.delete(ride); nav.back() }
        },
        onDismiss = { confirmDelete = false },
    )
}

/** Full-screen interactive map (pan/zoom) with a close button; used by the ride detail and the heatmap. */
@Composable
fun FullScreenMap(content: MapContent, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppTheme.colors.mapLand)) {
        BikeMap(content, Modifier.fillMaxSize(), mode = MapMode.INTERACTIVE)
        RoundIconButton(
            AppIcons.Close, stringResource(R.string.common_back), onClose,
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp), bordered = true,
        )
    }
}

/** Stateless Fahrtdetail (screenshot tests). */
@Composable
fun RideDetailContent(
    state: RideDetailUiState, actions: RideActions, modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(), initialChart: RideChart = RideChart.SPEED,
) {
    val fmt = rememberUnitFormatter()
    val r = state.ride
    val c = AppTheme.colors
    // The map starts under the transparent bar (round buttons over it); once content scrolls under the bar, it turns
    // opaque with the title, so nothing shows through the status bar or behind the buttons.
    val header = @Composable {
        BackHeader(stringResource(R.string.ride_title), actions::back, overMedia = true, trailing = {
            if (r.hasTrack) RoundIconButton(AppIcons.Expand, stringResource(R.string.ride_expand_map), actions::expandMap, Modifier.padding(end = 8.dp), bordered = true)
        })
    }
    ScreenFrame(header, modifier) { inner ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = inner.calculateBottomPadding())) {
            if (r.hasTrack) TrackHeader(state, actions) else NoGpsHeader()
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(dayText(Instant.ofEpochMilli(r.startMs).atZone(zone).toLocalDate()), style = AppTheme.type.detailTitle)
                    Text(stringResource(R.string.ride_time_range, clockText(r.startMs, zone), clockText(r.endMs, zone)), style = AppTheme.type.body, color = c.textMuted)
                }
                Figures(r, fmt)
                BatteryCard(r)
                ChartCard(state, fmt, initialChart)
                state.route?.let { route -> CompareRow { actions.compare(route.id) } }
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r.hasTrack) AppButton(stringResource(R.string.ride_share_gpx), actions::shareGpx, icon = AppIcons.Share)
                    AppButton(stringResource(R.string.ride_delete), actions::delete, kind = ButtonKind.DANGER_TEXT, size = ButtonSize.MEDIUM)
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(state: RideDetailUiState, actions: RideActions) {
    val points = state.charts?.track ?: state.ride.shape?.points.orEmpty()
    val desc = stringResource(R.string.ride_map_desc)
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        BikeMap(
            MapContent(tracks = listOf(MapTrack(points))),
            Modifier.fillMaxSize().semantics { contentDescription = desc }, onClick = actions::expandMap,
        )
    }
}

/** Designed header of a ride without GPS track: says why there is no map and how to get one. */
@Composable
private fun NoGpsHeader() {
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth().background(c.mapLand)) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 32.dp, end = 32.dp, top = 72.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconBadge(AppIcons.TabRides, container = c.surface, content = c.neutral, size = 52.dp, radius = 16.dp)
            Text(stringResource(R.string.ride_no_gps_title), Modifier.padding(top = 4.dp), style = AppTheme.type.cardTitle, textAlign = TextAlign.Center)
            Text(stringResource(R.string.ride_no_gps_body), style = AppTheme.type.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Figures(r: RideSummary, fmt: UnitFormatter) {
    val dist = fmt.distance(r.distanceKm)
    val (dur, durUnit) = durationParts(r.durationS)
    val speed = r.avgSpeedKmh()?.let { fmt.speed(it, 1) }
    val perUnit = r.pctPerKm()?.let(fmt::perDistance)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueTile(stringResource(R.string.ride_distance), dist.value, unitLabel(dist.unit), Modifier.weight(1f), ValueTileStyle.RIDE)
            ValueTile(stringResource(R.string.ride_duration), dur, durUnit, Modifier.weight(1f), ValueTileStyle.RIDE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueTile(
                stringResource(R.string.ride_average), speed?.value ?: "–", unitLabel(speed?.unit ?: fmt.speedUnit),
                Modifier.weight(1f), ValueTileStyle.RIDE,
            )
            ValueTile(
                stringResource(R.string.ride_consumption), perUnit?.let { fmt.number(it, 1) } ?: "–",
                stringResource(if (fmt.system == UnitSystem.IMPERIAL) R.string.ride_pct_per_mi else R.string.ride_pct_per_km),
                Modifier.weight(1f), ValueTileStyle.RIDE,
            )
        }
    }
}

@Composable
private fun BatteryCard(r: RideSummary) {
    val c = AppTheme.colors
    AppCard(contentPadding = PaddingValues(16.dp), gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ride_battery_line, r.batteryStartPct, r.batteryEndPct), Modifier.weight(1f), style = AppTheme.type.cardTitle)
            Text(stringResource(R.string.ride_battery_used, r.batteryUsedPct.roundToInt()), style = AppTheme.type.bodySmall, color = c.textMuted)
        }
        val end = r.batteryEndPct.coerceIn(0, 100) / 100f
        val used = (r.batteryStartPct - r.batteryEndPct).coerceIn(0, 100) / 100f
        SegmentBar(listOf(end to c.primary, used to c.accent))
    }
}

@Composable
private fun ChartCard(state: RideDetailUiState, fmt: UnitFormatter, initial: RideChart) {
    var chart by rememberSaveable { mutableStateOf(initial) }
    val c = AppTheme.colors
    val labels = listOf(R.string.ride_chart_speed, R.string.ride_chart_battery, R.string.ride_chart_altitude, R.string.ride_chart_level).map { stringResource(it) }
    AppCard(contentPadding = PaddingValues(16.dp), gap = 12.dp) {
        SegmentedControl(labels, chart.ordinal, { chart = RideChart.entries[it] })
        val charts = state.charts
        val imperial = fmt.system == UnitSystem.IMPERIAL
        val k = fmt.toUnit(1.0).toFloat() // km and km/h to the rider's unit
        val distUnit = unitLabel(fmt.distanceUnit)
        val note: String
        when {
            charts == null -> {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ride_loading), style = AppTheme.type.bodySmall, color = c.textMuted)
                }
                note = ""
            }
            chart == RideChart.ALTITUDE && charts.altitude == null -> {
                NoDataBox(stringResource(if (state.ride.hasTrack) R.string.ride_chart_no_altitude_old else R.string.ride_chart_no_altitude_gps))
                note = ""
            }
            else -> {
                val xMax = (charts.distanceKm * k).toFloat().coerceAtLeast(0.1f)
                val xTicks = distanceTicks(xMax, fmt, distUnit)
                val title = labels[chart.ordinal]
                val desc = stringResource(R.string.ride_chart_desc, title)
                when (chart) {
                    RideChart.SPEED -> {
                        val pts = charts.speed.map { ChartPoint(it.x * k, it.y * k) }
                        val ticks = ChartScale.ticks(0.0, (pts.maxOfOrNull { it.y } ?: 0f).toDouble().coerceAtLeast(10.0)).map { AxisTick(it.toFloat(), fmt.number(it, 0)) }
                        val avg = state.ride.avgSpeedKmh()?.let { (it * k).toFloat() }
                        LineChart(pts, 0f..xMax, ticks, xTicks, desc, average = avg)
                        note = stringResource(R.string.ride_chart_speed_note, unitLabel(fmt.speedUnit))
                    }
                    RideChart.BATTERY -> {
                        val pts = charts.battery.map { ChartPoint(it.x * k, it.y) }
                        val ticks = listOf(0, 50, 100).map { AxisTick(it.toFloat(), "$it") }
                        LineChart(pts, 0f..xMax, ticks, xTicks, desc)
                        note = stringResource(R.string.ride_chart_battery_note)
                    }
                    RideChart.ALTITUDE -> {
                        val f = if (imperial) (1 / UnitFormatter.M_PER_FT).toFloat() else 1f
                        val pts = charts.altitude!!.map { ChartPoint(it.x * k, it.y * f) }
                        val lo = pts.minOf { it.y }.toDouble(); val hi = pts.maxOf { it.y }.toDouble()
                        val ticks = ChartScale.ticks(lo, hi.coerceAtLeast(lo + 10)).map { AxisTick(it.toFloat(), fmt.number(it, 0)) }
                        LineChart(pts, 0f..xMax, ticks, xTicks, desc)
                        note = stringResource(R.string.ride_chart_altitude_note, stringResource(if (imperial) R.string.unit_ft else R.string.unit_m))
                    }
                    RideChart.LEVEL -> {
                        val pts = charts.level.map { ChartPoint(it.x * k, it.y) }
                        val top = charts.maxLevel.coerceAtLeast(1)
                        val ticks = ChartScale.ticks(0.0, top.toDouble(), top.coerceAtMost(5), integer = true).map { AxisTick(it.toFloat(), "${it.toInt()}") }
                        LineChart(pts, 0f..xMax, ticks, xTicks, desc, stepped = true)
                        note = stringResource(R.string.ride_chart_level_note)
                    }
                }
            }
        }
        if (note.isNotEmpty()) Text(note, style = AppTheme.type.caption, color = c.textMuted)
    }
}

/** "0 km", a round middle value, and the full distance with unit (artboard: 0 km · 5 · 10,6 km). */
private fun distanceTicks(max: Float, fmt: UnitFormatter, unit: String): List<AxisTick> {
    val mid = ChartScale.ticks(0.0, max.toDouble(), 3).filter { it > 0 && it < max * 0.85 }.minByOrNull { abs(it - max / 2) }
    return listOfNotNull(
        AxisTick(0f, "${fmt.number(0.0, 0)} $unit"),
        mid?.let { AxisTick(it.toFloat(), fmt.number(it, if (it % 1.0 == 0.0) 0 else 1)) },
        AxisTick(max, "${fmt.number(max.toDouble(), 1)} $unit"),
    )
}

@Composable
private fun NoDataBox(text: String) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = 150.dp).background(c.background, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        AppIcon(AppIcons.Warning, null, size = 22.dp, tint = c.neutral)
        Text(text, style = AppTheme.type.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompareRow(onClick: () -> Unit) {
    val c = AppTheme.colors
    AppCard(contentPadding = PaddingValues(horizontal = 16.dp), onClick = onClick) {
        Row(Modifier.heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ride_compare), Modifier.weight(1f), style = AppTheme.type.bodyStrong, color = c.primary)
            AppIcon(AppIcons.ChevronRight, null, size = 20.dp, tint = c.primary)
        }
    }
}
