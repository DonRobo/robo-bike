package dev.donrobo.ebike.sim

import android.Manifest
import android.content.Intent
import android.os.Looper
import dev.donrobo.ebike.BikeService
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.bike.DisplayInfoStore
import dev.donrobo.ebike.clock.ClockState
import dev.donrobo.ebike.display.DisplayCapabilitiesStore
import dev.donrobo.ebike.errors.DriveErrorState
import dev.donrobo.ebike.errors.ErrorEntry
import dev.donrobo.ebike.errors.ErrorLogStore
import dev.donrobo.ebike.location.LocationSettings
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * End to end without a device: the real BikeService (connection loop, session, info/feature/clock/error reads,
 * RideRecorder, DriveErrorWatcher, navigation loop, parked position) against the simulated display, driven
 * through the same receiver the adb commands use.
 */
@RunWith(RobolectricTestRunner::class)
class SimulatedServiceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val receiver = SimControlReceiver()

    private fun sim(cmd: String, vararg extras: Pair<String, Any>) {
        val i = Intent().putExtra("cmd", cmd)
        for ((k, v) in extras) when (v) {
            is String -> i.putExtra(k, v)
            is Int -> i.putExtra(k, v)
            is Float -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            else -> error("extra type")
        }
        receiver.onReceive(app, i)
    }

    private fun await(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what\n${Repo.log.value.takeLast(40).joinToString("\n")}")
            Thread.sleep(50)
        }
    }

    @After fun tearDown() { Simulator.powerOff() }

    @Test fun connectReportsLightRideErrorNavDisconnect() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        Simulator.connectDelayMs = 0
        Simulator.offConnectTimeoutMs = 300

        // First run: no display yet -> setup; 'pair' gives the bike the simulated display without the chooser.
        assertTrue(Pairing.needsSetup(app.bikes.all()))
        sim("pair", "name" to "Sim")
        val bike = app.bikes.active()!!
        assertEquals(Simulator.MAC, bike.displayMac)
        assertFalse(Pairing.needsSetup(app.bikes.all()))

        sim("start", "scenario" to "still")
        assertEquals(BikeService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        val service = Robolectric.buildService(BikeService::class.java).create()
        var destroyed = false
        try {
            await("connected with reports") { Repo.conn.value == ConnState.CONNECTED && Repo.live.value?.bikeId == bike.id }
            assertEquals(bike.id, Repo.sessionBikeId.value)
            await("info read") { DisplayInfoStore.get(app.settings, bike.id)?.software == "EKD01_CAN_BF_APO_N04" }
            await("feature block stored") { DisplayCapabilitiesStore.get(app.settings, bike.id).known }
            assertTrue(DisplayCapabilitiesStore.get(app.settings, bike.id).supports(DisplayFeature.LAMP_SWITCH))
            await("clock synced") { ClockState.last.value?.accepted == true }
            await("controls published") { Repo.bike != null }

            runBlocking { Repo.bike!!.setLight(true) }
            await("light bit in the report") { Repo.live.value?.report?.lightOn == true }

            sim("set", "speed" to 20f)
            await("ride recording") { Repo.recording.value }
            assertEquals(20.0, Repo.live.value!!.report.speedKmh, 0.001)

            sim("error", "code" to "21")
            await("drive error in the report") { Repo.live.value?.report?.errorRaw == 0x21 }
            await("drive error episode") { DriveErrorState.current.value?.raw == 0x21 }
            await("drive error logged") { ErrorLogStore.read(app, bike.id).any { it.source == ErrorEntry.Source.LIVE } }
            // one tracker: one log line and one log entry per episode
            assertEquals(1, Repo.log.value.count { it.contains("drive error: ") })
            assertEquals(1, ErrorLogStore.read(app, bike.id).count { it.source == ErrorEntry.Source.LIVE })

            sim("nav-test", "dist" to 150, "icon" to "RIGHT")
            await("display shows the maneuver") { Simulator.edit { d, _ -> d.nav.active && d.nav.update?.distToTurnM == 150 } }
            sim("nav-test-end")
            await("navigation ended on the display") { Simulator.edit { d, _ -> !d.nav.active } }

            sim("off")
            await("disconnected") { Repo.conn.value != ConnState.CONNECTED && Repo.live.value == null }
            assertNotNull("parked position recorded", app.settings.get(LocationSettings.parked, bike.id))
            // reports are persisted off the GATT thread; the session's end writes the last one received
            await("last known state stored") { app.settings.get(LastKnown.setting, bike.id)?.report?.errorRaw == 0x21 }
            await("drop shown as lost") { Repo.linkLost.value }

            service.destroy()
            destroyed = true
            // the teardown resets everything once the service's coroutines are done; nothing stays "lost"
            await("service state reset") { !Repo.running.value && !Repo.linkLost.value && Repo.sessionBikeId.value == null }
            assertEquals(null, DriveErrorState.current.value)
        } finally {
            if (!destroyed) service.destroy()
            println(Repo.log.value.joinToString("\n")) // the app log of the run, in the test report's system-out
        }
    }
}
