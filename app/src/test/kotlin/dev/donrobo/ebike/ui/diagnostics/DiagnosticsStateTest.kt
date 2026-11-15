package dev.donrobo.ebike.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsStateTest {
    @Test fun errorBytes() {
        assertEquals("0x21 · BCD 21 · bin 33", errorByteReadings(0x21))
        assertEquals("0x1a · BCD – · bin 26", errorByteReadings(0x1A))
        // 32-byte answer as the display sends it: only the first 16 bytes are the error block
        val answer = List(16) { 0 } + "EKD01-BF ".map { it.code } + List(7) { 0 }
        assertEquals(emptyList<String>(), decodedHistory(answer))
        assertEquals(4, hexLines(answer).size)
        assertEquals(listOf("21", "04"), decodedHistory(listOf(0x21, 0x04) + List(30) { 0 }))
    }
}
