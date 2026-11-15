package dev.donrobo.ebike.maintenance

import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The one "due in / due now / overdue" rule of the Rad card, the Start card and the alert. */
class DueWordingTest {
    private val km = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val mi = UnitFormatter(UnitSystem.IMPERIAL, Locale.US)

    @Test fun byTheShownWholeUnits() {
        assertEquals(DueWording.DUE_IN, dueWording(46.0, km))
        assertEquals(DueWording.DUE_IN, dueWording(0.5, km))
        assertEquals(DueWording.DUE_NOW, dueWording(0.4, km))
        assertEquals(DueWording.DUE_NOW, dueWording(-0.4, km))
        assertEquals(DueWording.OVERDUE, dueWording(-0.5, km))
        assertEquals(DueWording.OVERDUE, dueWording(-12.0, km))
    }

    @Test fun milesRoundInMiles() {
        assertEquals(DueWording.DUE_NOW, dueWording(0.7, mi)) // 0.43 mi would read "0 mi"
        assertEquals(DueWording.DUE_IN, dueWording(0.9, mi))
        assertEquals(DueWording.OVERDUE, dueWording(-0.9, mi))
    }

    @Test fun distanceIsUnsignedWholeUnits() {
        assertEquals("12", dueDistance(-12.3, km).value)
    }
}
