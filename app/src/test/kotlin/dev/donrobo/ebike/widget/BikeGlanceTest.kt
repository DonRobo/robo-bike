package dev.donrobo.ebike.widget

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.Live
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BikeGlanceTest {
    private val bike = Bike("b1", "Stadtrad", null)
    private val fmt = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val live = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 20.0, tripKm = 1.0, odoKm = 100.0)
    private val stored = LastKnown(1_000L, live.copy(batteryPct = 60, lightOn = false, speedKmh = 0.0))
    private val range = RangeEstimate(63.6, 86.0, 1.2)

    private fun glance(
        status: ConnectionStatus, live: Live?, lastKnown: LastKnown?, range: RangeEstimate? = null, fmt: UnitFormatter = this.fmt,
    ) = bikeGlance(bike, status, Snapshot.of(bike.id, live, lastKnown), range, fmt)

    @Test fun liveWhileConnected() {
        val g = glance(ConnectionStatus.CONNECTED, Live(bike.id, live), stored, range)
        assertEquals(ConnectionStatus.CONNECTED, g.link)
        assertEquals(75, g.batteryPct)
        assertEquals(Measure("64", UnitLabel.KM), g.range)
        assertEquals(true, g.lightOn)
        assertNull(g.asOfMs)
        assertTrue(g.live)
        assertTrue(g.light.switchable)
        assertFalse(g.locked)
    }

    @Test fun lastKnownWhenNotConnected() {
        val g = glance(ConnectionStatus.OFF, null, stored)
        assertEquals(ConnectionStatus.OFF, g.link)
        assertEquals(60, g.batteryPct)
        assertEquals(false, g.lightOn)
        assertEquals(1_000L, g.asOfMs)
        assertFalse(g.live)
        assertFalse(g.light.switchable)
        assertNull(g.range) // not learned yet: designed empty state
    }

    @Test fun connectingButNoReportYetShowsLastKnown() {
        val g = glance(ConnectionStatus.CONNECTING, null, stored)
        assertEquals(ConnectionStatus.CONNECTING, g.link)
        assertEquals(1_000L, g.asOfMs)
        assertFalse(g.light.switchable)
    }

    /** A2: the active bike is not the connected one: its own last known state, never the other bike's live values. */
    @Test fun otherBikesLiveReportIsNotShown() {
        val g = glance(ConnectionStatus.OFF, Live("other", live), stored)
        assertEquals(60, g.batteryPct)
        assertEquals(1_000L, g.asOfMs)
        assertFalse(g.live)
        assertFalse(g.light.switchable) // the switch would reach the other bike's lamp
        assertNull(glance(ConnectionStatus.OFF, Live("other", live), null).batteryPct)
    }

    @Test fun lockedBikeCannotSwitchLight() {
        val g = glance(ConnectionStatus.CONNECTED, Live(bike.id, live.copy(unlocked = false)), null)
        assertFalse(g.light.switchable)
        assertTrue(g.locked)
    }

    @Test fun nothingKnown() {
        val g = bikeGlance(null, ConnectionStatus.OFF, null, null, fmt)
        assertNull(g.bikeName)
        assertNull(g.batteryPct)
        assertNull(g.asOfMs)
        assertFalse(g.live)
    }

    @Test fun rangeInMiles() {
        val g = glance(ConnectionStatus.CONNECTED, Live(bike.id, live), null, range, UnitFormatter(UnitSystem.IMPERIAL, Locale.UK))
        assertEquals(Measure("40", UnitLabel.MI), g.range)
    }

    @Test fun speedAndTripChangesGiveEqualGlances() {
        val a = glance(ConnectionStatus.CONNECTED, Live(bike.id, live), null, range)
        val b = glance(ConnectionStatus.CONNECTED, Live(bike.id, live.copy(speedKmh = 25.3, tripKm = 1.2)), null, range.copy(remainingKm = 63.9))
        assertEquals(a, b)
    }
}
