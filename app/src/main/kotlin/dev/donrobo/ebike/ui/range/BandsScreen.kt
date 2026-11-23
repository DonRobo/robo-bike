package dev.donrobo.ebike.ui.range

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.HeroCard
import dev.donrobo.ebike.ui.components.InfoButton
import dev.donrobo.ebike.ui.components.InfoParagraph
import dev.donrobo.ebike.ui.components.InsetPanel
import dev.donrobo.ebike.ui.components.NumberWithUnit
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter

/** Akku-Bänder (artboard "Akku-Bänder", R8), wired to [RangeState]. */
@Composable
fun BandsScreen(nav: AppNavigator) {
    val detail by RangeState.detail.collectAsStateWithLifecycle()
    BandsContent(bandsUiState(detail), nav::back)
}

/** Stateless Akku-Bänder screen; [initialPick] = band (its low percent) selected at first (screenshot tests). */
@Composable
fun BandsContent(state: BandsUiState?, onBack: () -> Unit, initialPick: Int? = null) {
    val fmt = rememberUnitFormatter()
    var picked by rememberSaveable { mutableStateOf(initialPick) }
    val header = @Composable {
        BackHeader(stringResource(R.string.range_bands_title), onBack) {
            InfoButton(stringResource(R.string.range_bands_info), stringResource(R.string.range_bands_title)) { BandsInfo() }
        }
    }
    Screen(header = header) {
        if (state == null) {
            AppCard { Text(stringResource(R.string.range_bands_empty), style = AppTheme.type.body, color = AppTheme.colors.textMuted) }
            return@Screen
        }
        TotalsCard(state, fmt)
        BandsCard(state, picked, fmt) { picked = it }
        DetailCard(state.selected(picked), fmt)
    }
}

/** The Akku-Bänder explanation sheet's content (S4). */
@Composable
internal fun BandsInfo() {
    InfoParagraph(stringResource(R.string.range_bands_info_why))
    InfoParagraph(stringResource(R.string.range_bands_info_estimated))
    InfoParagraph(stringResource(R.string.range_bands_info_sum))
}

@Composable
private fun TotalsCard(state: BandsUiState, fmt: UnitFormatter) {
    HeroCard(gap = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Total(stringResource(R.string.range_full), state.fullKm, fmt, Modifier.weight(1f))
            if (state.restKm != null && state.batteryPct != null) {
                Total(stringResource(R.string.range_now, state.batteryPct), state.restKm, fmt, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Total(label: String, km: Double, fmt: UnitFormatter, modifier: Modifier) {
    val m = fmt.distance(km, 0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = AppTheme.type.label, color = AppTheme.colors.textMuted)
        NumberWithUnit(m.value, unitLabel(m.unit), AppTheme.type.numberMedium, AppTheme.type.unit)
    }
}

@Composable
private fun BandsCard(state: BandsUiState, picked: Int?, fmt: UnitFormatter, onPick: (Int) -> Unit) {
    val selected = state.selected(picked)
    AppCard(gap = 10.dp) {
        Legend()
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.rows.forEach { row ->
                BandRowView(row, state.barFraction(row), row == selected, fmt) { onPick(row.lowPct) }
            }
        }
        Text(stringResource(R.string.range_bands_tap), style = AppTheme.type.caption, color = AppTheme.colors.textMuted)
    }
}

@Composable
private fun Legend() {
    val c = AppTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(stringResource(R.string.range_bands_measured)) { drawBar(c.primary, null, size.width, size.height) }
        LegendItem(stringResource(R.string.range_bands_estimated)) { drawBar(c.primaryTint, c.primary, size.width, size.height) }
        LegendItem(stringResource(R.string.range_bands_used)) { drawBar(c.primarySoft, null, size.width, size.height) }
    }
}

@Composable
private fun LegendItem(text: String, swatch: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(20.dp, 12.dp), onDraw = swatch)
        Text(text, style = AppTheme.type.captionStrong, color = AppTheme.colors.textMuted)
    }
}

/** A rounded bar of [w] x [h]; [dotted] = a dotted outline in that colour (estimated band). */
private fun DrawScope.drawBar(fill: Color, dotted: Color?, w: Float, h: Float, x: Float = 0f) {
    val r = CornerRadius(4.dp.toPx())
    drawRoundRect(fill, Offset(x, 0f), Size(w, h), r)
    if (dotted != null) {
        val sw = 2.dp.toPx()
        drawRoundRect(
            dotted, Offset(x + sw / 2, sw / 2), Size(w - sw, h - sw), r,
            style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw, sw * 1.5f))),
        )
    }
}

@Composable
private fun BandRowView(row: BandRow, fraction: Float, selected: Boolean, fmt: UnitFormatter, onClick: () -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val km = fmt.distance(row.km, 1).text()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) c.track else Color.Transparent,
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.width(72.dp)) {
                Text(
                    stringResource(R.string.range_bands_band, row.lowPct, row.highPct),
                    style = t.label.copy(fontWeight = if (row.current) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (row.restFraction > 0) c.text else c.textMuted, maxLines = 1,
                )
                if (row.current) NowChip()
            }
            Box(Modifier.weight(1f).height(24.dp)) {
                Canvas(Modifier.fillMaxWidth(fraction).height(24.dp)) {
                    val restW = size.width * row.restFraction.toFloat()
                    val (rest, used) = if (row.measured) c.primary to c.primarySoft else c.primaryTint to c.track
                    drawBar(used, null, size.width, size.height)
                    if (restW > 0) drawBar(rest, null, restW, size.height)
                    if (!row.measured) drawBar(Color.Transparent, if (row.restFraction > 0) c.primary else c.neutral, size.width, size.height)
                }
            }
            Text(
                if (row.measured) km else stringResource(R.string.range_level_approx, km), Modifier.width(72.dp),
                style = t.bodyStrong, textAlign = TextAlign.End, maxLines = 1,
                color = if (row.measured) c.text else c.textMuted,
            )
        }
    }
}

@Composable
private fun NowChip() {
    Surface(shape = RoundedCornerShape(6.dp), color = AppTheme.colors.primaryStrong) {
        Text(
            stringResource(R.string.range_bands_now), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = AppTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = AppTheme.colors.onPrimary,
        )
    }
}

@Composable
private fun DetailCard(row: BandRow, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val band = stringResource(R.string.range_bands_band, row.lowPct, row.highPct)
    AppCard(gap = 10.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.range_bands_detail_title, band), Modifier.weight(1f), style = t.cardTitle)
            Text(stringResource(R.string.range_bands_per_km, fmt.number(row.pctPerKm, 2)), style = t.bodySmall, color = c.textMuted)
        }
        val m = fmt.distance(row.km, 1)
        NumberWithUnit(if (row.measured) m.value else stringResource(R.string.range_level_approx, m.value), unitLabel(m.unit), t.numberMedium, t.unit)
        if (row.measured) {
            Text(
                pluralStringResource(R.plurals.range_bands_measured_on, row.rides, fmt.distance(row.basisKm, 0).text(), row.rides),
                style = t.bodySmall, color = c.textMuted,
            )
        } else {
            InsetPanel { Text(stringResource(R.string.range_bands_estimated_body), style = t.bodySmall) }
        }
    }
}

