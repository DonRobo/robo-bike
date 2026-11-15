package dev.donrobo.ebike.errors

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.proto.unhex
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorLogTest {
    @Test fun registerHistoryDedup() {
        val empty = ByteArray(32)
        val reg = ByteArray(32).also { it[0] = 0x21; it[1] = 0x30; it[16] = 'E'.code.toByte() } // bytes 16.. belong to reg 136
        assertEquals(listOf("21", "30"), ErrorLogic.registerEntries(reg))
        // first read ever: all entries are new; nothing new on the next identical read
        assertEquals(listOf("21", "30"), ErrorLogic.newHistoryEntries(null, emptyList(), reg))
        assertEquals(emptyList<String>(), ErrorLogic.newHistoryEntries(reg, emptyList(), reg))
        assertEquals(emptyList<String>(), ErrorLogic.newHistoryEntries(null, emptyList(), empty))
        // display prepends a new one (FIFO shift): only that one is new
        val shifted = byteArrayOf(0x12, 0x21, 0x30) + ByteArray(13)
        assertEquals(listOf("12"), ErrorLogic.newHistoryEntries(reg, emptyList(), shifted))
        // an error already logged live during the last connection is not logged again from the register
        assertEquals(emptyList<String>(), ErrorLogic.newHistoryEntries(reg, listOf("12"), shifted))
        // ASCII digit pairs ("21", "07")
        assertEquals(listOf("3231", "3037"), ErrorLogic.registerEntries("2107".toByteArray() + ByteArray(12)))
        assertEquals(21, ErrorLogic.decode("3231".unhex()).code)
        assertEquals(21, ErrorLogic.decode(byteArrayOf(0x21)).code)
    }

    @Test fun errorCsvRoundTrip() {
        val e = listOf(
            ErrorEntry(ErrorEntry.Source.LIVE, 1_000, 1284.3, GeoPoint(48.2016, 16.3663), "21"),
            ErrorEntry(ErrorEntry.Source.HISTORY, 2_000, null, null, "3231"),
        )
        val text = ErrorLogic.HEADER + "\n" + e.joinToString("") { ErrorLogic.line(it) + "\n" } + "LIVE,3000,"
        assertEquals(e, ErrorLogic.parse(text))
        assertEquals(21, e[1].error.code)
    }
}
