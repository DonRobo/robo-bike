package dev.donrobo.ebike.sim

import android.content.Context
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.BikeService
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings

/**
 * Debug builds only: what the bike and the rider do to the simulated display (pair, switch on and Connect, switch
 * off), shared by the adb remote control ([SimControlReceiver]) and the Diagnose screen's simulator card.
 */
object SimActions {
    /** The active bike's display is the simulated one. */
    fun paired(ctx: Context): Boolean = ctx.bikes.active()?.displayMac.equals(Simulator.MAC, ignoreCase = true)

    /** Display on and Connect pressed (adb `start`). */
    fun start(ctx: Context) { Simulator.init(ctx); Simulator.powerOn(); connect(ctx) }

    /** Display off; a Disconnect pause ends like when the real bike is switched off (adb `off`). */
    fun off(ctx: Context) { Simulator.powerOff(); disappeared(ctx) }

    /** Debug stand-in for setup step 1 without the Companion Device chooser: the same adopt() the chooser result goes through. */
    fun pair(ctx: Context, name: String?) {
        val all = ctx.bikes.all()
        when (val r = BikeCompanion.adopt(ctx, Pairing.setupTarget(all), Simulator.MAC, name, ctx.getString(R.string.bike_default_name))) {
            is Pairing.Assign.Store -> Repo.log("sim: simulated display ${Simulator.MAC} given to bike ${r.bikeId} (${ctx.bikes.get(r.bikeId!!)?.name}), now active")
            is Pairing.Assign.AlreadyOwned -> Repo.log("sim: simulated display already belongs to bike ${r.bikeId}, selected it")
        }
    }

    /** Like the Connect button: lifts the Disconnect pause and starts the service. */
    fun connect(ctx: Context) {
        val bike = ctx.bikes.active() ?: run { Repo.log("sim: no bike; run 'pair' first"); return }
        ctx.settings.set(ConnectionSettings.pausedUntilOff, bike.id, false)
        startService(ctx, "Connect")
    }

    /** Like PresenceService.appeared for the simulated display (the emulator has no companion association). */
    fun appeared(ctx: Context) {
        val bikes = ctx.bikes
        val all = bikes.all()
        val connected = Repo.sessionBikeId.value.takeIf { Repo.conn.value == ConnState.CONNECTED }
        val paused = ConnectionSettings.pausedBikes(ctx.settings, all)
        when (val d = Pairing.onAppeared(all, Simulator.MAC, ctx.settings[ConnectionSettings.autoConnect], connected, paused)) {
            is Pairing.Appeared.Ignore -> Repo.log("sim: display appeared, not connecting: ${d.reason}")
            is Pairing.Appeared.Connect -> {
                if (bikes.active()?.id != d.bikeId) bikes.setActive(d.bikeId)
                startService(ctx, "display appeared")
            }
        }
    }

    /** Like PresenceService.disappeared: a Disconnect pause lasts until the bike is switched off. */
    fun disappeared(ctx: Context) {
        val all = ctx.bikes.all()
        val id = Pairing.onDisappeared(all, Simulator.MAC, ConnectionSettings.pausedBikes(ctx.settings, all)) ?: return
        ctx.settings.set(ConnectionSettings.pausedUntilOff, id, false)
        Repo.log("sim: display gone, auto-connect for bike $id active again")
    }

    private fun startService(ctx: Context, why: String) {
        try {
            BikeService.start(ctx)
            Repo.log("sim: $why, BikeService started")
        } catch (e: Exception) {
            // Android refuses foreground-service starts from the background unless the app is visible or exempt.
            Repo.log("sim: $why, cannot start BikeService: $e (open the app first, or: dumpsys deviceidle whitelist +${ctx.packageName})")
        }
    }
}
