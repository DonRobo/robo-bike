package dev.donrobo.ebike.ui.handlebar

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.autoConnectArmed
import dev.donrobo.ebike.R
import dev.donrobo.ebike.handlebar.HandlebarSettings
import dev.donrobo.ebike.handlebar.HandlebarTile
import dev.donrobo.ebike.handlebar.HandlebarTiles
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppListItem
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.ListGroup
import dev.donrobo.ebike.ui.components.RoundIconButton
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.SwitchCard
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.connection.rememberConnectionStatus
import dev.donrobo.ebike.ui.connection.rememberSnapshot
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import java.time.LocalTime
import dev.donrobo.ebike.ui.components.rememberActiveBike

/** Everything the tile editor shows. [preview] carries the values the tiles would show right now. */
data class HandlebarTilesUiState(
    val bikeName: String?,
    val tiles: List<HandlebarTile>,
    val preview: HandlebarUiState,
    val autoStart: Boolean,
)

interface HandlebarTilesActions {
    fun back()
    fun setTiles(tiles: List<HandlebarTile>)
    fun setAutoStart(on: Boolean)
}

/** Glove-sized round buttons in the editor rows (the app's minimum is 44). */
private val RowButton = 52.dp

/**
 * Tile editor (M2) plus the auto-start switch (M5): the destination of "Kacheln anpassen" and of the
 * settings row "Lenkermodus". The layout is stored per bike, auto-start once for the phone.
 */
@Composable
fun HandlebarTilesScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bikes = ctx.bikes
    val bike by rememberActiveBike()
    val bikeId = bike?.id
    val tiles by remember(bikeId) { bikeId?.let { settings.flow(HandlebarSettings.tiles, it) } ?: flowOf(HandlebarTiles.DEFAULT) }
        .collectAsStateWithLifecycle(bikeId?.let { settings.get(HandlebarSettings.tiles, it) } ?: HandlebarTiles.DEFAULT)
    val autoStart by remember { settings.flow(HandlebarSettings.autoStart) }.collectAsStateWithLifecycle(settings[HandlebarSettings.autoStart])
    val snapshot = rememberSnapshot(bikeId)
    val range by RangeState.estimate.collectAsStateWithLifecycle()
    val home by RangeState.home.collectAsStateWithLifecycle()
    val counters by dev.donrobo.ebike.counters.CounterState.inputs.collectAsStateWithLifecycle()

    val status = rememberConnectionStatus(bike, bikeId, remember(bike) { autoConnectArmed(ctx, bike) })
    val state = HandlebarTilesUiState(
        bikeName = bike?.name,
        tiles = tiles,
        preview = handlebarUiState(status, snapshot, null, null, range, home, tiles, LocalTime.now(), counters = counters),
        autoStart = autoStart,
    )
    val actions = remember(nav, bikeId) {
        object : HandlebarTilesActions {
            override fun back() = nav.back()
            override fun setTiles(tiles: List<HandlebarTile>) { bikeId?.let { settings.set(HandlebarSettings.tiles, it, tiles) } }
            override fun setAutoStart(on: Boolean) { settings[HandlebarSettings.autoStart] = on }
        }
    }
    HandlebarTilesContent(state, actions)
}

