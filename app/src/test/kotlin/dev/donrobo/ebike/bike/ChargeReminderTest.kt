package dev.donrobo.ebike.bike

import dev.donrobo.ebike.bike.ChargeReminderTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeReminderTest {
    private fun tracker(threshold: Int = 30) = ChargeReminderTracker { threshold }

    @Test fun logic() {
        assertTrue(ChargeReminderLogic.shouldRemind(30, 22, 22))
        assertEquals(false, ChargeReminderLogic.shouldRemind(30, 30, 30))   // not below
        assertEquals(false, ChargeReminderLogic.shouldRemind(30, 18, 28))   // charged meanwhile
        // the reading is voltage-derived: after a sag to 20 under load it climbs back to 27 at rest by itself
        // (7 points, as on the owner's real rides) - recovery, not charging
        assertTrue(ChargeReminderLogic.shouldRemind(30, 20, 27))
        assertTrue(ChargeReminderLogic.shouldRemind(30, 20, 22))            // jitter, not charging
    }

    @Test fun remindsOnceWhenTheRideEndsBelowTheThreshold() {
        val t = tracker()
        assertNull(t.onSample(false, "b1", 40, 0))
        assertNull(t.onSample(true, "b1", 40, 1_000))     // ride starts
        assertNull(t.onSample(true, "b1", 31, 2_000))
        assertNull(t.onSample(true, "b1", 25, 3_000))
        assertEquals(Event.Remind("b1", 1_000, 25), t.onSample(false, "b1", 25, 4_000)) // recorder ended it
        assertNull(t.onSample(false, "b1", 25, 5_000))    // once
        assertNull(t.onStop())
    }

    @Test fun noReminderAboveThresholdOrWhenChargedBeforeTheEnd() {
        val t = tracker()
        t.onSample(true, "b1", 50, 0)
        assertNull(t.onSample(false, "b1", 35, 1))
        t.onSample(true, "b1", 30, 2)
        t.onSample(true, "b1", 20, 3)
        t.onSample(true, "b1", 24, 4)                     // plugged in during the 5 minutes standing still
        assertNull(t.onSample(false, "b1", 32, 5))
    }

    @Test fun recoveryAfterSagIsNotCharging() {
        val t = tracker()
        t.onSample(true, "b1", 34, 0)
        t.onSample(true, "b1", 21, 1)                     // sag on the last climb
        t.onSample(true, "b1", 27, 2)                     // standing: back at rest
        assertEquals(Event.Remind("b1", 0, 27), t.onSample(false, "b1", 27, 3))
        assertNull(t.onSample(false, "b1", 30, 4))        // still recovering hours later: not charged
    }

    @Test fun rideEndingWithTheServiceUsesTheLastBattery() {
        val t = tracker()
        t.onSample(true, "b1", 28, 7)
        t.onSample(true, null, null, 8)                   // connection lost, ride still open
        assertEquals(Event.Remind("b1", 7, 28), t.onStop())
    }

    @Test fun chargingAfterTheReminderResolvesIt() {
        val t = tracker()
        t.onSample(true, "b1", 20, 0)
        assertEquals(Event.Remind("b1", 0, 20), t.onSample(false, "b1", 20, 1))
        assertNull(t.onSample(false, "b1", 24, 2))        // recovery
        assertEquals(Event.Charged("b1"), t.onSample(false, "b1", 30, 3))
        assertNull(t.onSample(false, "b1", 60, 4))
    }

    @Test fun thresholdIsPerBike() {
        val t = ChargeReminderTracker { if (it == "b2") 15 else 30 }
        t.onSample(true, "b2", 20, 0)
        assertNull(t.onSample(false, "b2", 20, 1))
    }
}
