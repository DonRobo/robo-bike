package dev.donrobo.ebike.sim

import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.Frame
import dev.donrobo.ebike.proto.FrameParser
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.NavUpdate
import dev.donrobo.ebike.proto.Op
import dev.donrobo.ebike.proto.PHONE
import dev.donrobo.ebike.proto.authResponse
import dev.donrobo.ebike.proto.buildFrame
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.le
import dev.donrobo.ebike.proto.unhex
import dev.donrobo.ebike.proto.u32
import java.util.Random
import kotlin.math.roundToLong

/**
 * What the simulated display shows for navigation: [active] = a session is open (start seen, no end yet);
 * [update] = the last update frame, decoded. [frames] counts all op-3 frames received.
 */
data class NavShown(val active: Boolean = false, val seq: Int = 0, val update: NavUpdate? = null, val frames: Int = 0) {
    fun describe(): String = if (!active) "no navigation" else update?.let {
        "${it.icon} in ${it.distToTurnM} m (box ${it.distTurnToNextM} m), ${it.remainingM} m to go"
    } ?: "navigation open, no update yet"
}

/**
 * The EKD01 display at the protocol level (docs/PROTOCOL.md), for the debug simulator: it takes the bytes the
 * phone writes and answers with the bytes the real display would notify. Behaviour mirrors what was observed on
 * the real device (a register dump of a real display, the app's logs); where the real behaviour is unknown, the choice is
 * noted at the spot.
 *
 *  - Reads (op 1) answer with the firmware's own register length, not the requested one (`0xA5:120` → 32
 *    bytes: the 16-byte error block followed by the first 16 bytes of reg 136, exactly as the real one).
 *    Registers the real display never answered (0x10:69, 0xA3:136, 0xA6, 0xA8, 0xF1:2) get no answer.
 *  - Writes (op 2) are acknowledged with status 0 and reflected: the lamp switch `0xA5:166` sets the light bit
 *    of later reports, `0xA5:164` the assist level, `0x10:14` the lock state; the clock registers 62/66/70 read
 *    back what was written. Writes the app never makes (0xA3, 0xA1, unknown registers) get no answer.
 *  - Navigation commands (op 3 to 0xF1) get no answer, like on the real one; what they would show is kept in
 *    [nav].
 *  - Authentication (op 0x20): a fresh random challenge per read of 0x10:0, answer `00` only for the right
 *    AES response.
 *
 * Not thread-safe; [Simulator] serialises access.
 */
class FakeDisplay(private val random: Random = Random(), private val log: (String) -> Unit = {}) {
    // --- live values, pushed in report 1 ---------------------------------------------------------
    var speedKmh = 0.0
    var batteryPct = 72
    var gear = 1
    var gears = 5
    var lightOn = false
    var unlocked = true
    /** Report 1 byte 0, the current drive error (0 = none). BCD like the app's default decoding. */
    var errorRaw = 0
    var tripKm = 0.70
    var odoKm = 0.70
    /** Report 9 bytes 0–3. */
    var rideTimeS = 174.0

    /** 0xF1 reg 1. Null = no answer (what the real display answers was never observed, see [DEFAULT_FEATURES]). */
    var featureBlock: ByteArray? = DEFAULT_FEATURES.copyOf()

    var nav = NavShown()
        private set

    /** The last auth response was correct. */
    var authenticated = false
        private set

    private var challenge = ByteArray(4)
    private val parser = FrameParser()
    private val regs: Map<Int, MutableMap<Int, ByteArray>> = initialRegisters()

    /** The 16-byte error history block, `0xA5:120`. */
    var history: ByteArray
        get() = regs.getValue(Dev.METER).getValue(ErrorHistory.REG).copyOf()
        set(v) { regs.getValue(Dev.METER)[ErrorHistory.REG] = v.copyOf(16) }

    /** Raw stored bytes of a register (for tests and the status line); null if the display does not have it. */
    fun register(target: Int, reg: Int): ByteArray? = regs[target]?.get(reg)?.copyOf()

    /** Raises a drive error: report byte 0 and the first free slot of the history register. */
    fun raiseError(raw: Int, intoHistory: Boolean) {
        errorRaw = raw and 0xFF
        if (intoHistory) history = ErrorHistory.add(history, raw)
    }

    // --- phone -> display --------------------------------------------------------------------------