@Composable
fun HandlebarTilesContent(state: HandlebarTilesUiState, actions: HandlebarTilesActions, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    val tiles = state.tiles
    Screen(header = { BackHeader(stringResource(R.string.handlebar_tiles_title), actions::back) }, modifier) {
        AppCard(gap = 12.dp) {
            CardTitle(
                stringResource(R.string.handlebar_tiles_preview),
                description = state.bikeName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.handlebar_tiles_for_bike, it) },
            )
            if (tiles.isEmpty()) Text(stringResource(R.string.handlebar_tiles_preview_empty), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
            else TileGrid(tiles, state.preview, fmt, columns = 2, small = true, gap = 8.dp)
        }

        SectionLabel(stringResource(R.string.handlebar_tiles_shown, tiles.size, HandlebarTiles.MAX))
        if (tiles.isEmpty()) {
            AppCard { Text(stringResource(R.string.handlebar_tiles_none), style = AppTheme.type.body, color = AppTheme.colors.textMuted) }
        } else {
            ListGroup {
                tiles.forEachIndexed { i, tile ->
                    item {
                        val label = tileLabel(tile)
                        EditorRow(label, hint(tile, state.preview)) {
                            RoundIconButton(
                                HandlebarIcons.ArrowUp, stringResource(R.string.handlebar_tiles_move_up, label),
                                { actions.setTiles(HandlebarTiles.move(tiles, tile, -1)) }, size = RowButton, enabled = i > 0,
                            )
                            RoundIconButton(
                                HandlebarIcons.ArrowDown, stringResource(R.string.handlebar_tiles_move_down, label),
                                { actions.setTiles(HandlebarTiles.move(tiles, tile, 1)) }, size = RowButton, enabled = i < tiles.lastIndex,
                            )
                            RoundIconButton(
                                HandlebarIcons.Minus, stringResource(R.string.handlebar_tiles_remove, label),
                                { actions.setTiles(HandlebarTiles.remove(tiles, tile)) }, size = RowButton,
                            )
                        }
                    }
                }
            }
        }

        val available = HandlebarTiles.available(tiles)
        if (available.isNotEmpty()) {
            SectionLabel(stringResource(R.string.handlebar_tiles_more))
            if (tiles.size >= HandlebarTiles.MAX) StatusBanner(stringResource(R.string.handlebar_tiles_full, HandlebarTiles.MAX), tone = Tone.NEUTRAL)
            ListGroup {
                available.forEach { tile ->
                    item {
                        val label = tileLabel(tile)
                        val enabled = HandlebarTiles.canAdd(tiles, tile)
                        val add = stringResource(R.string.handlebar_tiles_add, label)
                        EditorRow(
                            label, hint(tile, state.preview),
                            Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = add) { actions.setTiles(HandlebarTiles.add(tiles, tile)) }
                                .alpha(if (enabled) 1f else 0.45f),
                        ) {
                            // Visual only: the whole row is the target.
                            AppIcon(AppIcons.Plus, null, Modifier.padding(horizontal = 12.dp), size = 24.dp, tint = AppTheme.colors.primary)
                        }
                    }
                }
            }
        }

        if (tiles != HandlebarTiles.DEFAULT) {
            AppButton(stringResource(R.string.handlebar_tiles_reset), { actions.setTiles(HandlebarTiles.DEFAULT) }, kind = ButtonKind.TEXT)
        }

        SectionLabel(stringResource(R.string.handlebar_section_auto))
        SwitchCard(
            stringResource(R.string.handlebar_auto_title), state.autoStart, actions::setAutoStart,
            subtitle = stringResource(R.string.handlebar_auto_sub),
        )
    }
}

/** Why a tile may show a dash: said in the editor, so the rider is not surprised on the road. */
@Composable
private fun hint(tile: HandlebarTile, preview: HandlebarUiState): String? = when (tile) {
    HandlebarTile.RANGE -> if (preview.range == null) stringResource(R.string.handlebar_tiles_range_learning) else null
    HandlebarTile.HOME -> if (preview.home == null) stringResource(R.string.handlebar_tiles_home_unset) else null
    HandlebarTile.SPEED, HandlebarTile.ASSIST, HandlebarTile.LIGHT -> stringResource(R.string.handlebar_tiles_live_only)
    HandlebarTile.RIDE -> if (preview.counters.openRide == null) stringResource(R.string.counters_hint_last_ride) else null
    HandlebarTile.SINCE_CHARGE -> if (preview.counters.fullChargeOdoKm == null) stringResource(R.string.counters_hint_charge) else null
    HandlebarTile.SINCE_HOME -> if (preview.counters.homeDepartureOdoKm == null) stringResource(R.string.counters_hint_home) else null
    else -> null
}

/** Material list item of the tile editor: tile name, why it may show a dash, and the row's buttons. */
@Composable
private fun EditorRow(title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit) {
    AppListItem(
        title, modifier, subtitle, titleStyle = AppTheme.type.itemTitleStrong,
        trailing = { Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = trailing) },
    )
}
