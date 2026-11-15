package dev.donrobo.ebike.ui.start

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.Live
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.settings.Bike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartStateTest {
    private val bike = Bike("b1", "Stadtrad", "00:11:22:33:44:55")
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 0.0, tripKm = 1.0, odoKm = 10.0)

    private fun state(connection: ConnectionStatus, live: Live?, lastKnown: LastKnown?) = startUiState(
        bike, listOf(bike), connection, null, Snapshot.of(bike.id, live, lastKnown), LightStatus(), null, null, null, null, true, null,
    )

    @Test fun lampStateOnlyWhileLive() {
        assertTrue(state(ConnectionStatus.CONNECTED, Live(bike.id, report), null).lightOn)
        assertFalse(state(ConnectionStatus.CONNECTED, Live(bike.id, report.copy(lightOn = false)), null).lightOn)
        // A last known "on" is stale: not connected shows the neutral off presentation.
        assertFalse(state(ConnectionStatus.WAITING, null, LastKnown(1_000, report)).lightOn)
        assertFalse(state(ConnectionStatus.OFF, null, LastKnown(1_000, report)).lightOn)
        // A2: another bike is connected; its live lamp is not this bike's.
        assertFalse(state(ConnectionStatus.OFF, Live("other", report), LastKnown(1_000, report)).lightOn)
    }

    /** A2: active bike != session bike. Start shows the active bike's own last known state, no banner, no live values. */
    @Test fun activeBikeIsNotTheSessionBike() {
        val s = state(ConnectionStatus.OFF, Live("other", report.copy(batteryPct = 20)), LastKnown(1_000, report))
        assertFalse(s.live)
        assertEquals(75, s.report?.batteryPct)
        assertEquals(1_000L, s.lastKnownAtMs)
        val live = state(ConnectionStatus.CONNECTED, Live(bike.id, report.copy(batteryPct = 20)), LastKnown(1_000, report))
        assertTrue(live.live)
        assertEquals(20, live.report?.batteryPct)
        assertNull(live.lastKnownAtMs)
    }
}
