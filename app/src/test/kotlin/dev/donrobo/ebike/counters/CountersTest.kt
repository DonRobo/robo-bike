package dev.donrobo.ebike.counters

import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.LastKnown
import dev.donrobo.ebike.backup.Backup
import dev.donrobo.ebike.counters.CounterTracker.Event
import dev.donrobo.ebike.counters.HomeDeparture.Decision
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.ride.OpenRide
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import dev.donrobo.ebike.units.UnitSystem
import dev.donrobo.ebike.widget.bikeGlance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CountersTest {
    private val min = 60_000L
    private fun report(pct: Int = 80, odo: Double = 1000.0, trip: Double = 51.7, speed: Double = 0.0) =
        BikeReport(lightOn = false, gear = 2, gears = 5, batteryPct = pct, speedKmh = speed, tripKm = trip, odoKm = odo)

    // --- values ------------------------------------------------------------------

    private val inputs = CounterInputs(
        live = true, odoKm = 1052.0, tripKm = 51.7, openRide = OpenRide("b", 0, 1040.5),
        lastRideKm = 8.2, fullChargeOdoKm = 1020.0, homeDepartureOdoKm = 1038.0,
    )

    @Test fun eachCounter() {
        assertEquals(CounterReading(1052.0, false), Counters.value(DistanceCounter.TOTAL, inputs))
        assertEquals(CounterReading(51.7, false), Counters.value(DistanceCounter.DISPLAY_TRIP, inputs))
        assertEquals(32.0, Counters.value(DistanceCounter.SINCE_CHARGE, inputs).km!!, 1e-9)
        assertEquals(14.0, Counters.value(DistanceCounter.SINCE_HOME, inputs).km!!, 1e-9)
        assertEquals(CounterReading(11.5, false), Counters.value(DistanceCounter.THIS_RIDE, inputs))
    }

    @Test fun betweenRidesThisRideIsTheLastRideAndNeverLive() {
        assertEquals(CounterReading(8.2, true), Counters.value(DistanceCounter.THIS_RIDE, inputs.copy(openRide = null)))
        assertEquals(CounterReading(null, true), Counters.value(DistanceCounter.THIS_RIDE, inputs.copy(openRide = null, lastRideKm = null)))
    }

    @Test fun lastKnownValuesAreStale() {
        val off = inputs.copy(live = false)
        DistanceCounter.entries.forEach { assertTrue(it.name, Counters.value(it, off).stale) }
    }

    @Test fun freshInstallKnowsNothing() {
        DistanceCounter.entries.forEach { assertEquals(it.name, CounterReading(null, true), Counters.value(it, CounterInputs.NONE)) }
    }

    @Test fun neverSeenEventsAreUnknown() {
        val none = inputs.copy(fullChargeOdoKm = null, homeDepartureOdoKm = null)
        assertNull(Counters.value(DistanceCounter.SINCE_CHARGE, none).km)
        assertNull(Counters.value(DistanceCounter.SINCE_HOME, none).km)
    }

    @Test fun odometerBelowTheEventIsUnknownNotNegative() {
        assertNull(Counters.since(10.0, 1020.0))
        assertEquals(0.0, Counters.since(1020.0, 1020.0)!!, 0.0)
        assertEquals(0.0, Counters.since(1019.999, 1020.0)!!, 0.0) // rounding of the stored value
    }

    // --- full charge -------------------------------------------------------------

    private val start = 10_000 * min
    private fun before(pct: Int, minutesAgo: Long) = LastKnown(start - minutesAgo * min, report(pct))

    @Test fun fullAfterALowerLevelAndLongEnoughOff() {
        assertTrue(FullCharge.detected(before(40, 120), start, 100))
        assertTrue(FullCharge.detected(before(97, 30), start, 100))
    }

    @Test fun notFullBelowTheMaximum() = assertFalse(FullCharge.detected(before(40, 600), start, 99))

    @Test fun stillFullFromBeforeIsNoNewCharge() = assertFalse(FullCharge.detected(before(100, 600), start, 100))

    @Test fun reconnectSoonAfterIsNoCharge() = assertFalse(FullCharge.detected(before(99, 2), start, 100))

    @Test fun firstConnectionEverAtFullCounts() {
        assertTrue(FullCharge.detected(null, start, 100))
        assertTrue(FullCharge.detected(before(0, 1), start, 100)) // implausible last level = unknown
    }

    // --- departure ---------------------------------------------------------------

    private val home = GeoPoint(48.2085, 16.3721)
    private val nearHome = GeoPoint(48.2096, 16.3721) // ~120 m
    private val town = GeoPoint(48.2216, 16.3863) // ~1.8 km
    private val rideStart = 50_000 * min
    private fun fix(p: GeoPoint, at: Long) = LocationFix(p, at, 30f)

    @Test fun fixBeforeTheStartDecides() {
        assertEquals(Decision.DEPARTURE, HomeDeparture.decide(home, rideStart, 0.0, fix(nearHome, rideStart - 4 * min), null))
        assertEquals(Decision.NO_DEPARTURE, HomeDeparture.decide(home, rideStart, 0.0, fix(town, rideStart - 1 * min), null))
    }

    @Test fun parkedPositionDecidesWithoutAFreshFix() {
        val old = fix(town, rideStart - 60 * min) // too old to say where the bike stood
        assertEquals(Decision.DEPARTURE, HomeDeparture.decide(home, rideStart, 0.0, old, fix(nearHome, 0)))
        assertEquals(Decision.NO_DEPARTURE, HomeDeparture.decide(home, rideStart, 0.0, old, fix(town, 0)))
    }

    @Test fun lateFixWithinTheFirstHalfKilometre() {
        assertEquals(Decision.UNDECIDED, HomeDeparture.decide(home, rideStart, 0.2, null, null))
        // 400 m from home after 0.3 km ridden: within 300 m + 300 m
        val p = GeoPoint(48.2121, 16.3721)
        assertEquals(Decision.DEPARTURE, HomeDeparture.decide(home, rideStart, 0.3, fix(p, rideStart + min), null))
        assertEquals(Decision.NO_DEPARTURE, HomeDeparture.decide(home, rideStart, 0.0, fix(p, rideStart + min), null))
        assertEquals(Decision.NO_DEPARTURE, HomeDeparture.decide(home, rideStart, 0.6, null, null))
    }

    @Test fun noHomeNoDeparture() = assertEquals(Decision.NO_DEPARTURE, HomeDeparture.decide(null, rideStart, 0.0, fix(nearHome, rideStart), null))

    // --- session tracker -----------------------------------------------------------

    @Test fun fullChargeOnlyAtTheFirstPlausibleReading() {
        val t = CounterTracker("b", before(40, 300), start, null)
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 0), start, null, null, null)) // display not ready yet
        assertEquals(listOf(Event.FullyCharged(1000.0)), t.onReport(report(pct = 100), start + 1000, null, null, null))
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 100), start + 2000, null, null, null))
    }

    @Test fun flickerTo100DuringARideDoesNotCount() {
        val t = CounterTracker("b", before(40, 300), start, null)
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 99), start, null, null, null))
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 100), start + min, null, null, null))
    }

    @Test fun departureOncePerRideAtItsStartOdometer() {
        val t = CounterTracker("b", null, start, null)
        val ride = OpenRide("b", start + min, 1000.0)
        val f = fix(nearHome, start + 30_000)
        assertEquals(listOf(Event.LeftHome(1000.0)), t.onReport(report(pct = 0, odo = 1000.1, speed = 12.0), start + min, ride, f, home))
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 0, odo = 1000.3, speed = 12.0), start + 2 * min, ride, f, home))
    }

    @Test fun undecidedUntilAFixArrives() {
        val t = CounterTracker("b", null, start, null)
        val ride = OpenRide("b", start + min, 1000.0)
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 0, odo = 1000.1), start + min, ride, null, home))
        assertEquals(listOf(Event.LeftHome(1000.0)), t.onReport(report(pct = 0, odo = 1000.2), start + 2 * min, ride, fix(nearHome, start + 90_000), home))
    }

    @Test fun parkedOnlyForTheFirstRideOfTheSession() {
        val parked = fix(nearHome, 0)
        // ride started before this session (reconnect mid-ride): the parked position says nothing about its start
        val t = CounterTracker("b", null, start, parked)
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 0, odo = 1003.0), start, OpenRide("b", start - 10 * min, 1000.0), null, home))
        val u = CounterTracker("b", null, start, parked)
        assertEquals(listOf(Event.LeftHome(1000.0)), u.onReport(report(pct = 0), start + min, OpenRide("b", start + min, 1000.0), null, home))
        assertEquals(emptyList<Event>(), u.onReport(report(pct = 0, odo = 1010.0), start + 60 * min, OpenRide("b", start + 60 * min, 1010.0), null, home))
    }

    @Test fun ridesOfOtherBikesAreIgnored() {
        val t = CounterTracker("b", null, start, fix(nearHome, 0))
        assertEquals(emptyList<Event>(), t.onReport(report(pct = 0), start + min, OpenRide("other", start + min, 1000.0), null, home))
    }

    // --- settings, backup, widget -------------------------------------------------------

    @Test fun settingsPerBikeWithDefaultsAndSurviveABackup() {
        val s = Settings(MemoryStore())
        assertEquals(DistanceCounter.THIS_RIDE, s[CounterSettings.start])
        assertEquals(DistanceCounter.SINCE_CHARGE, s[CounterSettings.widget])
        assertNull(s.get(CounterSettings.fullChargeOdo, "a"))
        s.set(CounterSettings.fullChargeOdo, "a", 1234.56)
        s.set(CounterSettings.homeDepartureOdo, "a", 1240.0)
        s[CounterSettings.start] = DistanceCounter.SINCE_HOME
        assertNull(s.get(CounterSettings.fullChargeOdo, "b"))

        val restored = Settings(MemoryStore(Backup.decodeSettings(Backup.encodeSettings(s.store.all()))))
        assertEquals(1234.56, restored.get(CounterSettings.fullChargeOdo, "a")!!, 0.0)
        assertEquals(1240.0, restored.get(CounterSettings.homeDepartureOdo, "a")!!, 0.0)
        assertEquals(DistanceCounter.SINCE_HOME, restored[CounterSettings.start])
    }

    @Test fun widgetGlanceCarriesTheChosenCounter() {
        val fmt = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
        val g = bikeGlance(null, ConnectionStatus.CONNECTED, Snapshot.live(report(odo = 1052.0)), null, fmt, counter = DistanceCounter.SINCE_CHARGE, counters = inputs)
        assertEquals(DistanceCounter.SINCE_CHARGE, g.counter)
        assertEquals(Measure("32,0", UnitLabel.KM), g.counterValue)
        assertNull(bikeGlance(null, ConnectionStatus.OFF, null, null, fmt).counterValue)
    }
}
