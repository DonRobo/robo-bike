package dev.donrobo.ebike.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.BikeService
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.nav.MapsExtract
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.nav.TestManeuver
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.hexByte
import dev.donrobo.ebike.proto.unhex
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import java.util.Locale

/**
 * Debug builds only: remote control of the [Simulator] over `adb shell am broadcast` (commands and expected log
 * lines: android/README.md, "Simulated bike"). Declared in the debug manifest, exported but guarded by the DUMP
 * permission, which the adb shell holds and no ordinary app can get.
 *
 * Every command logs what it did with the prefix "sim:" (files/log.txt, logcat tag "ebike").
 */
class SimControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Simulator.init(ctx)
        val cmd = intent.getStringExtra("cmd")?.lowercase(Locale.ROOT)
        try {
            run(ctx, cmd, Args(intent))
        } catch (e: Exception) {
            Repo.log("sim: command '$cmd' failed: $e")
        }
    }

    /** Extras read leniently: a number may come as --ei, --el, --ef, --ed or --es. */
    private class Args(private val i: Intent) {
        @Suppress("DEPRECATION")
        private fun raw(k: String): Any? = i.extras?.get(k)
        fun has(k: String) = i.hasExtra(k)
        fun str(k: String): String? = raw(k)?.toString()
        fun num(k: String): Double? = when (val v = raw(k)) {
            is Number -> v.toDouble()
            is String -> v.trim().let { s -> if (s.startsWith("0x")) s.drop(2).toLong(16).toDouble() else s.toDouble() }
            null -> null
            else -> error("$k: not a number: $v")
        }
        fun int(k: String): Int? = num(k)?.toInt()
        fun bool(k: String): Boolean? = when (val v = raw(k)) {
            is Boolean -> v
            is String -> v.toBooleanStrict()
            is Number -> v.toInt() != 0
            null -> null
            else -> error("$k: not a boolean: $v")
        }
    }

    private fun run(ctx: Context, cmd: String?, a: Args) {
        when (cmd) {
            "pair" -> SimActions.pair(ctx, a.str("name"))
            "start" -> { scenario(a); SimActions.start(ctx) }
            "on" -> { scenario(a); Simulator.powerOn(); SimActions.appeared(ctx) }
            "off" -> SimActions.off(ctx)
            "drop" -> if (!Simulator.dropLink()) Repo.log("sim: drop: no phone connected")
            "scenario" -> if (!scenario(a)) Repo.log("sim: scenario: --es scenario ride|still is required")
            "set" -> set(a)
            "error" -> {
                val raw = a.int("raw") ?: ErrorHistory.bcd(requireNotNull(a.int("code")) { "--es code NN or --ei raw N" })
                val history = a.bool("history") ?: true
                Simulator.edit { d, _ -> d.raiseError(raw, history) }
                Repo.log("sim: drive error raw 0x${hexByte(raw)} raised${if (history) ", added to the history register" else ""}")
            }
            "error-clear" -> { Simulator.edit { d, _ -> d.errorRaw = 0 }; Repo.log("sim: drive error cleared (report byte 0 = 0)") }
            "history-clear" -> { Simulator.edit { d, _ -> d.history = ByteArray(16) }; Repo.log("sim: error history register cleared") }
            "nav-maps" -> navMaps(a)
            "nav-maps-end" -> { Repo.publishMapsNav(null); Repo.log("sim: Maps nav ended") }
            "nav-test" -> {
                val s = NavState(a.int("dist") ?: 200, icon(a.str("icon")) ?: Icon.LEFT, (a.num("remaining") ?: 1500.0).toLong())
                TestManeuver.start(s)
                Repo.log("sim: test maneuver $s (Diagnose path; ends by itself after ${TestManeuver.TIMEOUT_MS / 1000} s)")
            }
            "nav-test-end" -> { TestManeuver.stop(); Repo.log("sim: test maneuver stopped") }
            "status" -> status()
            else -> Repo.log("sim: unknown command '$cmd'; commands: $COMMANDS")
        }
    }

    /** Applies --es scenario / --ef speedup if given. True if a scenario was started. */
    private fun scenario(a: Args): Boolean {
        val name = a.str("scenario") ?: return false
        val s = Simulator.scenario(name) ?: run { Repo.log("sim: unknown scenario '$name' (ride, still)"); return false }
        Simulator.play(s, a.num("speedup") ?: 1.0)
        return true
    }

    private fun set(a: Args) {
        val changes = mutableListOf<String>()
        Simulator.edit { d, e ->
            a.str("release")?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.forEach { k ->
                when (k) {
                    "speed" -> e.speedOverride = null
                    "battery" -> e.batteryOverride = null
                    "level", "gear" -> e.gearOverride = null
                    "all" -> { e.speedOverride = null; e.batteryOverride = null; e.gearOverride = null }
                }
                changes += "released $k"
            }
            a.num("speed")?.let { e.speedOverride = it.coerceAtLeast(0.0); d.speedKmh = it; changes += "speed $it km/h" }
            a.int("battery")?.let { e.batteryOverride = it.coerceIn(0, 100); d.batteryPct = it.coerceIn(0, 100); changes += "battery $it %" }
            (a.int("level") ?: a.int("gear"))?.let { e.gearOverride = it; d.gear = it; changes += "level $it" }
            a.int("gears")?.let { d.gears = it; changes += "levels $it" }
            a.bool("light")?.let { d.lightOn = it; changes += "light ${if (it) "on" else "off"} (button on the bike)" }
            a.bool("locked")?.let { d.unlocked = !it; changes += if (it) "locked" else "unlocked" }
            a.num("odo")?.let { d.odoKm = it; changes += "odo $it km" }
            a.num("trip")?.let { d.tripKm = it; changes += "trip $it km" }
            a.str("features")?.let { f ->
                d.featureBlock = when (f.lowercase(Locale.ROOT)) {
                    "default" -> FakeDisplay.DEFAULT_FEATURES.copyOf()
                    "none" -> null
                    else -> f.unhex().also { b -> require(b.size == 26) { "feature block must be 26 bytes" } }
                }
                changes += "feature block ${d.featureBlock?.hex() ?: "no answer"} (read at the next connection)"
            }
        }
        Repo.log("sim: set ${changes.joinToString().ifEmpty { "nothing (no known extra)" }}")
    }

    /** Runs texts through the same parser as the Maps notification listener and publishes the result like it does. */
    private fun navMaps(a: Args) {
        val texts = listOfNotNull(a.str("title"), a.str("text"), a.str("summary")).filter { it.isNotBlank() }
        val parsed = MapsExtract.parseTexts(texts)
        val state = MapsExtract.navState(parsed, parsed.textIcon ?: icon(a.str("icon")) ?: Icon.UNKNOWN)
        val prev = Repo.mapsNav.value
        Repo.publishMapsNav(state)
        Repo.log("sim: Maps notification ${texts.joinToString(" | ") { "\"$it\"" }} -> ${state ?: "no maneuver (not sent)"}${if (prev == null && state != null) ", Maps nav started" else ""}")
    }

    private fun icon(s: String?): Icon? = s?.let { n -> Icon.entries.firstOrNull { it.name.equals(n.replace('-', '_'), ignoreCase = true) } }

    private fun status() {
        Repo.log(Simulator.describe())
        Repo.log("sim: app sees conn ${Repo.conn.value}, live ${Repo.live.value}, display nav ${Repo.displayNav.value}, maps ${Repo.mapsNav.value}, test ${Repo.testNav.value}")
    }

    private companion object {
        const val COMMANDS = "pair, start, on, off, drop, scenario, set, error, error-clear, history-clear, nav-maps, nav-maps-end, nav-test, nav-test-end, status"
    }
}