    /** Bytes the phone wrote (any chunking) → the frames the display answers with, in order. */
    fun receive(bytes: ByteArray): List<ByteArray> =
        try { parser.feed(bytes) } catch (e: IllegalArgumentException) { log("garbage from phone: ${e.message}"); emptyList() }
            .mapNotNull(::handle)

    private fun handle(f: Frame): ByteArray? {
        if (!f.checksumOk || f.src != PHONE) return null
        return when (f.op) {
            // The request's DATA byte is the length the app expects; the display answers with its own length.
            Op.READ -> read(f.dst, f.reg)?.let { reply(f.dst, Op.READ_RESP, f.reg, it) }
            Op.WRITE -> write(f.dst, f.reg, f.data)?.let { reply(f.dst, Op.WRITE_ACK, f.reg, byteArrayOf(it.toByte())) }
            Op.COMMAND -> { if (f.dst == Dev.NAV && f.reg == 0) command(f.data); null }
            Op.AUTH -> if (f.dst == Dev.DISPLAY && f.reg == 0) reply(Dev.DISPLAY, Op.AUTH, 0, byteArrayOf(auth(f.data).toByte())) else null
            else -> null
        }
    }

    private fun reply(src: Int, op: Int, reg: Int, data: ByteArray) = buildFrame(PHONE, op, reg, data, src = src)

    private fun auth(resp: ByteArray): Int {
        authenticated = resp.contentEquals(authResponse(challenge))
        // The real display's answer to a wrong response was never observed; anything but 00 is a rejection.
        return if (authenticated) 0 else 1
    }

    private fun read(target: Int, reg: Int): ByteArray? {
        if (target == Dev.DISPLAY && reg == 0) {
            challenge = ByteArray(4).also(random::nextBytes) // changes with every read, like the real one
            return challenge.copyOf()
        }
        if (target == Dev.NAV) return if (reg == 1) featureBlock?.copyOf() else null
        live(target, reg)?.let { return it }
        val table = regs[target] ?: return null
        val own = table[reg]?.takeIf { (target to reg) !in WRITE_ONLY } ?: return null
        val len = ANSWER_LEN[target to reg] ?: return own.copyOf()
        // The firmware's map is longer than the app's: append the following registers' bytes.
        var out = own
        for (next in table.keys.filter { it > reg }.sorted()) {
            if (out.size >= len) break
            out += table.getValue(next)
        }
        return out.copyOf(len)
    }

    /** Registers that mirror live state on the real display. */
    private fun live(target: Int, reg: Int): ByteArray? = when (target to reg) {
        Dev.DISPLAY to 14 -> byteArrayOf(if (unlocked) 1 else 0)
        Dev.CONTROLLER to 160 -> le(batteryPct.toLong(), 2)
        Dev.CONTROLLER to 162 -> le((tripKm * 100).toLong() and 0xFFFF, 2)
        Dev.CONTROLLER to 192 -> le(gear.toLong(), 2)
        Dev.CONTROLLER to 194 -> le(gears.toLong(), 2)
        A1 to 0 -> challenge.copyOf()
        A1 to 59 -> ByteArray(64).also { b ->
            // 0xA1:59 mirrors 0x10 regs 62/66/70 at bytes 3/7/11
            listOf(62, 66, 70).forEachIndexed { i, r -> regs.getValue(Dev.DISPLAY).getValue(r).copyInto(b, 3 + 4 * i) }
        }
        else -> null
    }

    /** Status byte of the ack, or null for no answer. */
    private fun write(target: Int, reg: Int, data: ByteArray): Int? {
        if ((target to reg) !in WRITABLE) return null
        val table = regs.getValue(target)
        // The app always sends exactly the register's length; what the real display does otherwise is unknown.
        if (data.size != table.getValue(reg).size) return 1
        table[reg] = data.copyOf()
        when (target to reg) {
            Dev.METER to LAMP_REG -> lightOn = data[0].toInt() != 0
            Dev.METER to GEAR_REG -> gear = data[0].toInt() and 0xFF
            Dev.DISPLAY to 14 -> unlocked = data[0].toInt() == 1
        }
        return 0
    }

    private fun command(d: ByteArray) {
        if (d.size < 2) return
        val seq = d[0].toInt() and 0xFF
        val before = nav
        nav = when (d[1].toInt()) {
            1 -> NavShown(active = true, seq = seq, update = null, frames = nav.frames + 1)
            2 -> nav.copy(seq = seq, update = decodeUpdate(d) ?: nav.update, frames = nav.frames + 1)
            3 -> nav.copy(active = false, seq = seq, frames = nav.frames + 1)
            else -> nav.copy(frames = nav.frames + 1)
        }
        if (nav.copy(seq = 0, frames = 0) != before.copy(seq = 0, frames = 0)) log("shows ${nav.describe()}")
    }

