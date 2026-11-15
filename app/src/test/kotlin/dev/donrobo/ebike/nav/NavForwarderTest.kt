package dev.donrobo.ebike.nav

import dev.donrobo.ebike.nav.NavForwarder.Kind.END
import dev.donrobo.ebike.nav.NavForwarder.Kind.START
import dev.donrobo.ebike.nav.NavForwarder.Kind.UPDATE
import dev.donrobo.ebike.proto.Frame
import dev.donrobo.ebike.proto.Icon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavForwarderTest {
    private val f = NavForwarder(graceMs = 10_000)
    private var now = 0L

    private fun maps(m: Int, icon: Icon = Icon.LEFT) = NavState(m, icon, 5_000)

    /** One tick one second later; returns the frame kinds. */
    private fun tick(maps: NavState?, showFrom: ShowFrom = ShowFrom.M200, test: NavState? = null, forward: Boolean = true): List<NavForwarder.Kind> {
        now += 1_000
        return f.tick(test, maps, forward, showFrom, now).map { it.kind }
    }

    @Test fun alwaysStartsAtOnceAndUpdatesEveryTick() {
        assertEquals(listOf(START, UPDATE), tick(maps(1500), ShowFrom.ALWAYS))
        assertEquals(listOf(UPDATE), tick(maps(1400), ShowFrom.ALWAYS))
        assertEquals(1400, f.shown!!.state.distM)
        assertTrue(f.shown!!.active)
    }

    @Test fun thresholdHoldsBackFarHints() {
        assertEquals(emptyList<NavForwarder.Kind>(), tick(maps(450)))
        assertEquals(emptyList<NavForwarder.Kind>(), tick(maps(250)))
        assertNull(f.shown)
        assertEquals(listOf(START, UPDATE), tick(maps(200)))
        assertEquals(listOf(UPDATE), tick(maps(150)))
    }

    @Test fun jitterAroundTheThresholdDoesNotFlicker() {
        tick(maps(200))
        // GPS/rounding jumps back above 200 m, but within the hysteresis (200 + 50): stays on
        assertEquals(listOf(UPDATE), tick(maps(250)))
        assertEquals(listOf(UPDATE), tick(maps(200)))
        assertEquals(listOf(UPDATE), tick(maps(240)))
        assertTrue(f.shown!!.active)
    }

    @Test fun passedTurnEndsUntilTheNextOneIsNear() {
        tick(maps(200)); tick(maps(50)); tick(maps(0))
        // next maneuver is 800 m away: back to the normal screen
        assertEquals(listOf(END), tick(maps(800, Icon.RIGHT)))
        assertFalse(f.shown!!.active)
        assertEquals(emptyList<NavForwarder.Kind>(), tick(maps(500, Icon.RIGHT)))
        assertEquals(listOf(START, UPDATE), tick(maps(200, Icon.RIGHT)))
    }

    @Test fun shortDropoutKeepsTheSession() {
        tick(maps(150))
        // Maps notification gone for 5 s: the last hint is re-sent, no end frame
        repeat(5) { assertEquals(listOf(UPDATE), tick(null)) }
        assertEquals(150, f.shown!!.state.distM)
        assertEquals(listOf(UPDATE), tick(maps(120)))
        // gone for good: ends once the grace period is over
        val kinds = (1..12).flatMap { tick(null) }
        assertEquals(END, kinds.last())
        assertEquals(1, kinds.count { it == END })
        assertEquals(emptyList<NavForwarder.Kind>(), tick(null))
    }

    @Test fun switchingForwardingOffEndsAtOnce() {
        tick(maps(150))
        assertEquals(listOf(END), tick(maps(150), forward = false))
        assertEquals(emptyList<NavForwarder.Kind>(), tick(maps(150), forward = false))
    }

    @Test fun testManeuverIgnoresThresholdAndStopsAtOnce() {
        assertEquals(listOf(START, UPDATE), tick(null, ShowFrom.M100, test = NavState(1200, Icon.UTURN, 2500)))
        assertEquals(NavOrigin.TEST, f.shown!!.origin)
        assertEquals(listOf(END), tick(null, ShowFrom.M100))
    }

    @Test fun testFallsBackToMapsWithinTheSameSession() {
        tick(maps(150), test = NavState(50, Icon.RIGHT, 0))
        assertEquals(listOf(UPDATE), tick(maps(140)))
        assertEquals(NavOrigin.GOOGLE_MAPS, f.shown!!.origin)
    }

    @Test fun arrivalIsShownRegardlessOfThreshold() {
        assertEquals(listOf(START, UPDATE), tick(maps(900, Icon.ARRIVED), ShowFrom.M100))
    }

    @Test fun sinceChangesOnlyWithTheContent() {
        tick(maps(150))
        val first = f.shown!!.sinceMs
        tick(maps(150))
        assertEquals(first, f.shown!!.sinceMs)
        tick(maps(100))
        assertEquals(now, f.shown!!.sinceMs)
    }

    @Test fun framesFollowTheSessionSequence() {
        now += 1000
        val out = f.tick(null, maps(150), true, ShowFrom.ALWAYS, now)
        val start = Frame.parse(out[0].frame)
        val upd = Frame.parse(out[1].frame)
        assertEquals(listOf(1, 1), start.data.map { it.toInt() })            // seq 1, cmd start
        assertEquals(listOf(2, 2), upd.data.take(2).map { it.toInt() })      // seq 2, cmd update
        assertEquals(150, (upd.data[2].toInt() and 0xFF) or ((upd.data[3].toInt() and 0xFF) shl 8))
        assertEquals(Icon.LEFT.code, upd.data[5].toInt())
        assertTrue(out[1].changed)
        assertFalse(f.tick(null, maps(150), true, ShowFrom.ALWAYS, now + 1000)[0].changed)
    }

    @Test fun hysteresis() {
        assertEquals(25, NavForwarder.hysteresisM(100))
        assertEquals(50, NavForwarder.hysteresisM(200))
        assertEquals(125, NavForwarder.hysteresisM(500))
    }
}
