package dev.donrobo.ebike.ui.diagnostics

import dev.donrobo.ebike.NavDebug
import dev.donrobo.ebike.clock.ClockCheck
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.hexByte

/** Maneuvers in the order of the artboard's chips (every icon the display knows). */
val TEST_ICONS = listOf(
    Icon.STRAIGHT, Icon.LEFT, Icon.RIGHT, Icon.SLIGHT_LEFT, Icon.SLIGHT_RIGHT,
    Icon.SHARP_LEFT, Icon.SHARP_RIGHT, Icon.UTURN, Icon.ARRIVED, Icon.UNKNOWN,
)

/** Distances of the artboard's test segments, metres. */
val TEST_DISTANCES_M = listOf(50, 200, 1200)

/** Remaining route sent with a test maneuver (the display needs some value; not shown by it as far as known). */
const val TEST_REMAINING_M = 2500L

/** Result of reading the raw error memory: the full answer, or why it failed. */
data class ErrorMemoryRead(val bytes: List<Int>?, val error: String?)

/** Everything the Diagnose screen shows; rendered by [DiagnosticsContent]. */
data class DiagnosticsUiState(
    val connected: Boolean,
    val testIcon: Icon,
    val testDistM: Int,
    /** The test maneuver currently queued for the display. */
    val testNav: NavState?,
    val navDebug: NavDebug?,
    val bigEndian: Boolean,
    val clock: ClockCheck?,
    /** Byte 0 of the live report; null when there is no live report. */
    val errorRaw: Int?,
    val errorMemory: ErrorMemoryRead?,
    val logTail: List<String>,
)

/** "0x21 · BCD 21 · bin 33": the byte and both single-byte readings, for comparing with the display. */
fun errorByteReadings(raw: Int): String {
    val bcd = ErrorCodes.currentFromReport(byteArrayOf(raw.toByte()), ErrorCodes.Encoding.BCD)?.code
    return "0x${hexByte(raw)} · BCD ${bcd ?: "–"} · bin $raw"
}

/** Hex dump, [perLine] bytes per line. */
fun hexLines(bytes: List<Int>, perLine: Int = 8): List<String> = bytes.chunked(perLine).map { l -> l.joinToString(" ") { hexByte(it) } }

/** The error memory decoded like [ErrorCodes.decodeHistory] (first 16 bytes); codes as text, invalid ones as raw hex. */
fun decodedHistory(bytes: List<Int>): List<String> =
    ErrorCodes.decodeHistory(ByteArray(bytes.size) { bytes[it].toByte() }).map { e -> e.code?.let { "%02d".format(it) } ?: "0x${hexByte(e.raw)}?" }
