package dev.donrobo.ebike.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test fun url() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=48.21&longitude=16.37" +
                "&current=precipitation,rain,showers,snowfall,weather_code",
            Weather.url(48.20612, 16.37208),
        )
    }

    private fun p(mmPer15: Double?, code: Int? = 3) = Weather.precipitation(code, mmPer15, null, null, null, 900)!!

    @Test fun amountsAreScaledToMmPerHour() {
        assertEquals(0.4, p(0.1).rateMmH!!, 1e-9)
        assertEquals(2.0, Weather.precipitation(3, 2.0, null, null, null, 3600)!!.rateMmH!!, 1e-9)
        assertEquals(0.4, Weather.precipitation(3, 0.1, null, null, null, null)!!.rateMmH!!, 1e-9) // interval missing: 15 min
        // no total: rain + showers + snow (cm of snow ≈ 1/7 in mm of water)
        assertEquals(4.0 * (0.2 + 0.1 + 0.7 * 10 / 7), Weather.precipitation(null, null, 0.2, 0.1, 0.7, 900)!!.rateMmH!!, 1e-9)
        assertNull(Weather.precipitation(null, null, null, null, null, 900))
        assertEquals(Precipitation(null, 61), Weather.precipitation(61, null, null, null, null, 900))
    }

    @Test fun dryMatchesNoLevel() {
        for (l in RainLevel.entries) assertFalse(l.name, l.matches(p(0.0)))
        for (l in RainLevel.entries) assertFalse(l.name, l.matches(p(0.0, code = 45))) // fog
        assertFalse(p(0.0).wet)
    }

    @Test fun drizzleOnlyMatchesTheMostSensitiveLevel() {
        assertTrue(RainLevel.DRIZZLE.matches(p(0.0, code = 53)))
        assertFalse(RainLevel.RAIN.matches(p(0.0, code = 53)))
        assertTrue(RainLevel.DRIZZLE.matches(p(0.1))) // 0.4 mm/h
        assertFalse(RainLevel.RAIN.matches(p(0.1)))
        assertTrue(p(0.1).wet)
    }

    @Test fun rainLevel() {
        assertTrue(RainLevel.RAIN.matches(p(0.125))) // 0.5 mm/h
        assertTrue(RainLevel.RAIN.matches(p(0.0, code = 61))) // slight rain, amount rounded to 0
        assertTrue(RainLevel.RAIN.matches(p(0.0, code = 80)))
        assertTrue(RainLevel.RAIN.matches(p(0.0, code = 71))) // snow
        assertFalse(RainLevel.HEAVY.matches(p(0.0, code = 63)))
        assertFalse(RainLevel.HEAVY.matches(p(1.5))) // 6 mm/h
    }

    @Test fun heavyLevel() {
        assertTrue(RainLevel.HEAVY.matches(p(1.9))) // 7.6 mm/h
        for (c in listOf(65, 67, 75, 82, 86, 95, 96, 97, 99)) assertTrue("$c", RainLevel.HEAVY.matches(p(0.0, code = c)))
        for (l in RainLevel.entries) assertTrue(l.name, l.matches(p(3.0))) // 12 mm/h: everything
    }

    @Test fun wetCodes() {
        for (c in listOf(51, 57, 61, 67, 71, 77, 80, 86, 95, 99)) assertTrue("$c", Weather.isWetCode(c))
        for (c in listOf(0, 1, 2, 3, 45, 48, 58, 68, 78, 87, 94)) assertFalse("$c", Weather.isWetCode(c))
    }

    @Test fun rainNowForTheStatus() {
        assertEquals(RainNow.DISABLED, rainNow(false, RainLevel.RAIN, p(3.0)))
        assertEquals(RainNow.UNKNOWN, rainNow(true, RainLevel.RAIN, null))
        assertEquals(RainNow.DRY, rainNow(true, RainLevel.RAIN, p(0.0)))
        assertEquals(RainNow.BELOW_LEVEL, rainNow(true, RainLevel.RAIN, p(0.0, code = 51)))
        assertEquals(RainNow.WET, rainNow(true, RainLevel.RAIN, p(0.5)))
    }
}
