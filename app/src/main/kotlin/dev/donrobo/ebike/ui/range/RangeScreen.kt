package dev.donrobo.ebike.ui.range

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.range.BikePosition
import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.HomeCalc
import dev.donrobo.ebike.range.HomeLevel
import dev.donrobo.ebike.range.HomeStatus
import dev.donrobo.ebike.range.RangeCalc
import dev.donrobo.ebike.range.RangeDetail
import dev.donrobo.ebike.range.RangeModel
import dev.donrobo.ebike.range.RangeSettings
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppSwitch
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.HeroCard
import dev.donrobo.ebike.ui.components.NumberWithUnit
import dev.donrobo.ebike.ui.components.ProgressBar
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.Stepper
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.ValueRow
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.map.MapMarker
import dev.donrobo.ebike.ui.components.map.MapRing
import dev.donrobo.ebike.ui.components.map.MarkerKind
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import kotlinx.coroutines.flow.flowOf
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.roundToInt
import dev.donrobo.ebike.ui.components.rememberActiveBike
import dev.donrobo.ebike.ui.components.currentLocale

/** User actions on the Reichweite screen. */
interface RangeActions {
    fun back()
    fun setWindow(rides: Int)
    fun setMargin(pct: Int)
    fun setHomeEnabled(on: Boolean)
    fun setReserveKm(km: Double)
    fun pickHome()
}

/** Reichweite (artboard "Reichweite"), wired to [RangeState] and the range settings. */
@Composable
fun RangeScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bike by rememberActiveBike()
    val id = bike?.id

    @Composable
    fun <T> perBike(s: Setting<T>): T {
        val v by remember(id) { id?.let { settings.flow(s, it) } ?: flowOf(s.default) }
            .collectAsStateWithLifecycle(id?.let { settings.get(s, it) } ?: s.default)
        return v
    }

    @Composable
    fun <T> global(s: Setting<T>): T {
        val v by remember { settings.flow(s) }.collectAsStateWithLifecycle(settings[s])
        return v
    }
    val detail by RangeState.detail.collectAsStateWithLifecycle()
    val state = RangeUiState(
        detail = detail,
        window = perBike(RangeSettings.window),
        marginPct = perBike(RangeSettings.marginPct),
        homeEnabled = global(RangeSettings.homeEnabled),
        home = global(RangeSettings.home),
        reserveKm = global(RangeSettings.reserveKm),
    )
    var picking by remember { mutableStateOf(false) }
    val actions = remember(id, nav) {
        object : RangeActions {
            override fun back() = nav.back()
            override fun setWindow(rides: Int) { id?.let { settings.set(RangeSettings.window, it, rides) } }
            override fun setMargin(pct: Int) { id?.let { settings.set(RangeSettings.marginPct, it, pct) } }
            override fun setHomeEnabled(on: Boolean) { settings[RangeSettings.homeEnabled] = on }
            override fun setReserveKm(km: Double) { settings[RangeSettings.reserveKm] = km }
            override fun pickHome() { picking = true }
        }
    }
    RangeContent(state, actions)
    if (picking) {
        HomePicker(
            initial = state.home,
            near = detail?.position?.point,
            onSave = { settings[RangeSettings.home] = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** Stateless Reichweite screen (used by the screenshot tests with fixed data). */
@Composable
fun RangeContent(state: RangeUiState, actions: RangeActions, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    Screen(header = { BackHeader(stringResource(R.string.range_title), actions::back) }, modifier = modifier) {
        val d = state.detail
        SummaryCard(d, fmt)
        LevelsCard(d, fmt)
        RingCard(d, state.home, fmt)
        HomeCard(state, actions, fmt)
        HealthCard(d, fmt)
        ModelCard(state, actions, fmt)
    }
}

// --- summary (R1, R2) ------------------------------------------------------------

@Composable
private fun SummaryCard(d: RangeDetail?, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val battery = d?.reading?.batteryPct
    val est = d?.estimate
    val full = d?.full
    HeroCard(gap = 14.dp) {
        when (summaryMode(d)) {
            RangeSummaryMode.KNOWN -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigValue(
                    if (battery != null) stringResource(R.string.range_now, battery) else stringResource(R.string.range_now_plain),
                    est!!.remainingKm, fmt, Modifier.weight(1f),
                )
                BigValue(stringResource(R.string.range_full), est.fullKm, fmt, Modifier.weight(1f))
            }
            // learned, battery level unknown: the full-battery range is known and worth showing, "now" is not
            RangeSummaryMode.BATTERY_UNKNOWN -> BigValue(stringResource(R.string.range_full), full!!.fullKm, fmt, Modifier)
            RangeSummaryMode.LEARNING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.range_learning_title), style = t.cardTitle)
                Text(learningText(d, fmt), style = t.bodySmall, color = c.textMuted)
            }
        }
        ProgressBar((battery ?: 0) / 100f)
        if (full != null) {
            val word = stringResource(if (fmt.system == UnitSystem.IMPERIAL) R.string.range_unit_word_mi else R.string.range_unit_word_km)
            val rate = fmt.number(fmt.perDistance(full.pctPerKm), 1)
            val n = d!!.learned.ridesUsed
            val level = d.reading?.level?.takeIf { RangeCalc.ownLevelRate(d.learned, it) != null }
            Text(
                if (level != null) pluralStringResource(R.plurals.range_learned_from_level, n, n, rate, word, level)
                else pluralStringResource(R.plurals.range_learned_from, n, n, rate, word),
                style = t.bodySmall, color = c.textMuted,
            )
        }
        if (battery == null) Text(stringResource(R.string.range_no_battery), style = t.bodySmall, color = c.textMuted)
    }
}

