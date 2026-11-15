package dev.donrobo.ebike.light

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class SunTest {
    private val lat = 48.21
    private val lon = 16.37
    private val vienna = ZoneId.of("Europe/Vienna")

    private fun elev(t: ZonedDateTime) = Sun.elevationDeg(t.toInstant().toEpochMilli(), lat, lon)

    /** Minute of the day where the sun crosses the standard rise/set altitude (-0.833° geometric). */
    private fun crossing(date: String, rising: Boolean): LocalTime {
        val day = ZonedDateTime.of(java.time.LocalDate.parse(date), LocalTime.MIDNIGHT, vienna)
        val range = if (rising) 0 until 720 else 720 until 1440
        val m = range.first { (elev(day.plusMinutes(it.toLong())) > -0.833) == rising }
        return LocalTime.of(m / 60, m % 60)
    }

    private fun assertNear(expected: String, actual: LocalTime, tolMin: Int = 3) {
        val diff = abs(LocalTime.parse(expected).toSecondOfDay() - actual.toSecondOfDay()) / 60
        assertTrue("expected ~$expected, got $actual", diff <= tolMin)
    }

    @Test fun sunriseSunsetVienna() {
        assertNear("04:53", crossing("2026-06-21", rising = true))
        assertNear("20:58", crossing("2026-06-21", rising = false))
        assertNear("07:42", crossing("2026-12-21", rising = true))
        assertNear("16:01", crossing("2026-12-21", rising = false))
    }

    /** Max elevation over the day = 90 - lat + declination. */
    private fun noonMax(date: String): Double {
        val day = ZonedDateTime.of(java.time.LocalDate.parse(date), LocalTime.of(10, 0), vienna)
        return (0..240).maxOf { elev(day.plusMinutes(it.toLong())) }
    }

    @Test fun solarNoonElevation() {
        assertEquals(65.23, noonMax("2026-06-21"), 0.5)
        assertEquals(18.35, noonMax("2026-12-21"), 0.5)
        assertEquals(41.79, noonMax("2026-03-20"), 0.5)
    }

    @Test fun midnightIsDark() {
        assertTrue(elev(ZonedDateTime.of(2026, 6, 21, 0, 30, 0, 0, vienna)) < -15)
    }
}
