package dev.donrobo.ebike.sim

import dev.donrobo.ebike.ble.Ekd01Exception
import dev.donrobo.ebike.ble.Ekd01Link
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.cString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The production protocol layer (Ekd01Link: auth, framing, response matching) over the simulator's transport. */
class SimLinkTest {
    private val reports = CopyOnWriteArrayList<BikeReport>()

    @Before fun setUp() {
        Simulator.connectDelayMs = 0
        Simulator.offConnectTimeoutMs = 300
        Simulator.play(Scenario.Standstill, 1.0)
        Simulator.edit { d, _ -> d.lightOn = false; d.errorRaw = 0; d.history = ByteArray(16) }
    }

    @After fun tearDown() { Simulator.powerOff() }

    private fun link() = Ekd01Link(Simulator.transport()) { reg, data -> if (reg == 1) reports += BikeReport.decode(data) }

    private suspend fun awaitReport(what: String, cond: (BikeReport) -> Boolean) {
        val start = reports.size
        val ok = withTimeoutOrNull(5_000) { while (reports.drop(start).none(cond)) delay(50); true }
        assertTrue(what, ok == true)
    }

    @Test fun connectReadWriteDisconnect() = runBlocking {
        Simulator.powerOn()
        val l = link()
        l.connect() // includes the real AES challenge/response
        assertEquals("EKD01_CAN_BF_APO_N04", cString(l.read(Dev.METER, 24, 24)))
        // The display answers 0xA5:120 with 32 bytes; Ekd01Link cuts it to the 16 asked for.
        assertEquals(16, l.read(ErrorCodes.HISTORY_TARGET, ErrorCodes.HISTORY_REG, ErrorCodes.HISTORY_LEN).size)
        // Diagnose wants the whole answer: readFull returns it uncut.
        assertEquals(32, l.readFull(ErrorCodes.HISTORY_TARGET, ErrorCodes.HISTORY_REG, ErrorCodes.HISTORY_LEN).size)
        awaitReport("reports arrive") { true }
        l.write(Dev.METER, FakeDisplay.LAMP_REG, byteArrayOf(1, 0))
        awaitReport("light bit set") { it.lightOn }
        assertTrue(Simulator.dropLink())
        withTimeout(2_000) { l.disconnected.await() }
        try {
            l.read(Dev.METER, 0, 24)
            fail("read after link loss")
        } catch (_: Ekd01Exception) {
        }
    }

    @Test fun concurrentRequestsToTheSameRegisterBothSucceed() = runBlocking {
        Simulator.powerOn()
        val l = link()
        l.connect()
        // e.g. the Diagnose screen reading the error register while the session's setup reads it too
        val reads = List(3) { async { l.read(Dev.METER, 24, 24) } }
        reads.awaitAll().forEach { assertEquals("EKD01_CAN_BF_APO_N04", cString(it)) }
        val mixed = listOf(
            async { cString(l.read(Dev.METER, 24, 24)) },
            async { cString(l.read(Dev.METER, 0, 24)) },
            async { l.write(Dev.METER, FakeDisplay.LAMP_REG, byteArrayOf(1, 0)); "written" },
        )
        assertEquals(listOf("EKD01_CAN_BF_APO_N04", "B02NC_1.01", "written"), mixed.awaitAll())
        l.close()
    }

    @Test fun noAnswerIsATimeoutLikeOnTheRealDisplay() = runBlocking {
        Simulator.powerOn()
        val l = link()
        l.connect()
        try {
            l.read(0xA6, 4, 24)
            fail("0xA6 has no module on this bike")
        } catch (e: Ekd01Exception) {
            assertTrue(e.message!!, e.message!!.startsWith("no response"))
        }
        l.close()
    }

    @Test fun switchedOffDisplayTimesOut() = runBlocking {
        try {
            link().connect()
            fail("connected to a switched-off display")
        } catch (e: Ekd01Exception) {
            assertEquals("connect timeout", e.message)
        }
    }
}