@Composable
private fun learningText(d: RangeDetail?, fmt: UnitFormatter): String {
    val needKm = fmt.distance(RangeModel.READY_KM, 0).text()
    val needPct = fmt.number(RangeModel.READY_PCT, 0)
    val l = d?.learned
    return if (l == null || l.ridesUsed == 0) stringResource(R.string.range_learning_none, needKm, needPct)
    else stringResource(R.string.range_learning_progress, fmt.distance(l.km, 1).text(), fmt.number(l.pct, 0), needKm, needPct)
}

@Composable
private fun BigValue(label: String, km: Double, fmt: UnitFormatter, modifier: Modifier) {
    val t = AppTheme.type
    val m = fmt.distance(km, 0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = t.label, color = AppTheme.colors.textMuted)
        NumberWithUnit(m.value, unitLabel(m.unit), t.numberLarge, t.unitMedium)
    }
}

// --- per level (R3) --------------------------------------------------------------

@Composable
private fun LevelsCard(d: RangeDetail?, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val bars = levelBars(d?.levels.orEmpty(), d?.reading?.level)
    AppCard(gap = 12.dp) {
        Text(stringResource(R.string.range_levels_title), style = t.cardTitle)
        if (d == null || !d.learned.ready || bars.isEmpty()) {
            Text(stringResource(R.string.range_levels_empty), style = t.caption, color = c.textMuted)
            return@AppCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            bars.forEach { b -> LevelRow(b, fmt) }
        }
        val notes = buildList {
            val r = d.reading
            when {
                r == null -> add(stringResource(R.string.range_level_full_battery))
                r.level > 0 && r.live -> add(stringResource(R.string.range_level_current, r.level))
                r.level > 0 -> add(stringResource(R.string.range_level_last, r.level))
            }
            if (bars.any { it.confidence == Confidence.ESTIMATED }) add(stringResource(R.string.range_levels_legend_estimated))
            if (bars.any { it.confidence == Confidence.UNKNOWN }) add(stringResource(R.string.range_levels_legend_unknown))
        }
        if (notes.isNotEmpty()) Text(notes.joinToString(" "), style = t.caption, color = c.textMuted)
    }
}

