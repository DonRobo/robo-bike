package dev.donrobo.ebike.sim

import java.util.Locale

/** One row of a recorded ride: [tS] seconds since the start, [distKm] distance since the start. */
data class RidePoint(val tS: Double, val speedKmh: Double, val batteryPct: Int, val gear: Int, val distKm: Double)

/** What the simulated bike does over (scenario) time. */
sealed interface Scenario {
    val name: String

    /** Standing still: speed 0, nothing changes unless set by hand. */
    data object Standstill : Scenario { override val name = "still" }

    /** Replays a recorded ride, then stands still with its last values. */
    class Replay(override val name: String, val points: List<RidePoint>) : Scenario {
        init { require(points.size >= 2) { "a ride needs at least 2 points" } }

        val durationS: Double get() = points.last().tS

        /**
         * The ride at [tS]: speed and distance interpolated between rows, battery and assist level as last
         * recorded (they are integers on the display and step, including the sag under load and the recovery).
         */
        fun at(tS: Double): RidePoint {
            val t = tS.coerceIn(0.0, durationS)
            var i = points.binarySearchBy(t) { it.tS }
            if (i >= 0) return points[i]
            i = -i - 1 // first point after t
            if (i == 0) return points[0]
            val a = points[i - 1]
            val b = points[i]
            val f = (t - a.tS) / (b.tS - a.tS)
            return RidePoint(t, a.speedKmh + (b.speedKmh - a.speedKmh) * f, a.batteryPct, a.gear, a.distKm + (b.distKm - a.distKm) * f)
        }
    }

    companion object {
        /** Name -> scenario; [ride] is the bundled ride (see [RideScript]). */
        fun byName(name: String, ride: () -> Replay): Scenario? = when (name.lowercase(Locale.ROOT)) {
            "ride", "replay" -> ride()
            "still", "standstill", "manual" -> Standstill
            else -> null
        }
    }
}

/**
 * The bundled ride file: `#` comment lines, header [HEADER], then one row per sample.
 * Made from one of the owner's recorded rides, reduced to time offset, speed, battery, assist level and distance
 * since the start (no positions, no absolute time or odometer).
 */
object RideScript {
    const val HEADER = "tS,speedKmh,batteryPct,gear,distKm"

    fun parse(text: String): List<RidePoint> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it != HEADER }
        .mapNotNull { l ->
            val f = l.split(',')
            if (f.size != 5) null else runCatching {
                RidePoint(f[0].toDouble(), f[1].toDouble(), f[2].toInt(), f[3].toInt(), f[4].toDouble())
            }.getOrNull()
        }.toList()
}

/**
 * Drives a [FakeDisplay] through a [Scenario] on top of the real clock.
 *
 * Time: the app always runs on real time (its own clocks are never faked). [speedup] only compresses the
 * scenario: each real second advances the scenario by [speedup] seconds, so a 25-minute ride plays in 2.5 min at
 * 10x. The reports still come about twice a second in real time, and every value in them (speed, battery,
 * level, odometer) is what the bike showed at that scenario moment. Consequence for anything that measures real
 * time: the ride recorder writes one sample per real second (one per [speedup] scenario seconds), a recorded
 * ride lasts duration/[speedup] while covering the full distance (average speed [speedup] times too high), and
 * its 5-minute end-of-ride rule counts real minutes. Only [speedup] 1 is a fully truthful end-to-end run.
 *
 * Manual values ([speedOverride], [batteryOverride], [gearOverride]) take precedence over the scenario until
 * cleared. A manual speed also moves the odometer (speed x scenario time).
 */
class SimEngine(val display: FakeDisplay) {
    var scenario: Scenario = Scenario.Standstill
        private set
    var speedup = 1.0
        private set
    var timeS = 0.0
        private set
    /** The replay reached its end (the bike stands with the ride's last values). */
    var finished = false
        private set

    var speedOverride: Double? = null
    var batteryOverride: Int? = null
    var gearOverride: Int? = null

    fun play(s: Scenario, speedup: Double) {
        require(speedup > 0) { "speedup must be > 0" }
        scenario = s
        this.speedup = speedup
        timeS = 0.0
        finished = false
        if (s is Scenario.Replay) apply(s.at(0.0), distKm = 0.0, dtS = 0.0) else apply(null, 0.0, 0.0)
    }

    /** Advances by [realDtMs] of wall time. Returns true when a replay reaches its end in this step. */
    fun tick(realDtMs: Long): Boolean {
        val dtS = realDtMs / 1000.0 * speedup
        val s = scenario
        if (s !is Scenario.Replay || finished) { apply(null, 0.0, dtS); return false }
        val from = s.at(timeS)
        timeS = minOf(timeS + dtS, s.durationS)
        val to = s.at(timeS)
        val justFinished = timeS >= s.durationS
        finished = justFinished
        apply(if (justFinished) to.copy(speedKmh = 0.0) else to, to.distKm - from.distKm, dtS)
        return justFinished
    }

    private fun apply(p: RidePoint?, distKm: Double, dtS: Double) {
        val d = display
        val manualSpeed = speedOverride
        d.speedKmh = manualSpeed ?: p?.speedKmh ?: 0.0
        (batteryOverride ?: p?.batteryPct)?.let { d.batteryPct = it }
        (gearOverride ?: p?.gear)?.let { d.gear = it }
        val dist = if (manualSpeed != null) manualSpeed * dtS / 3600.0 else distKm
        d.odoKm += dist
        d.tripKm += dist
        if (d.speedKmh > 0) d.rideTimeS += dtS
    }

    fun describe(): String = when (val s = scenario) {
        is Scenario.Replay -> String.format(Locale.ROOT, "%s %.0f/%.0f s at %.1fx%s", s.name, timeS, s.durationS, speedup, if (finished) " (finished)" else "")
        Scenario.Standstill -> "still"
    } + listOfNotNull(
        speedOverride?.let { "speed=$it" }, batteryOverride?.let { "battery=$it" }, gearOverride?.let { "level=$it" },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ", " (manual: ", ")").orEmpty()
}
