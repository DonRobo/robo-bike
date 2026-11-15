package dev.donrobo.ebike.sim

import dev.donrobo.ebike.clock.ClockSync
import dev.donrobo.ebike.clock.RegisterIo
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.Frame
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.MeterConfig
import dev.donrobo.ebike.proto.NavSession
import dev.donrobo.ebike.proto.NavUpdate
import dev.donrobo.ebike.proto.Op
import dev.donrobo.ebike.proto.PHONE
import dev.donrobo.ebike.proto.authResponse
import dev.donrobo.ebike.proto.buildFrame
import dev.donrobo.ebike.proto.cString
import dev.donrobo.ebike.proto.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The fake display answers with real frames that the app's own decoders read. */
class FakeDisplayTest {
    private val d = FakeDisplay(Random(1))

    private fun one(bytes: ByteArray): Frame? = d.receive(bytes).map(Frame::parse).also { assertTrue(it.size <= 1) }.firstOrNull()
    private fun read(target: Int, reg: Int, len: Int) = one(buildFrame(target, Op.READ, reg, byteArrayOf(len.toByte())))
    private fun write(target: Int, reg: Int, data: ByteArray) = one(buildFrame(target, Op.WRITE, reg, data))

    @Test fun report1DecodesWithTheAppDecoder() {
        d.speedKmh = 18.37; d.batteryPct = 64; d.gear = 3; d.gears = 5; d.lightOn = true; d.tripKm = 12.34; d.odoKm = 1234.56
        val f = Frame.parse(d.report1Frame())
        assertTrue(f.checksumOk)
        assertEquals(Dev.DISPLAY, f.src); assertEquals(PHONE, f.dst); assertEquals(Op.REPORT, f.op); assertEquals(1, f.reg)
        assertEquals(21, f.data.size) // BikeGo accepts exactly 21 bytes
        val r = BikeReport.decode(f.data)
        assertEquals(BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 64, speedKmh = 18.37, tripKm = 12.34, odoKm = 1234.56), r)
        val nine = Frame.parse(d.report9Frame())
        assertEquals(9, nine.reg); assertEquals(16, nine.data.size)
    }

    @Test fun observedIdleReportIsReproduced() {
        // Real report at power-on (PROTOCOL.md): 00 00 00 01 00 01 05 48 00 00 00 46 00 00 00 46 00 00 00 00 00
        d.batteryPct = 72; d.gear = 1; d.gears = 5; d.tripKm = 0.70; d.odoKm = 0.70
        assertEquals("00 00 00 01 00 01 05 48 00 00 00 46 00 00 00 46 00 00 00 00 00", d.report1().hex())
    }

    @Test fun authenticationNeedsTheRightResponse() {
        val c1 = read(Dev.DISPLAY, 0, 4)!!.data
        val c2 = read(Dev.DISPLAY, 0, 4)!!.data
        assertEquals(4, c2.size)
        assertFalse(c1.contentEquals(c2)) // a new challenge per read
        val bad = one(buildFrame(Dev.DISPLAY, Op.AUTH, 0, authResponse(c1)))!!
        assertEquals(Op.AUTH, bad.op); assertArrayEquals(byteArrayOf(1), bad.data)
        val ok = one(buildFrame(Dev.DISPLAY, Op.AUTH, 0, authResponse(c2)))!!
        assertArrayEquals(byteArrayOf(0), ok.data)
        assertTrue(d.authenticated)
    }

    @Test fun deviceInfoStringsAsOnTheRealDisplay() {
        val hw = read(Dev.METER, 0, 24)!!
        assertEquals(Dev.METER, hw.src) // the answer comes from the target, as Ekd01Link matches it
        assertEquals("B02NC_1.01", cString(hw.data))
        assertEquals("EKD01_CAN_BF_APO_N04", cString(read(Dev.METER, 24, 24)!!.data))
        assertEquals(16, read(Dev.METER, 72, 16)!!.data.size)
        assertArrayEquals(ByteArray(24), read(Dev.CONTROLLER, 24, 24)!!.data) // zeros on the real bike
        assertEquals("EKD01-BF ", String(read(Dev.METER, 136, 24)!!.data, Charsets.US_ASCII).substringBefore('\u0000'))
    }

    @Test fun errorRegisterAnswersWithMoreBytesThanAsked() {
        d.raiseError(ErrorHistory.bcd(21), intoHistory = true)
        val a = read(ErrorCodes.HISTORY_TARGET, ErrorCodes.HISTORY_REG, ErrorCodes.HISTORY_LEN)!!
        assertEquals(32, a.data.size)
        assertEquals("EKD01-BF ", String(a.data.copyOfRange(16, 25), Charsets.US_ASCII)) // reg 136 overlaps
        assertEquals(listOf(21), ErrorCodes.decodeHistory(a.data).map { it.code })
        assertEquals(21, ErrorCodes.currentFromReport(d.report1())!!.code)
        d.errorRaw = 0
        assertNull(ErrorCodes.currentFromReport(d.report1()))
        assertEquals(listOf(21), ErrorCodes.decodeHistory(d.history).map { it.code }) // history stays
    }

    @Test fun historyFillsSlotsInOrderAndDropsTheOldest() {
        var h = ByteArray(16)
        for (c in listOf(21, 30, 8)) h = ErrorHistory.add(h, ErrorHistory.bcd(c))
        assertEquals(listOf(21, 30, 8), ErrorCodes.decodeHistory(h).map { it.code })
        repeat(13) { h = ErrorHistory.add(h, ErrorHistory.bcd(10)) }
        h = ErrorHistory.add(h, ErrorHistory.bcd(44))
        assertEquals(30, ErrorCodes.decodeHistory(h).first().code)
        assertEquals(44, ErrorCodes.decodeHistory(h).last().code)
    }

    @Test fun featureBlockDecodesAndCanBeWithheld() {
        val cfg = MeterConfig.decode(read(Dev.NAV, 1, MeterConfig.SIZE)!!.data)!!
        assertTrue(cfg.has(DisplayFeature.LAMP_SWITCH)); assertTrue(cfg.has(DisplayFeature.ERROR_CODE)); assertTrue(cfg.has(DisplayFeature.DATE_TIME))
        assertFalse(cfg.has(DisplayFeature.TIRE_PRESSURE)); assertFalse(cfg.has(DisplayFeature.UPGRADE_CONTROLS))
        assertEquals(5, cfg.maxBrightness)
        d.featureBlock = null
        assertNull(read(Dev.NAV, 1, MeterConfig.SIZE))
    }

    @Test fun registersTheRealDisplayNeverAnsweredStaySilent() {
        assertNull(read(Dev.DISPLAY, 69, 10))
        assertNull(read(Dev.CONTROLLER, 136, 24))
        assertNull(read(0xA6, 4, 24))
        assertNull(read(0xA8, 0, 1))
        assertNull(read(Dev.NAV, 2, 1))
        assertNull(read(Dev.METER, FakeDisplay.LAMP_REG, 2)) // write-only
        assertNull(write(Dev.CONTROLLER, 160, byteArrayOf(1, 0))) // the app never writes 0xA3
    }

    @Test fun lampWriteIsAcknowledgedAndReflectedInReports() {
        assertFalse(BikeReport.decode(d.report1()).lightOn)
        val ack = write(Dev.METER, FakeDisplay.LAMP_REG, byteArrayOf(1, 0))!!
        assertEquals(Op.WRITE_ACK, ack.op); assertEquals(Dev.METER, ack.src); assertArrayEquals(byteArrayOf(0), ack.data)
        assertTrue(BikeReport.decode(d.report1()).lightOn)
        write(Dev.METER, FakeDisplay.LAMP_REG, byteArrayOf(0, 0))
        assertFalse(BikeReport.decode(d.report1()).lightOn)
        assertArrayEquals(byteArrayOf(1), write(Dev.METER, FakeDisplay.LAMP_REG, byteArrayOf(1))!!.data) // wrong length: rejected
    }

    @Test fun lockStateIsReportedAndWritable() {
        d.unlocked = false
        assertFalse(BikeReport.decode(d.report1()).unlocked)
        assertArrayEquals(byteArrayOf(0), read(Dev.DISPLAY, 14, 1)!!.data)
        write(Dev.DISPLAY, 14, byteArrayOf(1))
        assertTrue(BikeReport.decode(d.report1()).unlocked)
    }

    @Test fun clockSyncReadsBackWhatWasWritten() = runBlocking {
        val io = object : RegisterIo {
            override suspend fun read(target: Int, reg: Int, len: Int) = this@FakeDisplayTest.read(target, reg, len)!!.data
            override suspend fun write(target: Int, reg: Int, data: ByteArray) {
                check(this@FakeDisplayTest.write(target, reg, data)!!.data[0].toInt() == 0)
            }
        }
        val log = mutableListOf<String>()
        val check = ClockSync.sync(io, 1_794_400_000_000, 3600, bigEndian = false, automatic = true, log = log::add)
        assertTrue(log.joinToString("\n"), check.accepted)
        assertArrayEquals(ClockSync.encodeOffset(3600), d.register(Dev.DISPLAY, ClockSync.REG_OFFSET))
        // 0xA1:59 mirrors the clock registers
        assertArrayEquals(ClockSync.encode(1_794_400_000, false), read(0xA1, 59, 64)!!.data.copyOfRange(3, 7))
    }

    @Test fun navigationFramesAreRecorded() {
        val s = NavSession()
        assertTrue(d.receive(s.start()).isEmpty()) // fire and forget, no answer
        assertTrue(d.nav.active)
        val u = NavUpdate(distToTurnM = 120, icon = Icon.RIGHT, distTurnToNextM = 250, nextIcon = Icon.LEFT, remainingM = 1500)
        d.receive(s.update(u))
        assertEquals(u, d.nav.update)
        d.receive(s.end())
        assertFalse(d.nav.active)
        assertEquals(3, d.nav.frames)
    }

    @Test fun framesSplitAcrossWritesAreReassembled() {
        val f = buildFrame(Dev.METER, Op.READ, 0, byteArrayOf(24))
        assertTrue(d.receive(f.copyOfRange(0, 4)).isEmpty())
        assertTrue(d.receive(f.copyOfRange(4, 7)).isEmpty())
        val out = d.receive(f.copyOfRange(7, f.size))
        assertEquals(1, out.size)
        assertNotNull(Frame.parse(out[0]).takeIf { it.checksumOk })
    }
}
