package dev.donrobo.ebike.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class SunCurveTest {
    private val vienna = ZoneId.of("Europe/Vienna")
    private val oslo = ZoneId.of("Europe/Oslo")
    private val nov = SunDay.of(LocalDate.of(2026, 11, 14), vienna, 48.21, 16.37)

    private fun near(expected: String, actual: LocalTime?, tolMin: Int) {
        val e = LocalTime.parse(expected).toSecondOfDay() / 60
        val a = actual!!.toSecondOfDay() / 60
        assertTrue("expected ~$expected, got $actual", abs(e - a) <= tolMin)
    }

    @Test fun rule() {
        assertTrue(curveWantsOn(0.0, 0.1, 1.0, 5.0)) // rising, below morning point
        assertFalse(curveWantsOn(2.0, 2.1, 1.0, 5.0)) // rising, above
        assertTrue(curveWantsOn(4.0, 3.9, 1.0, 5.0)) // setting, below evening point
        assertFalse(curveWantsOn(6.0, 5.9, 1.0, 5.0))
    }

    @Test fun defaultPointsInViennaInNovember() {
        val s = nov.schedule(1.0, 1.0)
        // sunrise ~07:06, sunset ~16:24 (standard refraction); 1° geometric is a few minutes after/before
        near("07:13", s.morning.time, 10)
        near("16:15", s.evening.time, 10)
        assertTrue(s.morning.reached && s.evening.reached)
        val intervals = s.onIntervals()
        assertEquals(2, intervals.size)
        assertEquals(0, intervals[0].first)
        assertEquals(s.morning.minute, intervals[0].second)
        assertEquals(s.evening.minute, intervals[1].first)
        assertEquals(SunDay.MINUTES, intervals[1].second)
    }

    @Test fun noonIsMaximum() {
        near("11:50", LocalTime.of(nov.noon / 60, nov.noon % 60), 15)
        assertEquals(23.5, nov.maxDeg, 1.0)
    }

    @Test fun draggingToAMinuteSwitchesExactlyThere() {
        for (m in listOf(6 * 60 + 40, 7 * 60 + 30, 9 * 60)) assertEquals(m, nov.schedule(nov.morningDegFor(m), 1.0).morning.minute)
        for (m in listOf(15 * 60, 16 * 60 + 5, 17 * 60)) assertEquals(m, nov.schedule(1.0, nov.eveningDegFor(m)).evening.minute)
    }

    @Test fun dragIsClampedToItsHalfAndRange() {
        assertEquals(nov.morningDegFor(nov.noon), nov.morningDegFor(nov.noon + 200), 0.0)
        assertEquals(LightSettings.MIN_DEG, nov.morningDegFor(60), 0.0) // 01:00: far below -12°
        assertEquals(LightSettings.MIN_DEG, nov.eveningDegFor(23 * 60), 0.0)
        assertTrue(nov.eveningDegFor(10 * 60) <= nov.maxDeg)
    }

    @Test fun steppersMoveFiveMinutesAndStayInSyncWithTheCurve() {
        val m0 = nov.schedule(1.0, 1.0).morning.minute!!
        val later = nov.stepMorning(1.0, 1.0, forward = true)
        assertTrue(later > 1.0) // later in the morning = higher sun
        assertEquals(m0 + 5, nov.schedule(later, 1.0).morning.minute)
        assertEquals(m0 - 5, nov.schedule(nov.stepMorning(1.0, 1.0, forward = false), 1.0).morning.minute)

        val e0 = nov.schedule(1.0, 1.0).evening.minute!!
        val eLater = nov.stepEvening(1.0, 1.0, forward = true)
        assertTrue(eLater < 1.0) // later in the evening = lower sun
        assertEquals(e0 + 5, nov.schedule(1.0, eLater).evening.minute)
        // repeated steps keep moving (no rounding drift)
        var d = 1.0
        repeat(6) { d = nov.stepEvening(1.0, d, forward = false) }
        assertEquals(e0 - 30, nov.schedule(1.0, d).evening.minute)
    }

    @Test fun pointAboveTheDaysMaximumSwitchesAtNoon() {
        val s = nov.schedule(1.0, 30.0)
        assertFalse(s.evening.reached)
        assertTrue("on at noon: ${s.evening.minute} vs ${nov.noon}", abs(s.evening.minute!! - nov.noon) <= 2)
        assertTrue(abs(nov.eveningHandle(s, 30.0) - nov.noon) <= 2) // the point sits on the peak
        val m = nov.schedule(30.0, 1.0)
        assertFalse(m.morning.reached)
        assertTrue(abs(m.morning.minute!! - nov.noon) <= 2) // stays on all morning, off at noon
    }

    @Test fun polarNightIsOnAllDay() {
        val tromso = SunDay.of(LocalDate.of(2026, 12, 21), oslo, 69.65, 18.96)
        assertTrue(tromso.maxDeg < 0)
        val s = tromso.schedule(1.0, 1.0)
        assertNull(s.morning.minute)
        assertNull(s.evening.minute)
        assertTrue(s.on.all { it })
        assertEquals(tromso.noon, tromso.morningHandle(s, 1.0)) // drawn at the top of the curve
    }

    @Test fun midnightSunIsOffAllDay() {
        val tromso = SunDay.of(LocalDate.of(2026, 6, 21), oslo, 69.65, 18.96)
        assertTrue(tromso.minDeg > 1.0)
        val s = tromso.schedule(1.0, 1.0)
        assertNull(s.morning.minute)
        assertNull(s.evening.minute)
        assertTrue(s.on.none { it })
        assertFalse(s.morning.reached)
        // a higher point still works there
        val s10 = tromso.schedule(10.0, 10.0)
        assertTrue(s10.morning.reached && s10.morning.minute != null && s10.evening.minute != null)
    }

    @Test fun withoutAMatchingStepTheSteppersMoveDegrees() {
        val tromso = SunDay.of(LocalDate.of(2026, 12, 21), oslo, 69.65, 18.96)
        assertEquals(2.0, tromso.stepMorning(1.0, 1.0, forward = true), 1e-9)
        assertEquals(0.0, tromso.stepEvening(1.0, 1.0, forward = true), 1e-9)
        assertEquals(LightSettings.MAX_DEG, tromso.stepMorning(LightSettings.MAX_DEG, 1.0, forward = true), 1e-9)
    }

    @Test fun edgesForTheStatusLine() {
        val s = nov.schedule(1.0, 1.0)
        val m = s.morning.minute!!; val e = s.evening.minute!!
        assertEquals(s.evening.time to null, curveEdges(LightReason.DARK, s, e + 30))
        assertEquals(null to s.morning.time, curveEdges(LightReason.DARK, s, m - 30))
        assertEquals(null to s.evening.time, curveEdges(LightReason.DAY, s, 12 * 60))
        assertEquals(null to null, curveEdges(LightReason.RAIN, s, 12 * 60))
        assertEquals(null to null, curveEdges(LightReason.DARK, null, 12 * 60))
    }
}
