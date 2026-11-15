package dev.donrobo.ebike.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlertGateTest {
    private val quiet = 30 * 60_000L

    @Test fun firstShowAlerts() {
        val (a, m) = AlertGate.onShow(null, "home", 1, 1_000, quiet)
        assertEquals(AlertAction.ALERT, a)
        assertEquals(AlertMemory("home", true, 1_000, 1), m)
    }

    @Test fun repeatedShowDoesNotReAlert() {
        var mem: AlertMemory? = null
        val actions = (0 until 100).map { i -> AlertGate.onShow(mem, "home", 1, i * 500L, quiet).also { mem = it.second }.first }
        assertEquals(AlertAction.ALERT, actions.first())
        assertEquals(listOf(AlertAction.NONE), actions.drop(1).distinct())
    }

    @Test fun changedTextUpdatesSilently() {
        val (_, m) = AlertGate.onShow(null, "home", 1, 0, quiet)
        val (a, m2) = AlertGate.onShow(m, "home", 2, 500, quiet)
        assertEquals(AlertAction.UPDATE_SILENTLY, a)
        assertEquals(2, m2!!.contentHash)
    }

    @Test fun comingBackSoonAfterResolveIsSilent() {
        val (_, m) = AlertGate.onShow(null, "21", 1, 0, quiet)
        val resolved = AlertGate.onResolve(m, 10_000)!!
        assertFalse(resolved.active)
        assertEquals(AlertAction.UPDATE_SILENTLY, AlertGate.onShow(resolved, "21", 1, 20_000, quiet).first)
    }

    @Test fun comingBackAfterQuietTimeAlertsAgain() {
        val (_, m) = AlertGate.onShow(null, "home", 1, 0, quiet)
        val resolved = AlertGate.onResolve(m, 10_000)
        assertEquals(AlertAction.ALERT, AlertGate.onShow(resolved, "home", 1, 10_000 + quiet, quiet).first)
    }

    @Test fun otherKeyIsANewEpisode() {
        val (_, m) = AlertGate.onShow(null, "ride-1", 1, 0, quiet)
        assertEquals(AlertAction.ALERT, AlertGate.onShow(m, "ride-2", 1, 1_000, quiet).first)
        val resolved = AlertGate.onResolve(m, 1_000)
        assertEquals(AlertAction.ALERT, AlertGate.onShow(resolved, "ride-2", 1, 2_000, quiet).first)
    }

    @Test fun resolveWithoutActiveEpisodeChangesNothing() {
        assertNull(AlertGate.onResolve(null, 5))
        val inactive = AlertMemory("x", false, 3, 1)
        assertEquals(inactive, AlertGate.onResolve(inactive, 99))
    }

    @Test fun memoryRoundTrip() {
        val m = AlertMemory("chain;oil", true, 1_731_600_000_000, -12345)
        assertEquals(m, AlertMemory.decode(m.encode()))
        assertNull(AlertMemory.decode("garbage"))
    }
}
