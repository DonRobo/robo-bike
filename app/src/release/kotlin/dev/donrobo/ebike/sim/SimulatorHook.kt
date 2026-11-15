package dev.donrobo.ebike.sim

import android.content.Context
import androidx.compose.runtime.Composable
import dev.donrobo.ebike.ble.LinkTransport

/** Release builds have no simulator: `BikeService` always talks to the real display over Bluetooth, Diagnose has no simulator card. */
object SimulatorHook {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun transport(ctx: Context): LinkTransport? = null

    @Suppress("FunctionNaming")
    @Composable
    fun DiagnosticsCard() {}
}
