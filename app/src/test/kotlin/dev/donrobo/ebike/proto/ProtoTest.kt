package dev.donrobo.ebike.proto

import org.junit.Assert.*
import org.junit.Test

/**
 * Protocol vectors, incl. two BLE sessions logged from a real display (`src/test/resources/ble-session/`;
 * relative timestamps, the display serial number replaced and its frame checksum recomputed).
 */
class ProtoTest {
    private fun hx(s: String) = s.replace(" ", "").unhex()

    private fun sessionLog(name: String): List<String> =
        checkNotNull(javaClass.getResource("/ble-session/$name")) { "fixture $name" }.readText().lines()

    private fun logLines(kind: String): List<ByteArray> {
        return listOf("before.log", "after.log").flatMap { sessionLog(it) }.mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 3 || p[1] != kind) null
            else hx(p.drop(2).takeWhile { it.matches(Regex("[0-9a-f]{2}")) }.joinToString(""))
        }
    }

    @Test fun loggedFramesRoundTrip() {
        val frames = logLines("TX") + logLines("RX")
        assertTrue(frames.size > 500)
        for (raw in frames) {
            val f = Frame.parse(raw)
            assertTrue(f.checksumOk)
            assertArrayEquals(raw, f.toBytes())
        }
    }

    @Test fun streamingParserReproducesRxFrames() {
        val p = FrameParser()
        val got = logLines("RX-raw").flatMap { p.feed(it) }.map { it.toBytes().hex() }
        assertEquals(logLines("RX").map { it.hex() }, got)
    }

    @Test fun parserHandlesByteByByteAndGarbage() {
        val a = buildFrame(0x10, Op.READ, 62, byteArrayOf(4))
        val b = buildFrame(0xA5, Op.READ, 224, byteArrayOf(1))
        val p = FrameParser()
        val out = (byteArrayOf(0, 0x55) + a + byteArrayOf(-1) + b).flatMap { p.feed(byteArrayOf(it)) }
        assertEquals(listOf(a.hex(), b.hex()), out.map { it.toBytes().hex() })
    }

    @Test fun readRequestAndChecksum() {
        assertArrayEquals(hx("55 aa 01 11 10 01 00 04 d8 ff"), buildFrame(Dev.DISPLAY, Op.READ, 0, byteArrayOf(4)))
        assertArrayEquals(hx("bd ff"), checksum(hx("01 10 11 20 00 00")))
    }

    @Test fun authResponsesFromLog() {
        // TX/RX are separate lists here, so pair each auth TX with the latest challenge by replaying both logs in order
        var challenge: ByteArray? = null
        var checked = 0
        for (name in listOf("before.log", "after.log")) for (line in sessionLog(name)) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 3 || p[1] !in setOf("TX", "RX")) continue
            val f = Frame.parse(hx(p.drop(2).takeWhile { it.matches(Regex("[0-9a-f]{2}")) }.joinToString("")))
            if (p[1] == "RX" && f.op == Op.READ_RESP && f.src == Dev.DISPLAY && f.reg == 0) challenge = f.data
            else if (p[1] == "TX" && f.op == Op.AUTH) { assertArrayEquals(authResponse(challenge!!), f.data); checked++ }
        }
        assertTrue(checked >= 2)
    }

    @Test fun bikeReports() {
        val reps = logLines("RX").map { Frame.parse(it) }.filter { it.op == Op.REPORT && it.reg == 1 }.map { BikeReport.decode(it.data) }
        assertTrue(reps.size > 50)
        for (r in reps) {
            assertEquals(1, r.gear); assertEquals(5, r.gears)
            assertTrue(r.batteryPct in 68..74)
            assertEquals(0.0, r.speedKmh, 0.0); assertEquals(0.70, r.odoKm, 1e-9); assertEquals(0.70, r.tripKm, 1e-9)
            assertEquals(0, r.errorRaw); assertTrue(r.unlocked)
        }
    }

    @Test fun navSession() {
        val s = NavSession()
        assertArrayEquals(hx("55 AA 02 11 F1 03 00 01 01 F6 FE".lowercase()), s.start())
        assertArrayEquals(
            hx("55 AA 12 11 F1 03 00 02 02 78 00 00 03 FA 00 00 02 00 00 00 00 DC 05 00 00 8C FC".lowercase()),
            s.update(NavUpdate(120, Icon.RIGHT, 250, Icon.LEFT, 0, Icon.UNKNOWN, 1500)),
        )
        assertArrayEquals(
            hx("55 AA 12 11 F1 03 00 03 02 00 00 00 0A 00 00 00 00 00 00 00 00 00 00 00 00 D9 FE".lowercase()),
            s.update(NavUpdate(0, Icon.ARRIVED)),
        )
        assertArrayEquals(hx("55 AA 02 11 F1 03 00 04 03 F1 FE".lowercase()), s.end())
        assertFalse(s.active)
        // the sequence byte wraps during a long navigation without ending the session
        s.start()
        repeat(300) { s.update(NavUpdate(100, Icon.LEFT)) }
        assertTrue(s.active)
    }

    @Test fun oneHexFormat() {
        val b = byteArrayOf(0x55, 0xAA.toByte(), 0, 0x0F)
        assertEquals("55 aa 00 0f", b.hex())
        assertEquals("55aa000f", b.hex(""))
        assertArrayEquals(b, "55aa000f".unhex())
        assertEquals("ff", hexByte(-1))
        assertNull("abc".unhexOrNull())
        assertNull("zz".unhexOrNull())
    }
}
