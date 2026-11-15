package dev.donrobo.ebike.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class LocaleTest {
    @Test fun asOfIsTimeTodayElseDateAndTime() {
        val zone = ZoneId.of("Europe/Vienna")
        val day = LocalDate.of(2026, 11, 14)
        val at = day.atTime(14, 32).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14:32", formatAsOf(at, Locale.GERMANY, zone, day))
        assertEquals("14.11.26, 14:32", formatAsOf(at, Locale.GERMANY, zone, day.plusDays(1)))
    }
}
