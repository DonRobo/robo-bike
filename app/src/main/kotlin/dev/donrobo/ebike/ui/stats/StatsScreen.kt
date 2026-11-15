package dev.donrobo.ebike.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ride.RideSettings
import dev.donrobo.ebike.ride.RecurringRoutes
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.ride.StatsPeriod
import dev.donrobo.ebike.settings.BikeKeys
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.stats.StatsSettings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.PillChipGroup
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.StatColumn
import dev.donrobo.ebike.ui.components.ValueRow
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.chart.AxisTick
import dev.donrobo.ebike.ui.components.chart.BarChart
import dev.donrobo.ebike.ui.components.chart.ChartScale
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.rides.FullScreenMap
import dev.donrobo.ebike.ui.rides.RidesHeader
import dev.donrobo.ebike.ui.rides.RouteComparison
import dev.donrobo.ebike.ui.components.TextInputDialog
import dev.donrobo.ebike.ui.rides.hoursMinutes
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.cssLineBox
import dev.donrobo.ebike.units.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Currency
import dev.donrobo.ebike.ui.components.rememberActiveBike
import dev.donrobo.ebike.ui.components.currentLocale

interface StatsActions {
    fun selectPeriod(p: StatsPeriod)
    fun editPrice()
    fun openBike()
    fun openRoute(id: String)
    fun expandHeat()
}

