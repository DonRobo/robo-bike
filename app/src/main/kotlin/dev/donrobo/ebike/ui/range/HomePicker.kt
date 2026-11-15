package dev.donrobo.ebike.ui.range

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.donrobo.ebike.R
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.map.MapMarker
import dev.donrobo.ebike.ui.components.map.MapMode
import dev.donrobo.ebike.ui.components.map.MarkerKind
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Full-screen home picker (R5): tap the map, or take the current position. [near] centres the map when
 * no home is set yet. [onSave] gets null for "remove".
 */
@Composable
fun HomePicker(initial: GeoPoint?, near: GeoPoint?, onSave: (GeoPoint?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(initial) }
    var locating by remember { mutableStateOf(false) }
    var noFix by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            BackHeader(stringResource(R.string.range_pick_title), onDismiss)
            val centre = picked ?: near ?: Locations.best(ctx)?.point
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BikeMap(
                    MapContent(markers = listOfNotNull(picked?.let { MapMarker(it, MarkerKind.HOME) } ?: centre?.let { MapMarker(it, MarkerKind.CURRENT) })),
                    Modifier.fillMaxSize(), mode = MapMode.INTERACTIVE,
                    onPick = { picked = it; noFix = false },
                )
            }
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            locating -> R.string.range_pick_locating
                            noFix -> R.string.range_pick_no_fix
                            else -> R.string.range_pick_hint
                        },
                    ),
                    style = AppTheme.type.bodySmall, color = c.textMuted,
                )
                AppButton(
                    stringResource(R.string.range_pick_use_position),
                    onClick = {
                        locating = true; noFix = false
                        scope.launch {
                            val fix = Locations.requestFix(ctx) ?: Locations.best(ctx)?.takeIf { it.ageMs(System.currentTimeMillis()) < 5 * 60_000 }
                            locating = false
                            if (fix != null) picked = fix.point else noFix = true
                        }
                    },
                    kind = ButtonKind.OUTLINED, modifier = Modifier.fillMaxWidth(), enabled = !locating,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (initial != null) AppButton(stringResource(R.string.range_pick_clear), onClick = { onSave(null) }, kind = ButtonKind.DANGER_TEXT, modifier = Modifier.weight(1f))
                    else AppButton(stringResource(R.string.range_pick_cancel), onClick = onDismiss, kind = ButtonKind.TEXT, modifier = Modifier.weight(1f))
                    AppButton(stringResource(R.string.range_pick_save), onClick = { onSave(picked) }, modifier = Modifier.weight(1f), enabled = picked != null)
                }
            }
        }
    }
}
