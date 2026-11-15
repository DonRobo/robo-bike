package dev.donrobo.ebike.errors

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.hexByte
import dev.donrobo.ebike.proto.unhex
import java.util.Locale

/**
 * One drive error in a bike's error log (W2).
 *
 * [source] LIVE: seen in byte 0 of the live report while connected; [seenAtMs] is when it occurred.
 * [source] HISTORY: found in the display's error register `0xA5:120` at connect, i.e. it happened while
 * the app was not connected; the real time is unknown and [seenAtMs] is when the app first saw it.
 * [rawHex] keeps the exact bytes (1 byte, or 2 ASCII digit bytes), because the byte encoding is not
 * verified yet; [error] decodes them with the current [ErrorCodes] rules on every access.
 */
data class ErrorEntry(
    val source: Source,
    val seenAtMs: Long,
    val odoKm: Double?,
    val position: GeoPoint?,
    val rawHex: String,
) {
    enum class Source { LIVE, HISTORY }

    val raw: ByteArray get() = rawHex.unhex()
    val error: ErrorCodes.DriveError get() = ErrorLogic.decode(raw)
}

/** Pure error-log logic: CSV codec, register splitting, de-duplication (unit-tested). */
object ErrorLogic {
    const val HEADER = "source,seenAtMs,odoKm,lat,lon,raw"

    fun line(e: ErrorEntry) = String.format(
        Locale.ROOT, "%s,%d,%s,%s,%s,%s", e.source.name, e.seenAtMs,
        e.odoKm?.let { "%.2f".format(Locale.ROOT, it) } ?: "",
        e.position?.let { "%.6f".format(Locale.ROOT, it.lat) } ?: "",
        e.position?.let { "%.6f".format(Locale.ROOT, it.lon) } ?: "",
        e.rawHex,
    )

    /** Oldest first, as stored; malformed lines are skipped. */
    fun parse(text: String): List<ErrorEntry> = text.lineSequence().mapNotNull { l ->
        val f = l.split(',')
        if (f.size != 6 || l == HEADER) return@mapNotNull null
        runCatching {
            val lat = f[3].toDoubleOrNull(); val lon = f[4].toDoubleOrNull()
            ErrorEntry(
                ErrorEntry.Source.valueOf(f[0]), f[1].toLong(), f[2].toDoubleOrNull(),
                if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                f[5].also { require(it.isNotEmpty() && it.length % 2 == 0) },
            )
        }.getOrNull()
    }.toList()

    /** Raw bytes -> code: two bytes are an ASCII digit pair, one byte is BCD (as [ErrorCodes] assumes). */
    fun decode(raw: ByteArray): ErrorCodes.DriveError = when (raw.size) {
        2 -> ErrorCodes.decodeHistory(raw, ErrorCodes.Encoding.ASCII).firstOrNull()
        else -> ErrorCodes.decodeHistory(raw.take(1).toByteArray(), ErrorCodes.Encoding.BCD).firstOrNull()
    } ?: ErrorCodes.DriveError(raw.firstOrNull()?.toInt()?.and(0xFF) ?: 0, null)

    /** The error register's entries ([ErrorCodes.historyEntries], AUTO) as stored in the log: hex of their raw bytes. */
    fun registerEntries(reg: ByteArray): List<String> =
        ErrorCodes.historyEntries(reg).map { b -> b.joinToString("") { hexByte(it) } }

    /**
     * Entries of the register read now that are new. [previous] = the register at the last read (null = never
     * read: everything is new). Errors logged LIVE since then ([liveSince]) are expected to appear in the
     * register too, so they are not logged a second time. Multiset difference: works whether the display
     * appends, prepends or drops old entries.
     */
    fun newHistoryEntries(previous: ByteArray?, liveSince: List<String>, now: ByteArray): List<String> {
        val current = registerEntries(now)
        if (previous == null) return current
        val expected = HashMap<String, Int>()
        (registerEntries(previous) + liveSince).forEach { expected.merge(it, 1, Int::plus) }
        return current.filter { e ->
            val n = expected[e] ?: 0
            if (n > 0) { expected[e] = n - 1; false } else true
        }
    }
}