@Composable
private fun LevelRow(b: LevelBar, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val base = AppTheme.type.body.copy(fontWeight = FontWeight.SemiBold)
    val strong = base.copy(fontWeight = FontWeight.Bold)
    val km = b.km?.let { fmt.distance(it, 0).text() }
    val value = when {
        km == null -> stringResource(R.string.range_level_unknown)
        b.confidence == Confidence.ESTIMATED -> stringResource(R.string.range_level_approx, km)
        else -> km
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.range_level, b.level), Modifier.width(64.dp),
            style = if (b.current) strong else base, color = if (b.current) c.primaryStrong else c.textMuted, maxLines = 1,
        )
        ProgressBar(
            b.fraction, Modifier.weight(1f), height = 14.dp,
            color = if (b.current) c.primary else c.primarySoft, track = c.track,
        )
        Text(
            value, Modifier.width(64.dp), style = if (b.current) strong else base, textAlign = TextAlign.End, maxLines = 1,
            color = when {
                b.current -> c.primaryStrong
                b.confidence == Confidence.LEARNED -> c.text
                else -> c.textMuted
            },
        )
    }
}

// --- ring (R4) -------------------------------------------------------------------

@Composable
private fun RingCard(d: RangeDetail?, home: GeoPoint?, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val pos = d?.position
    val remaining = d?.estimate?.remainingKm
    AppCard(contentPadding = PaddingValues(0.dp)) {
        if (pos != null) {
            val markers = listOfNotNull(
                MapMarker(pos.point, if (pos.source == BikePosition.Source.LIVE) MarkerKind.CURRENT else MarkerKind.PARKED),
                home?.let { MapMarker(it, MarkerKind.HOME) },
            )
            val ring = remaining?.let { MapRing(pos.point, RangeCalc.ringRadiusKm(it) * 1000) }
            BikeMap(MapContent(ring = ring, markers = markers), Modifier.fillMaxWidth().aspectRatio(350f / 230f))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.range_ring_title), style = t.cardTitle)
            val text = when {
                pos == null -> stringResource(R.string.range_ring_no_position)
                remaining == null -> stringResource(if (d?.full != null) R.string.range_ring_no_battery else R.string.range_ring_no_range)
                else -> {
                    val main = stringResource(R.string.range_ring_text, fmt.distance(RangeCalc.ringRadiusKm(remaining), 0).text())
                    if (pos.source == BikePosition.Source.PARKED) main + " " + stringResource(R.string.range_ring_parked) else main
                }
            }
            Text(text, style = t.bodySmall, color = c.textMuted)
        }
    }
}

// --- home (R5) -------------------------------------------------------------------

@Composable
private fun HomeCard(state: RangeUiState, actions: RangeActions, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val status = state.detail?.home
    AppCard(gap = 8.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        SwitchRow(
            stringResource(R.string.range_home_title), state.homeEnabled, actions::setHomeEnabled,
            Modifier.bleed(16.dp).padding(top = 0.dp), subtitle = stringResource(R.string.range_home_subtitle),
            titleStyle = t.cardTitle, subtitleStyle = t.bodySmall,
        )
        if (state.homeEnabled && status != null && status != HomeStatus.Off) HomeStatusLine(status, fmt)
        Column {
            ValueRow(
                stringResource(R.string.range_home_place),
                stringResource(if (state.home != null) R.string.range_home_place_set else R.string.range_home_place_unset),
                actions::pickHome, Modifier.bleed(16.dp),
            )
            dev.donrobo.ebike.ui.components.Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.range_reserve), Modifier.weight(1f), style = t.bodyStrong)
                Stepper(
                    fmt.distance(state.reserveKm, 0).text(),
                    onDecrease = { actions.setReserveKm(HomeCalc.stepReserve(state.reserveKm, -1, fmt.system)) },
                    onIncrease = { actions.setReserveKm(HomeCalc.stepReserve(state.reserveKm, +1, fmt.system)) },
                    decreaseDescription = stringResource(R.string.range_reserve_less),
                    increaseDescription = stringResource(R.string.range_reserve_more),
                )
            }
        }
    }
}