    private fun decodeUpdate(d: ByteArray): NavUpdate? {
        if (d.size < 18) return null // seq, cmd, 16 payload bytes (docs/PROTOCOL.md "Frame layout")
        fun u24(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or ((d[i + 2].toInt() and 0xFF) shl 16)
        fun icon(i: Int) = (d[i].toInt() and 0xFF).let { c -> Icon.entries.firstOrNull { it.code == c } ?: Icon.UNKNOWN }
        return NavUpdate(u24(2), icon(5), u24(6), icon(9), u24(10), icon(13), u32(d, 14))
    }

    // --- display -> phone (unsolicited) ------------------------------------------------------------

    /** Report 1, 21 bytes (layout: docs/PROTOCOL.md "ID 1 – Bike (live)"). */
    fun report1(): ByteArray = ByteArray(21).also { d ->
        d[0] = errorRaw.toByte()
        // byte 2 (pwd/BLE lock bits) stays 0: it was 0 in every observed report and its meaning is unconfirmed
        d[3] = if (unlocked) 1 else 0
        d[4] = if (lightOn) 1 else 0
        d[5] = gear.toByte()
        d[6] = gears.toByte()
        d[7] = batteryPct.coerceIn(0, 255).toByte()
        le((speedKmh * 100).roundToLong().coerceIn(0, 0xFFFF), 2).copyInto(d, 9)   // 0.01 km/h
        le((tripKm * 100).toLong().coerceAtLeast(0), 4).copyInto(d, 11)             // 10 m
        le((odoKm * 100).toLong().coerceAtLeast(0), 4).copyInto(d, 15)              // 10 m
    }

    fun report1Frame(): ByteArray = buildFrame(PHONE, Op.REPORT, 1, report1(), src = Dev.DISPLAY)

    /** Report 9 (16 bytes): ride time in bytes 0–3, the rest as observed (meaning doubtful, the app ignores it). */
    fun report9Frame(): ByteArray =
        buildFrame(PHONE, Op.REPORT, 9, le(rideTimeS.toLong(), 4) + REPORT9_TAIL, src = Dev.DISPLAY)

    fun describe(): String = String.format(
        java.util.Locale.ROOT,
        "speed %.1f km/h, battery %d %%, level %d/%d, light %s, %s, error 0x%02x, trip %.2f km, odo %.2f km, nav: %s, history [%s], clock reg62 [%s]",
        speedKmh, batteryPct, gear, gears, if (lightOn) "on" else "off", if (unlocked) "unlocked" else "locked",
        errorRaw, tripKm, odoKm, nav.describe(), history.hex(), regs.getValue(Dev.DISPLAY).getValue(62).hex(),
    )

    companion object {
        private const val A1 = 0xA1
        const val LAMP_REG = 166
        const val GEAR_REG = 164

        /**
         * Assumed feature block. The real display's answer to `0xF1:1` has never been observed (the dumps did
         * not read it), so this advertises what the real display demonstrably has: lamp switch (works),
         * notifications (0xA5:225 answers), date/time (0x10:62 is acknowledged), BLE unlock (0xA5:226 = 5),
         * unit (0xA5:224), assist level (report 1, 5 levels), backlight (0xA5:167 = 1), unlock level,
         * error codes (0xA5:120 answers). Bit layout: [dev.donrobo.ebike.proto.MeterConfig].
         */
        val DEFAULT_FEATURES: ByteArray = ByteArray(26).also {
            it[3] = 0x87.toByte()      // lamp switch, push notify, date-time, BLE unlock
            it[6] = 0x08               // unit
            it[7] = 0x80.toByte()      // gear, min gear 0
            it[8] = (0x80 or (5 shl 1) or 1).toByte() // brightness 1..5
            it[9] = (0x80 or (5 shl 1)).toByte()      // unlock level 0..5
            it[11] = 0x80.toByte()     // error code
        }

        /** The firmware's own register lengths where they differ from the stored register. */
        private val ANSWER_LEN = mapOf((Dev.METER to ErrorHistory.REG) to 32)

        /** Registers the app writes (docs/PROTOCOL.md "Registers the app uses"); 164/166 are not readable on the real display (not in its read map). */
        private val WRITE_ONLY = setOf(Dev.METER to GEAR_REG, Dev.METER to LAMP_REG)
        private val WRITABLE = setOf(
            Dev.DISPLAY to 14, Dev.DISPLAY to 62, Dev.DISPLAY to 66, Dev.DISPLAY to 70,
            Dev.METER to GEAR_REG, Dev.METER to LAMP_REG, Dev.METER to 167, Dev.METER to 224, Dev.METER to 225, Dev.METER to 226,
        )

        private val REPORT9_TAIL = "9305730c0401de0351000105".unhex()

        private fun ascii(s: String, len: Int) = s.toByteArray(Charsets.US_ASCII).copyOf(len)

        /** Register contents as read from a real display (register dump); the serial number is made up. */
        private fun initialRegisters(): Map<Int, MutableMap<Int, ByteArray>> {
            val display = mutableMapOf(
                4 to ByteArray(2), 6 to ByteArray(2), 8 to ByteArray(6), 14 to byteArrayOf(1), 15 to ByteArray(33),
                48 to ByteArray(2), 50 to ByteArray(1), 51 to ByteArray(1), 52 to ByteArray(1), 53 to ByteArray(1),
                54 to ByteArray(3), 57 to ByteArray(1), 58 to ByteArray(4),
                62 to "e931ee5f".unhex(), 66 to "40380000".unhex(), 70 to "eb31ee5f".unhex(), // never-synced factory clock
                80 to ByteArray(2), 82 to ByteArray(1), 83 to ByteArray(1), 84 to ByteArray(1), 85 to ByteArray(1), 86 to ByteArray(1),
            )
            val a1 = mutableMapOf(
                0 to ByteArray(4), 4 to ByteArray(4), 8 to ByteArray(4), 12 to "00000100".unhex(), 16 to ByteArray(8),
                24 to ByteArray(20), 44 to ByteArray(15), 59 to ByteArray(64), 123 to ByteArray(8),
                131 to ByteArray(32), 163 to ByteArray(32), 195 to ByteArray(32),
            )
            val controller = mutableMapOf<Int, ByteArray>()
            listOf(0, 24, 48).forEach { controller[it] = ByteArray(24) }
            listOf(72, 88, 104, 120).forEach { controller[it] = ByteArray(16) }
            for (r in 160..206 step 2) controller[r] = ByteArray(2)
            controller[164] = "8c00".unhex(); controller[166] = "9cca".unhex()
            controller[176] = "9313".unhex(); controller[184] = "1027".unhex()
            val meter = mutableMapOf(
                0 to ascii("B02NC_1.01", 24), 24 to ascii("EKD01_CAN_BF_APO_N04", 24), 48 to ascii("model undetermined", 24),
                72 to ascii("SIMULATED0T0001", 16), 88 to ascii("cn undetermined", 16), 104 to ascii("mf undetermined", 16),
                ErrorHistory.REG to ByteArray(16), 136 to ascii("EKD01-BF ", 24),
                GEAR_REG to ByteArray(2), LAMP_REG to ByteArray(2), 167 to byteArrayOf(1),
                176 to ByteArray(4), 180 to ByteArray(2), 182 to "730c9305ae0000004600000046000000ffffffffffffff01ff00ff".unhex(),
                224 to byteArrayOf(0), 225 to byteArrayOf(1), 226 to byteArrayOf(5),
            )
            return mapOf(Dev.DISPLAY to display, A1 to a1, Dev.CONTROLLER to controller, Dev.METER to meter)
        }
    }
}

/** The error history block `0xA5:120` as the simulator fills it: one code byte per slot, in order of occurrence. */
object ErrorHistory {
    const val REG = 120

    /** Puts [raw] into the first empty (0x00) slot; when all 16 are used, the oldest is dropped. */
    fun add(block: ByteArray, raw: Int): ByteArray {
        val b = block.copyOf(16)
        val free = b.indexOfFirst { it.toInt() == 0 }
        return if (free >= 0) b.also { it[free] = raw.toByte() } else (b.copyOfRange(1, 16) + raw.toByte())
    }

    /** A two-digit code as the display would store it, BCD (see [dev.donrobo.ebike.proto.ErrorCodes]). */
    fun bcd(code: Int): Int {
        require(code in 1..99) { "code must be 1..99" }
        return ((code / 10) shl 4) or (code % 10)
    }
}
