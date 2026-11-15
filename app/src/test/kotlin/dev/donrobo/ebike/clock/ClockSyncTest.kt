package dev.donrobo.ebike.clock

import dev.donrobo.ebike.proto.Dev
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {
    // 0x5FEE31E9 = 2020-12-31, the value read back from the device (little-endian on the wire)
    @Test fun littleEndianByDefault() =
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x31, 0xEE.toByte(), 0x5F), ClockSync.encode(0x5FEE31E9, false))

    @Test fun bigEndianFlag() =
        assertArrayEquals(byteArrayOf(0x5F, 0xEE.toByte(), 0x31, 0xE9.toByte()), ClockSync.encode(0x5FEE31E9, true))

    @Test fun decodeRoundTripsAndToleratesLongAnswers() {
        for (be in listOf(false, true)) assertEquals(1_763_000_000L, ClockSync.decode(ClockSync.encode(1_763_000_000L, be) + ByteArray(4), be))
        // factory value only makes sense little-endian
        assertEquals(0x5FEE31E9L, ClockSync.decode(byteArrayOf(0xE9.toByte(), 0x31, 0xEE.toByte(), 0x5F), false))
        assertNull(ClockSync.decode(byteArrayOf(1, 2), false))
    }

    @Test fun offsetIsSignedLittleEndian() {
        assertArrayEquals(byteArrayOf(0x40, 0x38, 0, 0), ClockSync.encodeOffset(14_400)) // the display's default +4 h
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0xF1.toByte(), 0xFF.toByte(), 0xFF.toByte()), ClockSync.encodeOffset(-3600))
    }

    /** Register file that stores writes; [tick] seconds pass between write and read-back. */
    private class FakeDisplay(val tick: Long = 0, val ignoreWrites: Boolean = false) : RegisterIo {
        val regs = mutableMapOf(62 to byteArrayOf(0xE9.toByte(), 0x31, 0xEE.toByte(), 0x5F))
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        override suspend fun read(target: Int, reg: Int, len: Int): ByteArray {
            val v = regs[reg] ?: throw IllegalStateException("no answer")
            return if (reg == 62 && tick > 0) ClockSync.encode(ClockSync.decode(v, false)!! + tick, false) else v
        }
        override suspend fun write(target: Int, reg: Int, data: ByteArray) {
            assertEquals(Dev.DISPLAY, target)
            writes += reg to data
            if (!ignoreWrites) regs[reg] = data
        }
    }

    @Test fun writesUtcAndOffsetButNotLocal() = runBlocking {
        val d = FakeDisplay(tick = 1)
        val log = mutableListOf<String>()
        val c = ClockSync.sync(d, 1_763_000_000_500, 3600, bigEndian = false, automatic = true, log = { log += it })
        assertEquals(listOf(62, 66), d.writes.map { it.first })
        assertArrayEquals(ClockSync.encode(1_763_000_000, false), d.writes[0].second)
        assertArrayEquals(ClockSync.encodeOffset(3600), d.writes[1].second)
        assertEquals(1_763_000_001L, c.readBackS)
        assertTrue(c.accepted)
        assertTrue(log.any { "accepted" in it })
    }

    @Test fun rejectedWhenTheDisplayKeepsItsOldValue() = runBlocking {
        val c = ClockSync.sync(FakeDisplay(ignoreWrites = true), 1_763_000_000_000, 0, bigEndian = false, automatic = false, log = {})
        assertEquals(0x5FEE31E9L, c.readBackS)
        assertFalse(c.accepted)
        assertEquals("e9 31 ee 5f", c.readBackHex)
    }

    @Test fun bigEndianIsReadBackInTheSameOrder() = runBlocking {
        val c = ClockSync.sync(FakeDisplay(), 1_763_000_000_000, 0, bigEndian = true, automatic = false, log = {})
        assertTrue(c.accepted)
    }
}
