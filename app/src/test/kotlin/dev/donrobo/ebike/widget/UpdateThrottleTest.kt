package dev.donrobo.ebike.widget

import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateThrottleTest {
    private fun g(battery: Int = 75, light: Boolean = true, link: ConnectionStatus = ConnectionStatus.CONNECTED, range: String? = "64") = BikeGlance(
        "Stadtrad", link, battery, range?.let { Measure(it, UnitLabel.KM) }, light, null, light = if (link != ConnectionStatus.CONNECTED) LightControl.NOT_CONNECTED else if (light) LightControl.ON else LightControl.OFF, locked = false,
    )

    private val t = UpdateThrottle(60_000, ::glanceUrgent)

    @Test fun firstValueAtOnce() = assertEquals(g(), t.offer(g(), 1_000))

    @Test fun sameValueNotRedrawn() {
        t.offer(g(), 1_000)
        assertNull(t.offer(g(), 2_000))
        assertNull(t.nextDueMs)
    }

    @Test fun batteryChangeWaitsForInterval() {
        t.offer(g(), 1_000)
        assertNull(t.offer(g(battery = 74), 2_000))
        assertNull(t.offer(g(battery = 73, range = "63"), 30_000)) // newest one is kept
        assertEquals(61_000L, t.nextDueMs)
        assertNull(t.poll(60_999))
        assertEquals(g(battery = 73, range = "63"), t.poll(61_000))
        assertNull(t.nextDueMs)
    }

    @Test fun twiceASecondReportsGiveOneRedrawPerMinute() {
        var redraws = 0
        var now = 0L
        repeat(240) { i -> // 2 minutes of reports, battery dropping one % every 10 s
            now = i * 500L
            if (t.offer(g(battery = 80 - i / 20), now) != null) redraws++
            t.nextDueMs?.let { if (now >= it && t.poll(now) != null) redraws++ }
        }
        assertEquals(2, redraws) // the first value at 0 s, then the newest at 60 s; the 120 s one is still held
        assertEquals(120_000L, t.nextDueMs)
    }

    @Test fun lightAndConnectionGoOutAtOnce() {
        t.offer(g(), 1_000)
        assertEquals(g(light = false), t.offer(g(light = false), 1_500))
        assertEquals(g(light = false, link = ConnectionStatus.OFF), t.offer(g(light = false, link = ConnectionStatus.OFF), 2_000))
    }

    @Test fun urgentChangeAlsoCarriesHeldValues() {
        t.offer(g(), 1_000)
        t.offer(g(battery = 74), 2_000)
        assertEquals(g(battery = 74, light = false), t.offer(g(battery = 74, light = false), 3_000))
        assertNull(t.nextDueMs)
    }

    @Test fun changeBackToShownDropsHeld() {
        t.offer(g(), 1_000)
        t.offer(g(battery = 74), 2_000)
        assertNull(t.offer(g(), 3_000))
        assertNull(t.nextDueMs)
    }
}