/** Statistik (artboard Statistik, in the Fahrten tab). */
@Composable
fun StatsScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bike by rememberActiveBike()
    val bikeId = bike?.id
    val rides by remember(bikeId) { bikeId?.let { RideRepository.summaries(ctx, it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList<RideSummary>())
    val groups by remember(bikeId) { bikeId?.let { RecurringRoutes.groups(ctx, it) } ?: flowOf(RouteGroups.EMPTY) }
        .collectAsStateWithLifecycle(RouteGroups.EMPTY)
    val capacity by remember(bikeId) { bikeId?.let { settings.flow(BikeKeys.batteryWh, it) } ?: flowOf(0) }
        .collectAsStateWithLifecycle(bikeId?.let { settings.get(BikeKeys.batteryWh, it) } ?: 0)
    val price by remember { settings.flow(StatsSettings.priceCentPerKwh) }.collectAsStateWithLifecycle(settings[StatsSettings.priceCentPerKwh])
    val heat by produceState(bikeId?.let { HeatCache.cached(it, rides) }, bikeId, rides) {
        value = bikeId?.let { id -> withContext(Dispatchers.Default) { HeatCache.get(id, rides) } }
    }
    var period by rememberSaveable { mutableStateOf(StatsPeriod.MONTH) }
    var editPrice by remember { mutableStateOf(false) }
    var fullHeat by rememberSaveable { mutableStateOf(false) }

    val gpsTrack by remember { settings.flow(RideSettings.gpsTrack) }.collectAsStateWithLifecycle(settings[RideSettings.gpsTrack])
    val state = statsUiState(rides, groups, period, LocalDate.now(), capacity, price, heat, gpsTrack = gpsTrack)
    if (fullHeat && heat != null) {
        BackHandler { fullHeat = false }
        FullScreenMap(MapContent(heat = heat)) { fullHeat = false }
        return
    }
    StatsContent(
        state,
        object : StatsActions {
            override fun selectPeriod(p: StatsPeriod) { period = p }
            override fun editPrice() { editPrice = true }
            override fun openBike() = nav.switchTab(Tab.BIKE)
            override fun openRoute(id: String) = nav.open(Routes.route(id))
            override fun expandHeat() { fullHeat = true }
        },
        nav,
    )
    if (editPrice) ElectricityPriceDialog { editPrice = false }
}

/** Edits the electricity price (F7); used by Statistik and Einstellungen. Writes the setting on save. */
@Composable
fun ElectricityPriceDialog(onDone: () -> Unit) {
    val settings = LocalContext.current.settings
    val price = remember { settings[StatsSettings.priceCentPerKwh] }
    TextInputDialog(
        stringResource(R.string.stats_price), formatPrice(price),
        onSave = { t -> parsePrice(t)?.let { settings[StatsSettings.priceCentPerKwh] = it }; onDone() },
        onDismiss = onDone,
        number = true, suffix = stringResource(R.string.stats_price_suffix),
        valid = { parsePrice(it) != null },
    )
}

/** "30" or "30,5" (whole cents without decimals). */
@Composable
fun formatPrice(centPerKwh: Double): String = rememberUnitFormatter().number(centPerKwh, if (centPerKwh % 1.0 == 0.0) 0 else 1)

/** Price input: decimal comma or point, within the accepted range. */
fun parsePrice(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in StatsSettings.MIN_PRICE..StatsSettings.MAX_PRICE }

/** Stateless Statistik (screenshot tests). */
@Composable
fun StatsContent(state: StatsUiState, actions: StatsActions, nav: AppNavigator) {
    Screen(header = { RidesHeader(nav, statsSelected = true) }) {
        PillChipGroup(
            listOf(stringResource(R.string.stats_week), stringResource(R.string.stats_month), stringResource(R.string.stats_year)),
            state.period.ordinal, { actions.selectPeriod(StatsPeriod.entries[it]) },
        )
        PeriodCard(state)
        CostCard(state, actions)
        RoutesCard(state, actions)
        HeatCard(state, actions)
    }
}

@Composable
private fun periodTitle(state: StatsUiState): String {
    val start = state.stats.total.start
    val locale = currentLocale()
    return when (state.period) {
        StatsPeriod.WEEK -> stringResource(R.string.stats_week_title, DateTimeFormatter.ofPattern(stringResource(R.string.stats_week_day_pattern), locale).format(start))
        StatsPeriod.MONTH -> DateTimeFormatter.ofPattern(stringResource(R.string.stats_month_pattern), locale).format(start)
        StatsPeriod.YEAR -> start.year.toString()
    }
}

@Composable
private fun PeriodCard(state: StatsUiState) {
    val fmt = rememberUnitFormatter()
    val c = AppTheme.colors
    val total = state.stats.total
    AppCard(contentPadding = PaddingValues(16.dp), gap = 12.dp) {
        Text(periodTitle(state), style = AppTheme.type.cardTitle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatColumn(
                fmt.distance(total.distanceKm, 0).value,
                stringResource(if (fmt.system == UnitSystem.METRIC) R.string.stats_km else R.string.stats_mi),
                Modifier.weight(1f), numberStyle = AppTheme.type.numberSmall,
            )
            StatColumn(hoursMinutes(total.durationS), stringResource(R.string.stats_hours), Modifier.weight(1f), numberStyle = AppTheme.type.numberSmall)
            StatColumn(fmt.number(total.charges, 1), stringResource(R.string.stats_charges), Modifier.weight(1f), numberStyle = AppTheme.type.numberSmall)
        }
        val values = state.stats.parts.map { fmt.toUnit(it.distanceKm).toFloat() }
        val max = values.maxOrNull() ?: 0f
        val unit = unitLabel(fmt.distanceUnit)
        val ref = ChartScale.reference(max.toDouble())?.let { AxisTick(it.toFloat(), "${fmt.number(it, 0)} $unit") }
        BarChart(
            values, barTicks(state), ref,
            stringResource(R.string.stats_chart_desc, "${fmt.number(max.toDouble(), 0)} $unit"),
        )
        if (total.rides == 0) Text(stringResource(R.string.stats_no_rides), style = AppTheme.type.caption, color = c.textMuted)
    }
}

/** Week: every weekday; month: 1., 10., 20., 30.; year: every third month. */
@Composable
private fun barTicks(state: StatsUiState): List<AxisTick> {
    val locale = currentLocale()
    val parts = state.stats.parts
    return when (state.period) {
        StatsPeriod.WEEK -> parts.mapIndexed { i, p -> AxisTick(i.toFloat(), p.start.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')) }
        StatsPeriod.MONTH -> listOf(1, 10, 20, 30).filter { it <= parts.size }.map { AxisTick((it - 1).toFloat(), stringResource(R.string.stats_day_label, it)) }
        StatsPeriod.YEAR -> listOf(0, 3, 6, 9).map { AxisTick(it.toFloat(), parts[it].start.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')) }
    }
}

@Composable
private fun CostCard(state: StatsUiState, actions: StatsActions) {
    val fmt = rememberUnitFormatter()
    val c = AppTheme.colors
    val locale = currentLocale()
    AppCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), gap = 4.dp) {
        Text(stringResource(R.string.stats_cost_title), style = AppTheme.type.cardTitle)
        val cost = state.cost
        if (cost == null) {
            Text(stringResource(R.string.stats_cost_needs_capacity), style = AppTheme.type.bodySmall, color = c.textMuted)
            TextButton(onClick = actions::openBike, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.stats_cost_to_bike), style = AppTheme.type.labelStrong, color = c.primary)
            }
        } else {
            val money = NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("EUR") }.format(cost.total)
            val when_ = when (state.period) {
                StatsPeriod.WEEK -> stringResource(R.string.stats_cost_week)
                StatsPeriod.MONTH -> stringResource(R.string.stats_cost_month, state.stats.total.start.month.getDisplayName(TextStyle.FULL_STANDALONE, locale))
                StatsPeriod.YEAR -> stringResource(R.string.stats_cost_year, state.stats.total.start.year)
            }
            val perUnit = cost.perKmCent?.let(fmt::perDistance)
            val detail = perUnit?.let { stringResource(R.string.stats_cost_detail, when_, fmt.number(it, 1), unitLabel(fmt.distanceUnit)) } ?: when_
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(money, Modifier.alignBy(LastBaseline).cssLineBox(AppTheme.type.numberTile), style = AppTheme.type.numberTile)
                Text(detail, Modifier.alignBy(LastBaseline).weight(1f), style = AppTheme.type.bodySmall, color = c.textMuted)
            }
        }
        Column(Modifier.padding(top = 6.dp)) {
            ValueRow(
                stringResource(R.string.stats_price),
                stringResource(R.string.stats_price_value, formatPrice(state.priceCentPerKwh)),
                actions::editPrice, Modifier.bleed(16.dp),
            )
        }
    }
}

