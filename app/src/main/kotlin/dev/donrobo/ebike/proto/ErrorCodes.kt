package dev.donrobo.ebike.proto

/**
 * Drive error codes as the EKD01 exposes them over BLE (docs/PROTOCOL.md "Error codes").
 *
 * Two sources:
 *  - error register `0xA5:120` (16 bytes, the app's error list; the firmware answers with 32 bytes,
 *    only the first 16 belong to the error block),
 *  - live push report 1, byte 0 (current error, 0 = none; the app uploads it to its server when != 0).
 *
 * What the app does is confirmed in code; how the firmware encodes a code into a byte is not,
 * because no real error has been observed yet. The app renders each non-zero byte as hex
 * (`byte.toRadixString(16)`), which only yields the code printed on the display ("21") if the byte
 * is BCD (0x21). That is the default here. [Encoding.ASCII] covers the other plausible layout
 * (Bafang CAN display info `60 07`: ASCII digit pairs, as parsed by OpenBafangTool).
 */
object ErrorCodes {
    const val HISTORY_TARGET = 0xA5
    const val HISTORY_REG = 120
    const val HISTORY_LEN = 16

    /** Codes from the public Bafang display error table (e.g. DP E12 manual, section 7.7). */
    val KNOWN: Set<Int> = setOf(
        4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 21, 25, 26, 27, 30, 33, 35, 36, 37,
        41, 42, 43, 44, 45, 46, 47, 48, 61, 62, 71, 81,
    )

    enum class Encoding {
        /** One code per byte, BCD: 0x21 -> 21. Matches how BikeGo displays the bytes. */
        BCD,
        /** One code per byte, plain binary: 0x15 -> 21. */
        BINARY,
        /** ASCII digit pairs, two bytes per code: "21" -> 21 (Bafang CAN `60 07` layout). */
        ASCII,
        /** ASCII if every used byte is an ASCII digit and their count is even, otherwise BCD. */
        AUTO,
    }

    /**
     * One error entry. [raw] is the byte (for ASCII: the first of the two digit bytes),
     * [code] the number as printed on the display, or null if [raw] is not valid in the encoding.
     */
    data class DriveError(val raw: Int, val code: Int?) {
        val known: Boolean get() = code != null && code in KNOWN
    }

    /** Resource key prefix for the texts in res/values/strings_errors.xml, e.g. `error_04`. */
    fun resourceKey(code: Int): String = "error_%02d".format(code)

    /**
     * Error register `0xA5:120` → codes in register order (index 0 first, which is the only entry
     * BikeGo ever shows). Empty bytes (0x00, and erased-flash 0xFF) are skipped; all-zero = no error.
     */
    fun decodeHistory(reg: ByteArray, encoding: Encoding = Encoding.AUTO): List<DriveError> {
        val enc = resolve(used(reg), encoding)
        return historyEntries(reg, enc).map { e ->
            if (enc == Encoding.ASCII) {
                val (hi, lo) = e
                val ok = hi in 0x30..0x39 && lo in 0x30..0x39
                DriveError(hi, if (ok) (hi - 0x30) * 10 + (lo - 0x30) else null)
            } else {
                single(e[0], enc)
            }
        }
    }

    /**
     * The error register split into its entries, in register order, as unsigned bytes: only the first
     * [HISTORY_LEN] bytes, 0x00/0xFF are empty, two bytes per entry for ASCII digit pairs, one otherwise.
     * The splitting rule of [decodeHistory]; the error log keeps these raw entries.
     */
    fun historyEntries(reg: ByteArray, encoding: Encoding = Encoding.AUTO): List<List<Int>> {
        val used = used(reg)
        return if (resolve(used, encoding) == Encoding.ASCII) used.chunked(2).filter { it.size == 2 } else used.map { listOf(it) }
    }

    private fun used(reg: ByteArray) = reg.take(HISTORY_LEN).map { it.toInt() and 0xFF }.filter { it != 0x00 && it != 0xFF }

    private fun resolve(used: List<Int>, encoding: Encoding) = if (encoding == Encoding.AUTO) {
        if (used.isNotEmpty() && used.size % 2 == 0 && used.all { it in 0x30..0x39 }) Encoding.ASCII else Encoding.BCD
    } else encoding

    /**
     * Live push report 1 (op 0x06, report id 1 from 0x10) → current error, or null if byte 0 is 0.
     * ASCII makes no sense for a single byte, so it is treated as BCD here.
     */
    fun currentFromReport(report: ByteArray, encoding: Encoding = Encoding.BCD): DriveError? {
        if (report.isEmpty()) return null
        val b = report[0].toInt() and 0xFF
        if (b == 0) return null
        return single(b, if (encoding == Encoding.BINARY) Encoding.BINARY else Encoding.BCD)
    }

    private fun single(b: Int, enc: Encoding): DriveError = when (enc) {
        Encoding.BINARY -> DriveError(b, b)
        else -> {
            val hi = b shr 4
            val lo = b and 0x0F
            DriveError(b, if (hi <= 9 && lo <= 9) hi * 10 + lo else null)
        }
    }
}
