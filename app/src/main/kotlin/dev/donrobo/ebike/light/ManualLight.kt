package dev.donrobo.ebike.light

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.display.DisplayCapabilitiesStore
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException

/**
 * The rider switching the headlight by hand (L1), from every surface: Start, Lenkermodus, home-screen widget,
 * quick-settings tile, connection notification. One command and one set of rules ([LightControl]): only while
 * connected and unlocked (BikeGo never sends the lamp command to a locked bike), and only if the display has a
 * lamp switch (Q4; unknown capabilities count as supported).
 *
 * The automation needs no notice: [LightDecider] sees the new lamp state in the next report and, because it
 * did not send it, counts it as a manual switch ([LightReason.MANUAL]) that holds until the next transition,
 * exactly like the button on the bike.
 */
object ManualLight {
    /**
     * Whether the display of the connected bike (or, between connections, the active bike) has a lamp switch,
     * from its stored feature block. True while the block was never read.
     */
    fun lampSwitch(ctx: Context): Boolean {
        val id = Repo.sessionBikeId.value ?: ctx.bikes.active()?.id ?: return true
        return DisplayCapabilitiesStore.get(ctx.settings, id).supports(DisplayFeature.LAMP_SWITCH)
    }

    /** Switches to [on]; false if not possible right now or the display rejected it (logged). [from] names the surface. */
    suspend fun set(ctx: Context, on: Boolean, from: String): Boolean {
        val bike = Repo.bike
        if (!lampSwitch(ctx)) {
            Repo.log("light by hand ($from): the display has no lamp switch")
            return false
        }
        if (bike == null || !LightControl.of(Repo.live.value?.report).switchable) {
            Repo.log("light by hand ($from): not possible now (not connected or locked)")
            return false
        }
        return try {
            bike.setLight(on)
            Repo.log("light by hand ($from): ${if (on) "on" else "off"}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.log("light by hand ($from): switching ${if (on) "on" else "off"} failed: ${e.message}")
            false
        }
    }
}