@Composable
private fun RoutesCard(state: StatsUiState, actions: StatsActions) {
    val c = AppTheme.colors
    AppCard(contentPadding = PaddingValues(16.dp), gap = 10.dp) {
        Text(stringResource(R.string.stats_routes_title), style = AppTheme.type.cardTitle)
        if (state.routes.isEmpty()) {
            Text(
                stringResource(
                    when {
                        state.anyTrack -> R.string.stats_routes_empty
                        state.noTrackReason == NoTrackReason.NO_POSITIONS -> R.string.stats_routes_no_positions
                        else -> R.string.stats_routes_no_gps
                    },
                ),
                style = AppTheme.type.bodySmall, color = c.textMuted,
            )
        }
        state.routes.forEachIndexed { i, r ->
            if (i > 0) Hairline(Modifier.padding(vertical = 4.dp))
            RouteComparison(r, Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.openRoute(r.id) })
        }
    }
}

@Composable
private fun HeatCard(state: StatsUiState, actions: StatsActions) {
    val c = AppTheme.colors
    val desc = stringResource(R.string.stats_heat_desc)
    AppCard(contentPadding = PaddingValues(0.dp)) {
        if (state.anyTrack) {
            BikeMap(
                MapContent(heat = state.heat),
                Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = desc },
                onClick = if (state.heat != null) actions::expandHeat else null,
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CardTitle(stringResource(R.string.stats_heat_title))
            Text(
                stringResource(
                    when {
                        state.anyTrack -> R.string.stats_heat_body
                        state.noTrackReason == NoTrackReason.NO_POSITIONS -> R.string.stats_heat_no_positions
                        else -> R.string.stats_heat_empty
                    },
                ),
                style = AppTheme.type.bodySmall, color = c.textMuted,
            )
        }
    }
}