@Composable
private fun HomeStatusLine(s: HomeStatus, fmt: UnitFormatter) {
    val c = AppTheme.colors
    fun d(km: Double) = fmt.distance(km, 1)
    when (s) {
        is HomeStatus.Known -> {
            val way = d(s.distanceKm).text()
            when (s.level) {
                HomeLevel.OK -> StatusBanner(stringResource(R.string.range_home_ok, way, fmt.distance(s.leftoverKm, 0).text()), icon = AppIcons.Check)
                HomeLevel.TIGHT -> StatusBanner(stringResource(R.string.range_home_tight, way, d(s.leftoverKm).text()), tone = Tone.WARNING, icon = AppIcons.Warning)
                HomeLevel.SHORT -> StatusBanner(stringResource(R.string.range_home_short, way), tone = Tone.DANGER, icon = AppIcons.Warning)
            }
            Text(
                stringResource(R.string.range_home_estimate_note, fmt.number(RangeCalc.DETOUR_FACTOR, 1)),
                Modifier.padding(top = 0.dp), style = AppTheme.type.caption, color = c.textMuted,
            )
        }
        is HomeStatus.NoRange -> StatusBanner(
            stringResource(if (s.learned) R.string.range_home_no_battery else R.string.range_home_no_range, d(s.distanceKm).text()), tone = Tone.NEUTRAL,
        )
        HomeStatus.NoHome -> StatusBanner(stringResource(R.string.range_home_no_home), tone = Tone.NEUTRAL)
        is HomeStatus.NoPosition -> StatusBanner(
            stringResource(
                when (s.limit) {
                    LocationLimit.NEEDS_BACKGROUND -> R.string.range_home_needs_background
                    LocationLimit.NO_PERMISSION -> R.string.range_home_no_permission
                    LocationLimit.SYSTEM_LOCATION_OFF -> R.string.range_home_location_off
                    else -> R.string.range_home_no_position
                },
            ),
            tone = Tone.WARNING, icon = AppIcons.Warning,
        )
        HomeStatus.Off -> {}
    }
}

// --- R6 --------------------------------------------------------------------------

@Composable
private fun HealthCard(d: RangeDetail?, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val points = healthPoints(d?.months.orEmpty())
    val locale = currentLocale()
    AppCard(gap = 10.dp) {
        CardTitle(stringResource(R.string.range_health_title), description = stringResource(R.string.range_health_subtitle))
        val months = d?.months.orEmpty()
        if (points.size >= 2 && months.size == RangeModel.MONTHS) {
            val first = points.first(); val last = points.last()
            val desc = stringResource(
                R.string.range_health_chart, monthName(first.month, locale, full = true), monthName(last.month, locale, full = true),
                fmt.distance(first.km, 0).text(), fmt.distance(last.km, 0).text(),
            )
            val kmLabel = unitLabel(fmt.distanceUnit)
            HealthChart(points, months.map { monthName(it.month, locale) }, { "${fmt.distance(it, 0).value} $kmLabel" }, Modifier.semantics { contentDescription = desc })
        } else {
            points.singleOrNull()?.let {
                Text(stringResource(R.string.range_health_one_month, monthName(it.month, locale, full = true), fmt.distance(it.km, 0).text()), style = t.bodyStrong)
            }
            Text(stringResource(R.string.range_health_not_enough, fmt.distance(RangeModel.MONTH_KM, 0).text()), style = t.caption, color = c.textMuted)
        }
        Text(stringResource(R.string.range_health_caveat), style = t.caption, color = c.textMuted)
    }
}

private fun monthName(m: YearMonth, locale: Locale, full: Boolean = false) =
    m.month.getDisplayName(if (full) DateTextStyle.FULL_STANDALONE else DateTextStyle.SHORT_STANDALONE, locale).removeSuffix(".")

