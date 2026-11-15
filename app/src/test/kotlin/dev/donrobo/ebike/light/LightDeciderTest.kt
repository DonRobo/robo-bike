package dev.donrobo.ebike.light

import dev.donrobo.ebike.light.LightMode.ALWAYS_ON
import dev.donrobo.ebike.light.LightMode.AUTO
import dev.donrobo.ebike.light.LightMode.OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightDeciderTest {
    private val d = LightDecider()
    private var t = 0L
    private var last: LightInput? = null

    /** One evaluation, [dt] ms after the previous one. Defaults: connected, unlocked, light off, AUTO, day, dry. */
    private fun step(
        conn: Boolean = true, unlocked: Boolean = true, on: Boolean = false, mode: LightMode = AUTO,
        curve: Boolean? = false, rain: Boolean? = false, dt: Long = 1_000,
    ): Boolean? {
        t += dt
        val input = LightInput(conn, unlocked, on, mode, curve, rain, t)
        last = input
        return d.update(input)
    }

    private fun reason() = lightReason(last!!, d)

    // --- the curve in both directions -------------------------------------------------------

    @Test fun connectWhileDarkSwitchesOnOnce() {
        assertEquals(true, step(curve = true))
        d.result(true)
        assertNull(step(curve = true, on = true))
        assertNull(step(curve = true, on = true, dt = 60_000))
        assertEquals(LightReason.DARK, reason())
    }

    @Test fun eveningPointSwitchesOn() {
        assertNull(step())
        assertEquals(LightReason.DAY, reason())
        assertEquals(true, step(curve = true))
    }

    @Test fun morningPointSwitchesOff() {
        assertNull(step(curve = true, on = true))
        assertEquals(false, step(curve = false, on = true))
        d.result(true)
        assertNull(step(curve = false, on = false))
        assertEquals(LightReason.DAY, reason())
    }

    @Test fun connectInDaylightWithLightOnSwitchesOff() {
        assertEquals(false, step(on = true))
    }

    @Test fun lightAlreadyInTargetStateSendsNothing() {
        assertNull(step(curve = true, on = true))
        assertNull(step(curve = false, on = false))
    }

    // --- unknown inputs ----------------------------------------------------------------------

    @Test fun offOnlyOnceEverythingIsKnown() {
        // daylight, weather not fetched yet: when in doubt the light stays on
        assertNull(step(rain = null, on = true))
        assertEquals(LightReason.DAY, reason())
        assertEquals(false, step(rain = false, on = true)) // weather arrives: dry
    }

    @Test fun noPositionNoWeatherDoesNothing() {
        assertNull(step(curve = null, rain = null, on = true))
        assertNull(step(curve = null, rain = null, on = false))
        assertEquals(LightReason.NO_POSITION, reason())
    }

    @Test fun noPositionButRainStillSwitchesOn() {
        assertEquals(true, step(curve = null, rain = true))
        assertEquals(LightReason.RAIN, reason())
    }

    @Test fun noPositionDryDoesNotSwitchOff() {
        assertNull(step(curve = null, rain = false, on = true))
    }

    @Test fun unknownDuringRideKeepsLastValue() {
        assertEquals(true, step(rain = true)); d.result(true)
        assertNull(step(rain = true, on = true))
        assertNull(step(rain = true, on = false, dt = 10_000)) // rider off
        assertNull(step(rain = null, on = false)) // weather fetch failed
        assertNull(step(rain = true, on = false)) // back: not a new transition
        assertNull(step(curve = null, rain = true, on = false)) // position blip
    }

    // --- rain ---------------------------------------------------------------------------------

    @Test fun rainDuringDayOnThenOff() {
        assertNull(step())
        assertEquals(true, step(rain = true)); d.result(true)
        assertNull(step(rain = true, on = true))
        assertEquals(LightReason.RAIN, reason())
        assertEquals(false, step(rain = false, on = true))
    }

    @Test fun rainStoppingAtNightKeepsTheLightOn() {
        assertNull(step(curve = true, rain = true, on = true))
        assertNull(step(curve = true, rain = false, on = true))
        assertEquals(LightReason.DARK, reason())
    }

    @Test fun darkAndRainReportsDark() {
        assertNull(step(curve = true, rain = true, on = true))
        assertEquals(LightReason.DARK, reason())
    }

    // --- the rider ----------------------------------------------------------------------------

    @Test fun manualOffIsHeldUntilTheNextTransition() {
        assertEquals(true, step(curve = true)); d.result(true)
        assertNull(step(curve = true, on = true))
        repeat(20) { assertNull(step(curve = true, on = false, dt = 30_000)) } // rider switched off: not fought
        assertTrue(d.manual)
        assertEquals(LightReason.MANUAL, reason())
        assertNull(step(curve = true, rain = true, on = false)) // rain on top: still wanted, no transition
        assertNull(step(curve = false, rain = false, on = false)) // morning: target off, already off
        assertFalse(d.manual)
        assertEquals(true, step(curve = true)) // next evening
    }

    @Test fun manualOnDuringTheDayIsHeld() {
        assertNull(step())
        repeat(10) { assertNull(step(on = true, dt = 30_000)) }
        assertTrue(d.manual)
        assertNull(step(curve = true, on = true)) // evening: target on, already on
        assertFalse(d.manual)
        assertEquals(LightReason.DARK, reason())
    }

    @Test fun displayEchoDelayIsNotManual() {
        assertEquals(true, step(curve = true)); d.result(true)
        assertNull(step(curve = true, on = false, dt = 500)) // report not updated yet
        assertFalse(d.manual)
        assertNull(step(curve = true, on = false, dt = 5_000))
        assertTrue(d.manual) // still off after the echo window: the rider (or the display) said no
    }

    @Test fun reconnectReasserts() {
        assertEquals(true, step(curve = true)); d.result(true)
        assertNull(step(curve = true, on = false, dt = 10_000)) // rider off
        assertNull(step(conn = false, curve = true))
        assertEquals(LightReason.NOT_CONNECTED, reason())
        assertEquals(true, step(curve = true))
    }

    @Test fun reconnectForgetsOldConditions() {
        assertNull(step(rain = true, on = true))
        assertNull(step(conn = false))
        // new connection, weather not fetched yet: the old "raining" is not reused, nothing is switched off
        assertNull(step(rain = null, on = true))
    }

    @Test fun notConnectedNeverSwitches() {
        assertNull(step(conn = false, curve = true))
        assertNull(step(conn = false, on = true))
    }

    // --- modes --------------------------------------------------------------------------------

    @Test fun alwaysOnSwitchesOnAtConnectionDayOrNight() {
        assertEquals(true, step(mode = ALWAYS_ON)); d.result(true)
        assertNull(step(mode = ALWAYS_ON, on = true))
        assertEquals(LightReason.ALWAYS_ON, reason())
        assertNull(step(mode = ALWAYS_ON, on = true, curve = true))
        assertNull(step(conn = false, mode = ALWAYS_ON))
        assertEquals(true, step(mode = ALWAYS_ON))
    }

    @Test fun alwaysOnDoesNotFightManualOff() {
        assertEquals(true, step(mode = ALWAYS_ON)); d.result(true)
        repeat(10) { assertNull(step(mode = ALWAYS_ON, on = false, dt = 30_000)) }
        assertEquals(LightReason.MANUAL, reason())
    }

    @Test fun modeOffLeavesTheLightAlone() {
        assertNull(step(mode = OFF, curve = true))
        assertNull(step(mode = OFF, curve = false, on = true))
        assertNull(step(mode = OFF, rain = true))
        assertEquals(LightReason.MODE_OFF, reason())
        assertFalse(d.manual)
    }

    @Test fun switchingModeReasserts() {
        assertNull(step(mode = OFF, curve = true))
        assertEquals(true, step(mode = AUTO, curve = true)); d.result(true)
        assertNull(step(mode = AUTO, curve = true, on = true))
        assertNull(step(mode = OFF, curve = true, on = true))
        assertEquals(true, step(mode = ALWAYS_ON, curve = false, on = false))
        d.result(true)
        assertEquals(false, step(mode = AUTO, curve = false, on = true))
    }

    // --- locked bike, failures -----------------------------------------------------------------

    @Test fun lockedWaitsWithoutUsingAttempts() {
        repeat(10) { assertNull(step(curve = true, unlocked = false, dt = 30_000)) }
        assertEquals(LightReason.LOCKED, reason())
        assertEquals(true, step(curve = true)); d.result(false)
        assertEquals(true, step(curve = true)); d.result(false)
        assertEquals(true, step(curve = true)); d.result(false)
        assertNull(step(curve = true))
    }

    @Test fun lockedPendingDroppedWhenLightAlreadyOn() {
        assertNull(step(curve = true, unlocked = false))
        assertNull(step(curve = true, unlocked = false, on = true))
        assertNull(step(curve = true, on = true))
    }

    @Test fun lockingMidRetryPausesRetries() {
        assertEquals(true, step(curve = true)); d.result(false)
        assertNull(step(curve = true, unlocked = false))
        assertNull(step(curve = true, unlocked = false))
        assertEquals(true, step(curve = true)); d.result(false)
        assertEquals(true, step(curve = true)); d.result(false)
        assertNull(step(curve = true))
    }

    @Test fun failedSwitchRetriesThreeTimesThenWaitsForTheNextTransition() {
        assertEquals(true, step(curve = true)); d.result(false)
        assertEquals(true, step(curve = true)); d.result(false)
        assertEquals(true, step(curve = true)); d.result(false)
        assertNull(step(curve = true))
        assertTrue(d.failed)
        assertEquals(LightReason.FAILED, reason())
        assertNull(step(curve = false)) // morning: off, already off
        assertFalse(d.failed)
        assertEquals(true, step(curve = true))
    }
}
