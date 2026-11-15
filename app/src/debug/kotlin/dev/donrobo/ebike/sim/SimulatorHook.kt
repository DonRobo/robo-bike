package dev.donrobo.ebike.sim

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ble.LinkTransport
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Debug builds: the seam where `BikeService` creates its link. When the active bike's display is the simulated
 * one ([Simulator.MAC], given to it by `adb ... --es cmd pair` or the Diagnose card), the service talks to the
 * [Simulator] instead of Bluetooth; everything above the transport ([dev.donrobo.ebike.ble.Ekd01Link] and up) is the
 * production code. The release source set has stubs: no transport, no Diagnose card.
 */
object SimulatorHook {
    fun transport(ctx: Context): LinkTransport? {
        if (!SimActions.paired(ctx)) return null
        Simulator.init(ctx)
        return Simulator.transport()
    }

    /** Scenarios offered on the card: name for [Simulator.scenario] and speed-up. */
    private val SCENARIOS = listOf("still" to 1.0, "ride" to 1.0, "ride" to 10.0)

    /**
     * Diagnose card "Simuliertes Display": pair the active bike with the simulated display, switch it on (= adb
     * `start`: display on and Connect) and off (= adb `off`), pick the scenario. Strings live in the debug source set.
     */
    @Composable
    fun DiagnosticsCard() {
        val ctx = LocalContext.current
        LaunchedEffect(Unit) { Simulator.init(ctx) }
        val powered by Simulator.powered.collectAsStateWithLifecycle()
        val bike by remember { ctx.bikes.activeFlow() }.collectAsStateWithLifecycle(ctx.bikes.active())
        val paired = bike?.displayMac.equals(Simulator.MAC, ignoreCase = true)
        var scenario by rememberSaveable { mutableIntStateOf(0) }
        AppCard(gap = 12.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            CardTitle(stringResource(R.string.sim_title), description = stringResource(R.string.sim_sub))
            if (!paired) {
                Text(stringResource(R.string.sim_not_paired), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
                AppButton(stringResource(R.string.sim_pair), { SimActions.pair(ctx, null) }, kind = ButtonKind.OUTLINED)
            } else {
                SwitchRow(
                    stringResource(R.string.sim_power), powered,
                    { on -> if (on) SimActions.start(ctx) else SimActions.off(ctx) },
                    Modifier.bleed(16.dp), subtitle = stringResource(R.string.sim_power_sub),
                )
                SegmentedControl(
                    listOf(stringResource(R.string.sim_scenario_still), stringResource(R.string.sim_scenario_ride), stringResource(R.string.sim_scenario_ride_fast)),
                    scenario,
                    { i ->
                        scenario = i
                        val (name, speedup) = SCENARIOS[i]
                        Simulator.scenario(name)?.let { Simulator.play(it, speedup) }
                    },
                )
            }
        }
    }
}
