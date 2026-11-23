package dev.donrobo.ebike.ride

import dev.donrobo.ebike.ride.RideTemperatures.Moment.END
import dev.donrobo.ebike.ride.RideTemperatures.Moment.START
import dev.donrobo.ebike.ride.RideTemperatures.Reading
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class RideTemperatureTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_762_803_180_000L

    private fun ride(): File = File(tmp.newFolder("rides"), "2025-11-10_203300.csv").apply { writeText(RideCsv.HEADER + "\n") }

    @Test fun lineFormat() {
        assertEquals("start,$t0,-2.4", RideTemperatures.encode(Reading(START, t0, -2.36)))
        assertEquals(Reading(END, t0, 3.5), RideTemperatures.decode("end,$t0,3.5"))
        for (bad in listOf("", "start,$t0", "middle,$t0,1.0", "start,x,1.0", "start,$t0,NaN", "start,$t0,1.0,extra")) {
            assertNull(bad, RideTemperatures.decode(bad))
        }
    }

    @Test fun roundTripThroughTheSidecarFile() {
        val csv = ride()
        RideTemperatures.append(csv, Reading(START, t0, -1.0))
        RideTemperatures.append(csv, Reading(END, t0 + 1_800_000, 2.5))
        assertEquals(File(csv.parentFile, "2025-11-10_203300.weather"), RideTemperatures.file(csv))
        assertEquals(RideTemperature(-1.0, 2.5), RideTemperatures.read(csv))
        // the CSV itself is untouched, so the index and every other reader see the same ride
        assertEquals(RideCsv.HEADER + "\n", csv.readText())
    }

    @Test fun ridesWithoutTemperatureStillLoad() {
        val csv = ride()
        assertNull(RideTemperatures.read(csv)) // every ride recorded before this file existed
        RideTemperatures.file(csv).writeText("garbage\n")
        assertNull(RideTemperatures.read(csv))
    }

    @Test fun onlyTheNewestReadingOfEachMomentCounts() {
        assertNull(RideTemperatures.of(emptyList()))
        assertEquals(RideTemperature(null, 4.0), RideTemperatures.of(listOf(Reading(END, t0, 4.0))))
        assertEquals(
            RideTemperature(1.0, 4.0),
            RideTemperatures.of(listOf(Reading(START, t0 + 10, 1.0), Reading(START, t0, 0.0), Reading(END, t0 + 20, 4.0))),
        )
    }

    @Test fun deletedRideGetsNoOrphanedFile() {
        val csv = ride()
        csv.delete()
        RideTemperatures.append(csv, Reading(START, t0, 1.0))
        assertFalse(RideTemperatures.file(csv).exists())
    }

    private val metric = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val imperial = UnitFormatter(UnitSystem.IMPERIAL, Locale.US)

    @Test fun formatting() {
        assertEquals(Measure("3", UnitLabel.CELSIUS), metric.temperature(2.6))
        assertEquals(Measure("0", UnitLabel.CELSIUS), metric.temperature(-0.4)) // never "-0"
        assertEquals(Measure("-3 → 1", UnitLabel.CELSIUS), metric.temperature(-2.8, 0.7))
        assertEquals(Measure("3", UnitLabel.CELSIUS), metric.temperature(3.2, 2.9)) // same when rounded: one value
        assertEquals(Measure("32", UnitLabel.FAHRENHEIT), imperial.temperature(0.0))
        assertEquals(Measure("23 → 41", UnitLabel.FAHRENHEIT), imperial.temperature(-5.0, 5.0))
    }

    @Test fun rideTemperatureText() {
        assertEquals(Measure("1 → 4", UnitLabel.CELSIUS), RideTemperature(1.0, 4.0).measure(metric))
        assertEquals(Measure("1", UnitLabel.CELSIUS), RideTemperature(1.0, null).measure(metric))
        assertEquals(Measure("4", UnitLabel.CELSIUS), RideTemperature(null, 4.0).measure(metric))
        assertNull(RideTemperature(null, null).measure(metric))
    }
}