/** The artboard's line chart (viewBox 318x120): line between months with data, first and last value labelled. */
@Composable
private fun HealthChart(points: List<HealthPoint>, labels: List<String>, kmText: (Double) -> String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val measurer = rememberTextMeasurer()
    val valueStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text)
    val axisStyle = TextStyle(fontSize = 11.sp, color = c.textMuted)
    val texts = points.map { kmText(it.km) }
    Canvas(modifier.fillMaxWidth().aspectRatio(318f / 120f)) {
        val sx = size.width / 318f
        val sy = size.height / 120f
        fun x(i: Int) = (14f + i * 58f) * sx
        // plot area y 30..80 in the viewBox; 92 = baseline
        fun y(km: Double) = (30f + healthY(km, points) * 50f) * sy
        drawLine(c.border, Offset(0f, 92f * sy), Offset(size.width, 92f * sy), strokeWidth = 1.dp.toPx())
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.index), y(p.km)) else path.lineTo(x(p.index), y(p.km)) }
        drawPath(path, c.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val first = points.first(); val last = points.last()
        drawCircle(c.primary, 4.dp.toPx(), Offset(x(first.index), y(first.km)))
        drawCircle(c.surface, 5.dp.toPx(), Offset(x(last.index), y(last.km)))
        drawCircle(c.primary, 5.dp.toPx(), Offset(x(last.index), y(last.km)), style = Stroke(2.5.dp.toPx()))
        // value labels: baseline 12 above the point, first left-aligned, last right-aligned
        val f = measurer.measure(texts.first(), valueStyle)
        drawText(f, topLeft = Offset(x(first.index), y(first.km) - 12f * sy - f.firstBaseline))
        val l = measurer.measure(texts.last(), valueStyle)
        drawText(l, topLeft = Offset(x(last.index) - l.size.width, y(last.km) - 12f * sy - l.firstBaseline))
        labels.forEachIndexed { i, s ->
            val m = measurer.measure(s, axisStyle)
            val left = when (i) { 0 -> x(i); labels.lastIndex -> x(i) - m.size.width; else -> x(i) - m.size.width / 2f }
            drawText(m, topLeft = Offset(left, 110f * sy - m.firstBaseline))
        }
    }
}

// --- R7 --------------------------------------------------------------------------

@Composable
private fun ModelCard(state: RangeUiState, actions: RangeActions, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    AppCard(gap = 12.dp) {
        Text(stringResource(R.string.range_model_title), style = t.cardTitle)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.range_model_basis), style = t.label, color = c.textMuted)
            val options = RangeModel.WINDOWS
            SegmentedControl(
                options.map { stringResource(R.string.range_model_rides, it) },
                options.indexOf(state.window).coerceAtLeast(0),
                { actions.setWindow(options[it]) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.range_model_margin), Modifier.weight(1f), style = t.label, color = c.textMuted)
                Text(stringResource(R.string.range_model_margin_value, state.marginPct), style = t.label)
            }
            var draft by remember { mutableStateOf<Float?>(null) }
            MarginSlider(
                value = draft ?: state.marginPct.toFloat(),
                onValueChange = { draft = it },
                onValueChangeFinished = { draft?.let { actions.setMargin(it.roundToInt()) }; draft = null },
            )
        }
        Text(
            stringResource(R.string.range_model_note, fmt.distance(RangeModel.MIN_RIDE_KM, if (fmt.system == UnitSystem.IMPERIAL) 1 else 0).text(),fmt.number(RangeCalc.DETOUR_FACTOR, 1)),
            style = t.caption, color = c.textMuted,
        )
    }
}

/** Safety margin 0-30 % in steps of 5: the stock Material 3 [Slider] with tick marks. */
@Composable
private fun MarginSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    val description = stringResource(R.string.range_model_margin)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..RangeSettings.MARGIN_MAX.toFloat(),
        steps = RangeSettings.MARGIN_MAX / RangeSettings.MARGIN_STEP - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}
