package dev.donrobo.ebike

import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings
import java.util.Locale

/** The last report received from a bike and when (U2). Speed is not kept: it is only shown live. */
data class LastKnown(val atMs: Long, val report: BikeReport) {
    companion object {
        /** Per bike. Written by [LastKnownWriter] only. */
        val setting = Setting.mapped<LastKnown?>("last.report", null, Scope.PER_BIKE, ::decode, { it?.let(::encode) })

        /**
         * "atMs;light;gear;gears;battery;trip;odo;errorRaw" (locale-independent). Values stored before the drive
         * error was kept have 7 fields and read with errorRaw 0.
         */
        fun encode(v: LastKnown): String = with(v.report) {
            String.format(Locale.ROOT, "%d;%d;%d;%d;%d;%.2f;%.2f;%d", v.atMs, if (lightOn) 1 else 0, gear, gears, batteryPct, tripKm, odoKm, errorRaw)
        }

        fun decode(s: String): LastKnown? = runCatching {
            val f = s.split(';')
            if (f.size != 7 && f.size != 8) return null
            LastKnown(
                f[0].toLong(),
                BikeReport(
                    f[1] == "1", f[2].toInt(), f[3].toInt(), f[4].toInt(), speedKmh = 0.0, f[5].toDouble(), f[6].toDouble(),
                    errorRaw = f.getOrNull(7)?.toInt() ?: 0,
                ),
            )
        }.getOrNull()
    }
}

/**
 * Persists reports of one connection session as [LastKnown]. Not every 0.5 s report is written: only when
 * something other than speed/trip/ODO changed, or [maxAgeMs] after the last write; [flush] writes the
 * newest one at the end of the session, so the stored state is exactly the last report received.
 * Not thread-safe: one session coroutine feeds it (not the GATT callback thread).
 */
class LastKnownWriter(
    private val settings: Settings,
    private val bikeId: String,
    private val maxAgeMs: Long = 30_000,
) {
    private var written: LastKnown? = null
    private var pending: LastKnown? = null

    fun record(report: BikeReport, nowMs: Long) {
        val next = LastKnown(nowMs, report.copy(speedKmh = 0.0))
        pending = next
        if (needsWrite(written, next, maxAgeMs)) write(next)
    }

    fun flush() { pending?.takeIf { it != written }?.let(::write) }

    private fun write(v: LastKnown) {
        settings.set(LastKnown.setting, bikeId, v)
        written = v
    }

    companion object {
        fun needsWrite(prev: LastKnown?, next: LastKnown, maxAgeMs: Long): Boolean {
            if (prev == null) return true
            val a = prev.report; val b = next.report
            if (a.lightOn != b.lightOn || a.gear != b.gear || a.gears != b.gears || a.batteryPct != b.batteryPct || a.errorRaw != b.errorRaw) return true
            return next.atMs - prev.atMs >= maxAgeMs
        }
    }
}
