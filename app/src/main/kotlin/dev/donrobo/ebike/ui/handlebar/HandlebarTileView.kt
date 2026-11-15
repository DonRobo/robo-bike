package dev.donrobo.ebike.ui.handlebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import dev.donrobo.ebike.R
import dev.donrobo.ebike.counters.labelRes
import dev.donrobo.ebike.handlebar.HandlebarTile
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.LineIcon
import dev.donrobo.ebike.ui.components.NumberWithUnit
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlin.math.min

/** Icons only the handlebar feature uses (24-unit grid, stroke drawn by AppIcon). */
internal object HandlebarIcons {
    val Lock = LineIcon("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val ArrowUp = LineIcon("M12 19V5M6 11l6-6 6 6")
    val ArrowDown = LineIcon("M12 5v14M6 13l6 6 6-6")
    val Minus = LineIcon("M5 12h14")
}

@Composable
internal fun tileLabel(tile: HandlebarTile): String = stringResource(
    when (tile) {
        HandlebarTile.BATTERY -> R.string.handlebar_tile_battery
        HandlebarTile.RANGE -> R.string.handlebar_tile_range
        HandlebarTile.ASSIST -> R.string.handlebar_tile_assist
        HandlebarTile.RIDE, HandlebarTile.TRIP, HandlebarTile.SINCE_CHARGE, HandlebarTile.SINCE_HOME, HandlebarTile.ODO -> tile.counter!!.labelRes()
        HandlebarTile.SPEED -> R.string.handlebar_tile_speed
        HandlebarTile.CLOCK -> R.string.handlebar_tile_clock
        HandlebarTile.LIGHT -> R.string.handlebar_tile_light
        HandlebarTile.HOME -> R.string.handlebar_tile_home
    },
)

@Composable
private fun unitText(u: TileUnit): String? = when (u) {
    is TileUnit.Of -> unitLabel(u.label)
    TileUnit.Percent -> stringResource(R.string.unit_percent)
    is TileUnit.OfGears -> stringResource(R.string.handlebar_assist_of, u.gears)
    TileUnit.None -> null
}

/** Factor (≤ 1) that makes [value] + gap + [unit] fit into [maxWidthPx] at the given styles. */
internal fun fitScale(m: TextMeasurer, value: String, numberStyle: TextStyle, unit: String?, unitStyle: TextStyle, gapPx: Float, maxWidthPx: Float): Float {
    val w = m.measure(value, numberStyle, maxLines = 1, softWrap = false).size.width +
        (unit?.let { gapPx + m.measure(it, unitStyle, maxLines = 1, softWrap = false).size.width } ?: 0f)
    return if (w <= 0f) 1f else min(1f, maxWidthPx / w)
}

internal fun TextStyle.scaled(f: Float): TextStyle = if (f >= 1f) this else copy(fontSize = fontSize * f)

/**
 * One tile in the artboard's handlebar style (grey fill, radius 22, label 16/600, number 48, unit 20).
 * Long values ("1.284 km", "14:05") shrink to fit instead of being cut. Values from the last known state are
 * muted so a stale number never reads like a live one. [small] = the editor's preview size. [fill] = the tile
 * takes the height it is given and its number grows with it (up to [MaxTileGrow]x), for a grid with free height.
 */
@Composable
internal fun HandlebarTileView(label: String, reading: TileReading, modifier: Modifier = Modifier, small: Boolean = false, fill: Boolean = false) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val (value, unit, muted) = when (reading) {
        is TileReading.Value -> Triple(reading.text, unitText(reading.unit), reading.stale)
        is TileReading.Light -> Triple(stringResource(if (reading.on) R.string.handlebar_value_on else R.string.handlebar_value_off), null, false)
        is TileReading.Empty -> Triple(stringResource(R.string.handlebar_no_value), unitText(reading.unit), true)
    }
    val numberStyle = if (small) t.numberTile else t.numberTileLarge
    val unitStyle = if (small) t.unit else t.unitTileLarge
    val gap: Dp = 4.dp
    AppCard(
        if (fill) modifier.fillMaxHeight() else modifier,
        shape = if (small) MaterialTheme.shapes.medium else RoundedCornerShape(22.dp),
        contentPadding = if (small) PaddingValues(horizontal = 14.dp, vertical = 10.dp) else PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        color = c.background,
    ) {
        Column(if (fill) Modifier.fillMaxHeight() else Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = if (small) t.captionStrong else t.itemTitle, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val m = rememberTextMeasurer()
            val density = LocalDensity.current
            // Fixed line height of the unscaled number, so shrunk values keep the tile height and row alignment.
            val lineH = with(density) {
                val lh = numberStyle.lineHeight
                (if (lh.isEm) numberStyle.fontSize.toPx() * lh.value else m.measure("0", numberStyle).size.height.toFloat()).toDp()
            }
            BoxWithConstraints(
                if (fill) Modifier.weight(1f).fillMaxWidth() else Modifier.height(lineH),
                // Filling: the number sits in the middle of the free height instead of leaving a hole under the label.
                contentAlignment = if (fill) Alignment.CenterStart else Alignment.BottomStart,
            ) {
                // Same height for every tile of the grid, so all numbers grow alike; width only ever shrinks one.
                val grow = if (fill) tileGrow(maxHeight / lineH) else 1f
                val number = numberStyle.grown(grow)
                val unitGrown = unitStyle.grown(1f + (grow - 1f) / 2f)
                // Grown text does not scale exactly linearly (pixel rounding of glyph advances): keep 3 % slack so the
                // unit is never ellipsized.
                val room = constraints.maxWidth.toFloat() * (if (grow > 1f) 0.97f else 1f)
                val f = fitScale(m, value, number, unit, unitGrown, with(density) { gap.toPx() }, room)
                NumberWithUnit(
                    value, unit, number.scaled(f), unitGrown.scaled(f), gap = gap,
                    color = if (muted) c.textMuted else c.text,
                )
            }
        }
    }
}

/** Largest growth of a [HandlebarTileView] number in a grid with free height (48 sp -> 96 sp). */
internal const val MaxTileGrow = 2f

/** Growth of a filling tile's number for [heightRatio] = available height / unscaled line height: 1 .. [MaxTileGrow]. */
internal fun tileGrow(heightRatio: Float): Float = heightRatio.coerceIn(1f, MaxTileGrow)

private fun TextStyle.grown(f: Float): TextStyle = if (f <= 1f) this else copy(fontSize = fontSize * f)

/**
 * Tiles in rows of [columns]; a short last row keeps the tile width. [fill] = the rows share the height the grid
 * is given (give it a bounded height, e.g. a weight) and the tiles grow with it.
 */
@Composable
internal fun TileGrid(
    tiles: List<HandlebarTile>,
    state: HandlebarUiState,
    fmt: dev.donrobo.ebike.units.UnitFormatter,
    columns: Int,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    gap: Dp = 12.dp,
    fill: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        tiles.chunked(columns).forEach { row ->
            Row(if (fill) Modifier.weight(1f) else Modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { HandlebarTileView(tileLabel(it), tileReading(it, state, fmt), Modifier.weight(1f), small, fill) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
