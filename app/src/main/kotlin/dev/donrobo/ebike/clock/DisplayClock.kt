package dev.donrobo.ebike.clock

import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.le
import dev.donrobo.ebike.proto.u32
import dev.donrobo.ebike.settings.Setting
import kotlinx.coroutines.flow.MutableStateFlow

object ClockSettings {
    /** E7: set the display clock at every connection (Einstellungen "Uhr am Display stellen"). */
    val auto = Setting.bool("clock.auto", true)
    /** D4 "Andere Byte-Reihenfolge": big-endian Unix time. Used by the manual and the automatic sync. */
    val bigEndian = Setting.bool("clock.bigEndian", false)

    /**
     * E7 + Q4: the automatic sync runs when the rider wants it and the display has a clock. A display whose feature
     * block was never read counts as having one.
     */
    fun autoWanted(enabled: Boolean, caps: DisplayCapabilities): Boolean = enabled && caps.supports(DisplayFeature.DATE_TIME)
}

/** Register access the clock sync needs; implemented by the BLE link. */
interface RegisterIo {
    suspend fun read(target: Int, reg: Int, len: Int): ByteArray
    suspend fun write(target: Int, reg: Int, data: ByteArray)
}

/**
 * Outcome of one clock sync. [wroteS] = Unix seconds written; [readBackS] = the value the display
 * returned afterwards, decoded in the same byte order (null if the read failed or was too short).
 */
data class ClockCheck(
    val atMs: Long,
    val bigEndian: Boolean,
    val wroteS: Long,
    val readBackS: Long?,
    /** The raw read-back bytes as hex, null if the read failed. */
    val readBackHex: String?,
    val automatic: Boolean,
) {
    /** The display kept what was written (it may tick in between, hence the tolerance). */
    val accepted: Boolean get() = readBackS != null && readBackS - wroteS in 0..ClockSync.TOLERANCE_S
}

/**
 * Display clock (docs/PROTOCOL.md "Time sync"). BikeGo writes three registers of target 0x10:
 * 62 = UTC seconds, 66 = time-zone offset in seconds, 70 = local seconds.
 *
 * What we write, and why:
 *  - 62, UTC seconds: the one register every source agrees on; byte order from [ClockSettings.bigEndian]
 *    (little-endian by default: the never-synced factory value 0x5FEE31E9 only reads as a plausible date,
 *    2020-12-31, in little-endian; BikeGo's disassembly suggests big-endian, which contradicts that).
 *  - 66, time-zone offset: BikeGo stores it byte by byte in little-endian, so its order is not in doubt,
 *    and a display that renders local time needs it. Always little-endian, signed.
 *  - 70 is NOT written: BikeGo calls it local seconds (UTC + offset), but the value read from the display
 *    was UTC + 2 s next to an offset of +4 h, so its meaning is unclear. It is read back and logged so a
 *    later sync shows whether the display derives it itself.
 */
object ClockSync {
    const val REG_UTC = 62
    const val REG_OFFSET = 66
    const val REG_LOCAL = 70
    const val TOLERANCE_S = 5L

    fun encode(epochS: Long, bigEndian: Boolean): ByteArray = le(epochS, 4).let { if (bigEndian) it.reversedArray() else it }

    fun decode(b: ByteArray, bigEndian: Boolean): Long? {
        if (b.size < 4) return null
        val four = b.copyOfRange(0, 4)
        return u32(if (bigEndian) four.reversedArray() else four, 0)
    }

    /** Signed 32-bit little-endian (BikeGo's explicit byte stores). */
    fun encodeOffset(offsetS: Int): ByteArray = le(offsetS.toLong(), 4)

    /** Writes 62 and 66, then reads 62 back. [log] receives one line per step. Throws if a write fails. */
    suspend fun sync(io: RegisterIo, nowMs: Long, offsetS: Int, bigEndian: Boolean, automatic: Boolean, log: (String) -> Unit): ClockCheck {
        val utc = nowMs / 1000
        io.write(Dev.DISPLAY, REG_UTC, encode(utc, bigEndian))
        runCatching { io.write(Dev.DISPLAY, REG_OFFSET, encodeOffset(offsetS)) }
            .onFailure { log("clock: offset write failed: ${it.message}") }
        val back = runCatching { io.read(Dev.DISPLAY, REG_UTC, 4) }.onFailure { log("clock: read-back failed: ${it.message}") }.getOrNull()
        val check = ClockCheck(nowMs, bigEndian, utc, back?.let { decode(it, bigEndian) }, back?.hex(), automatic)
        log(
            "clock: wrote $utc (${if (bigEndian) "big" else "little"}-endian), offset ${offsetS}s, read back " +
                (back?.let { "${it.hex()} = ${check.readBackS}" } ?: "nothing") +
                if (check.accepted) " -> accepted" else " -> NOT accepted",
        )
        runCatching { io.read(Dev.DISPLAY, REG_LOCAL, 4) }.getOrNull()?.let {
            log("clock: reg 70 = ${it.hex()} (LE ${decode(it, false)}, BE ${decode(it, true)})")
        }
        return check
    }
}

/** Last clock sync of this process (manual or automatic); written by whoever runs [ClockSync.sync]. */
object ClockState {
    val last = MutableStateFlow<ClockCheck?>(null)
}
