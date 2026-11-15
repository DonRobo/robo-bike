package dev.donrobo.ebike.proto

/** Frame layer: `55 AA LEN SRC DST OP REG DATA[LEN] CK_L CK_H` (see docs/PROTOCOL.md). */
object Op {
    const val READ = 0x01
    const val WRITE = 0x02
    const val COMMAND = 0x03
    const val READ_RESP = 0x04
    const val WRITE_ACK = 0x05
    const val REPORT = 0x06
    const val AUTH = 0x20
}

object Dev {
    const val DISPLAY = 0x10
    const val CONTROLLER = 0xA3
    const val METER = 0xA5
    const val NAV = 0xF1
}

const val PHONE = 0x11

/** Checksum over `LEN SRC DST OP REG DATA`: 0xFFFF ^ sum, little-endian. */
fun checksum(body: ByteArray): ByteArray {
    val ck = 0xFFFF xor (body.sumOf { it.toInt() and 0xFF } and 0xFFFF)
    return byteArrayOf(ck.toByte(), (ck shr 8).toByte())
}

fun buildFrame(dst: Int, op: Int, reg: Int, data: ByteArray = ByteArray(0), src: Int = PHONE): ByteArray {
    require(data.size <= 0xFF) { "payload too long" }
    val body = byteArrayOf(data.size.toByte(), src.toByte(), dst.toByte(), op.toByte(), reg.toByte()) + data
    return byteArrayOf(0x55, 0xAA.toByte()) + body + checksum(body)
}

class Frame(val src: Int, val dst: Int, val op: Int, val reg: Int, val data: ByteArray, val checksumOk: Boolean) {
    fun toBytes() = buildFrame(dst, op, reg, data, src)
    override fun toString() =
        "%02x->%02x op=%02x reg=%d [%s]%s".format(src, dst, op, reg, data.hex(), if (checksumOk) "" else " BAD-CK")

    companion object {
        fun parse(raw: ByteArray): Frame {
            require(raw.size >= 9 && raw[0] == 0x55.toByte() && raw[1] == 0xAA.toByte()) { "not a frame" }
            val len = raw[2].toInt() and 0xFF
            require(raw.size == len + 9) { "length mismatch" }
            val ck = checksum(raw.copyOfRange(2, raw.size - 2))
            return Frame(
                raw[3].toInt() and 0xFF, raw[4].toInt() and 0xFF, raw[5].toInt() and 0xFF, raw[6].toInt() and 0xFF,
                raw.copyOfRange(7, 7 + len), ck[0] == raw[raw.size - 2] && ck[1] == raw[raw.size - 1],
            )
        }
    }
}

/** Reassembles frames from arbitrary notification chunks. */
class FrameParser {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<Frame> {
        buf += chunk
        val out = ArrayList<Frame>()
        while (true) {
            var i = -1
            for (k in 0 until buf.size - 1) if (buf[k] == 0x55.toByte() && buf[k + 1] == 0xAA.toByte()) { i = k; break }
            if (i < 0) {
                // keep a trailing 0x55 in case the header is split across chunks
                buf = if (buf.isNotEmpty() && buf.last() == 0x55.toByte()) byteArrayOf(0x55) else ByteArray(0)
                return out
            }
            buf = buf.copyOfRange(i, buf.size)
            if (buf.size < 3) return out
            val total = (buf[2].toInt() and 0xFF) + 9
            if (buf.size < total) return out
            out += Frame.parse(buf.copyOfRange(0, total))
            buf = buf.copyOfRange(total, buf.size)
        }
    }
}

fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
