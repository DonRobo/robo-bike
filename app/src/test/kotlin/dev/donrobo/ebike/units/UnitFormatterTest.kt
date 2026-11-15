package dev.donrobo.ebike.units

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UnitFormatterTest {
    private val de = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val en = UnitFormatter(UnitSystem.METRIC, Locale.UK)
    private val mi = UnitFormatter(UnitSystem.IMPERIAL, Locale.US)

    @Test fun metricGerman() {
        assertEquals(Measure("10,6", UnitLabel.KM), de.distance(10.6))
        assertEquals(Measure("1.284", UnitLabel.KM), de.distance(1284.4, 0))
        assertEquals(Measure("23", UnitLabel.KMH), de.speed(23.4))
    }

    @Test fun metricEnglish() {
        assertEquals(Measure("1,284", UnitLabel.KM), en.distance(1284.0, 0))
        assertEquals(Measure("0.0", UnitLabel.KM), en.distance(0.0))
    }

    @Test fun imperialConverts() {
        assertEquals(Measure("6.2", UnitLabel.MI), mi.distance(10.0))
        assertEquals(Measure("16", UnitLabel.MPH), mi.speed(25.0))
    }

    @Test fun shortDistances() {
        assertEquals(Measure("130", UnitLabel.M), de.shortDistance(130.0))
        assertEquals(Measure("1,2", UnitLabel.KM), de.shortDistance(1200.0))
        assertEquals(Measure("328", UnitLabel.FT), mi.shortDistance(100.0))
        assertEquals(Measure("0.6", UnitLabel.MI), mi.shortDistance(1000.0))
    }

    @Test fun unitLabels() {
        assertEquals(UnitLabel.KM, de.distanceUnit)
        assertEquals(UnitLabel.KMH, de.speedUnit)
        assertEquals(UnitLabel.MI, mi.distanceUnit)
        assertEquals(UnitLabel.MPH, mi.speedUnit)
    }

    @Test fun numbersInTheRidersUnit() {
        assertEquals(10.0, de.toUnit(10.0), 1e-9)
        assertEquals(10.0 / 1.609344, mi.toUnit(10.0), 1e-9)
        assertEquals(1.609344 * 200, UnitSystem.IMPERIAL.fromUnit(200.0), 1e-9)
        assertEquals(250.0, UnitSystem.METRIC.fromUnit(250.0), 1e-9)
    }

    @Test fun consumptionPerMile() {
        assertEquals(1.2, de.perDistance(1.2), 1e-9)
        assertEquals(1.2 * 1.609344, mi.perDistance(1.2), 1e-9)
    }
}
