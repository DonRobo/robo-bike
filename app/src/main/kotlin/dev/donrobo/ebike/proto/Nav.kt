package dev.donrobo.ebike.proto

/** Maneuver icons understood by the display (op 0x03 to 0xF1, see docs/PROTOCOL.md "Navigation payload"). */
enum class Icon(val code: Int) {
    UNKNOWN(0), STRAIGHT(1), LEFT(2), RIGHT(3), SLIGHT_LEFT(4), SLIGHT_RIGHT(5),
    SHARP_LEFT(6), SHARP_RIGHT(7), UTURN(8), ARRIVED(10),
}

data class NavUpdate(
    val distToTurnM: Int,
    val icon: Icon,
    val distTurnToNextM: Int = 0,
    val nextIcon: Icon = Icon.UNKNOWN,
    val distNextToNext2M: Int = 0,
    val next2Icon: Icon = Icon.UNKNOWN,
    val remainingM: Long = 0,
) {
    fun payload(): ByteArray =
        u24(distToTurnM) + icon.code.toByte() + u24(distTurnToNextM) + nextIcon.code.toByte() +
            u24(distNextToNext2M) + next2Icon.code.toByte() + le(remainingM.coerceIn(0, 0xFFFFFFFFL), 4)

    private fun u24(v: Int) = le(v.coerceIn(0, 0xFFFFFF).toLong(), 3)
}

/** Sequence bookkeeping like the app: start uses seq 1, each update/end pre-increments. */
class NavSession {
    var seq = 0
        private set
    /** Tracked separately from [seq], which wraps 255 -> 0 during a long navigation. */
    var active = false
        private set

    fun start(): ByteArray { seq = 1; active = true; return frame(1) }
    fun update(u: NavUpdate): ByteArray { seq = (seq + 1) and 0xFF; return frame(2, u.payload()) }
    fun end(): ByteArray { seq = (seq + 1) and 0xFF; active = false; return frame(3).also { seq = 0 } }

    private fun frame(cmd: Int, payload: ByteArray = ByteArray(0)) =
        buildFrame(Dev.NAV, Op.COMMAND, 0, byteArrayOf(seq.toByte(), cmd.toByte()) + payload)
}
