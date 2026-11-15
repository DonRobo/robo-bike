package dev.donrobo.ebike.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.light.ManualLight
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick-settings tile "Fahrradlicht" (K2), showing [LightControl]. Non-active (standard) mode: bound while the panel is open, and only
 * then does it follow the light. Unavailable while no bike is connected or the bike is locked. A tap goes
 * through [ManualLight], the same path as every other hand switch; the tile then follows the next report.
 * Switching the lamp is harmless, so it also works on the lock screen (no unlockAndRun).
 */
class LightTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var switching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            combine(Repo.live, Repo.sessionBikeId, settings.anyChange()) { l, _, _ -> LightControl.of(l?.report, ManualLight.lampSwitch(this@LightTileService)) }.distinctUntilChanged().collect(::render)
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val shown = LightControl.of(Repo.live.value?.report, ManualLight.lampSwitch(this))
        if (!shown.switchable) return
        if (switching?.isActive == true) return
        switching = CoroutineScope(Dispatchers.Default).launch {
            withTimeoutOrNull(8_000) { ManualLight.set(this@LightTileService, shown == LightControl.OFF, "tile") }
        }
    }

    private fun render(t: LightControl) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.widget_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_light)
        tile.state = when (t) {
            LightControl.ON -> Tile.STATE_ACTIVE
            LightControl.OFF -> Tile.STATE_INACTIVE
            LightControl.NOT_CONNECTED, LightControl.LOCKED, LightControl.NO_LAMP -> Tile.STATE_UNAVAILABLE
        }
        tile.subtitle = getString(
            when (t) {
                LightControl.ON -> R.string.widget_tile_on
                LightControl.OFF -> R.string.widget_tile_off
                LightControl.NOT_CONNECTED -> R.string.status_off
                LightControl.LOCKED -> R.string.widget_tile_locked
                LightControl.NO_LAMP -> R.string.widget_tile_no_lamp
            },
        )
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }
}
