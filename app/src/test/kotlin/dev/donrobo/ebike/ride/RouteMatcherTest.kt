package dev.donrobo.ebike.ride

import dev.donrobo.ebike.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.random.Random

/** Recurring-route recognition (F5) on synthetic tracks around Vienna. */
class RouteMatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val origin = GeoPoint(48.2085, 16.3721)
    private var nextStart = 1_762_000_000_000L

    private fun at(e: Double, n: Double) =
        GeoPoint(origin.lat + n / 111_320.0, origin.lon + e / (111_320.0 * cos(Math.toRadians(origin.lat))))

    /**
     * A ride along waypoints (metres east/north of the origin) sampled every second at ~18 km/h, with an
     * odometer that follows the true path. [noise] adds GPS jitter (metres, gaussian-ish), [spikes] single
     * wild fixes, [odoFactor] scales the odometer (another length), [gps] = false records no positions.
     */
    private fun ride(
        vararg wp: Pair<Int, Int>, noise: Double = 0.0, spikes: Int = 0, odoFactor: Double = 1.0, gps: Boolean = true, seed: Int = 1,
    ): RideSummary {
        val rnd = Random(seed)
        val pts = wp.map { (e, n) -> e.toDouble() to n.toDouble() }
        val samples = ArrayList<RideSample>()
        val start = nextStart; nextStart += 86_400_000
        var odo = 1000.0; var t = start
        val stepM = 5.0
        for ((a, b) in pts.zipWithNext()) {
            val len = Math.hypot(b.first - a.first, b.second - a.second)
            val n = (len / stepM).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val f = i.toDouble() / n
                var e = a.first + (b.first - a.first) * f; var no = a.second + (b.second - a.second) * f
                e += (rnd.nextDouble() * 2 - 1) * noise; no += (rnd.nextDouble() * 2 - 1) * noise
                if (spikes > 0 && rnd.nextInt(400) < spikes) { e += 800; no -= 600 }
                val p = at(e, no)
                samples += RideSample(t, if (gps) p.lat else null, if (gps) p.lon else null, 18.0, 80, 2, 0.0, odo)
                t += 1000; odo += stepM / 1000 * odoFactor
            }
        }
        val last = pts.last(); val p = at(last.first, last.second)
        samples += RideSample(t, if (gps) p.lat else null, if (gps) p.lon else null, 18.0, 70, 2, 0.0, odo)
        return RideCsv.summarize(File(RideCsv.fileName(start)), samples)!!
    }

    // home (0,0) to work (~3.6 km) with a few turns
    private val commute = arrayOf(0 to 0, 900 to 0, 900 to 1200, 2200 to 1200, 2300 to 2000, 2900 to 2050)
    private val commuteBack = commute.reversedArray()

    @Test fun sameRouteBothDirections() {
        val a = ride(*commute); val b = ride(*commuteBack)
        assertNotNull(RouteMatcher.match(a, b))
        val g = RouteMatcher.group(listOf(a, b), emptyList())
        assertEquals(1, g.routes.size)
        assertEquals(setOf(a.id, b.id), g.byRide.keys)
    }

    @Test fun noisyTracksStillMatch() {
        val clean = ride(*commute)
        val noisy = ride(*commuteBack, noise = 25.0, spikes = 3, seed = 7)
        assertNotNull(RouteMatcher.match(clean, noisy))
    }

    @Test fun smallDetourStillMatches() {
        // a 300 m loop around a block in the middle of a 3.6 km way
        val detour = ride(0 to 0, 900 to 0, 900 to 600, 1250 to 600, 1250 to 800, 900 to 800, 900 to 1200, 2200 to 1200, 2300 to 2000, 2900 to 2050)
        assertNotNull(RouteMatcher.match(ride(*commute), detour))
    }

    @Test fun sameEndsOtherWayDoesNotMatch() {
        // same start and destination and about the same length, but north first, then east: another way
        val other = ride(0 to 0, 0 to 2050, 2900 to 2050)
        assertNull(RouteMatcher.match(ride(*commute), other))
    }

    @Test fun parallelRoadNearbyDoesNotMatch() {
        // ends within the end radius (~250 m off), but the whole way on streets ~180 m beside the commute
        val parallel = ride(*commute.map { (e, n) -> e + 180 to n - 180 }.toTypedArray())
        assertNull(RouteMatcher.match(ride(*commute), parallel))
    }

    @Test fun otherDestinationDoesNotMatch() {
        val shorter = ride(0 to 0, 900 to 0, 900 to 1200, 2200 to 1200) // stops 900 m before work
        assertNull(RouteMatcher.match(ride(*commute), shorter))
    }

    @Test fun muchLongerOdometerDoesNotMatch() {
        assertNull(RouteMatcher.match(ride(*commute), ride(*commute, odoFactor = 1.6)))
    }

    @Test fun ridesWithoutTrackAreNeverMatched() {
        val a = ride(*commute); val b = ride(*commute); val noGps = ride(*commute, gps = false)
        assertNull(noGps.shape)
        assertNull(RouteMatcher.match(a, noGps))
        val g = RouteMatcher.group(listOf(a, b, noGps), emptyList())
        assertEquals(1, g.routes.size)
        assertNull(g.byRide[noGps.id])
    }

    @Test fun singleRideIsNotRecurringUntilNamed() {
        val a = ride(*commute)
        assertTrue(RouteMatcher.group(listOf(a), emptyList()).routes.isEmpty())
        val def = RouteDef("r1", "Arbeitsweg", a.shape!!)
        val g = RouteMatcher.group(listOf(a, ride(*commuteBack, noise = 10.0)), listOf(def))
        assertEquals(1, g.routes.size)
        assertEquals("n-r1", g.routes[0].id)
        assertEquals("Arbeitsweg", g.routes[0].name)
        assertEquals(2, g.routes[0].rides.size)
    }

    @Test fun twoRoutesAreKeptApart() {
        val other = arrayOf(0 to 0, -1500 to 0, -1500 to -2500)
        val rides = listOf(ride(*commute), ride(*other), ride(*commuteBack), ride(*other), ride(*commute))
        val g = RouteMatcher.group(rides, emptyList())
        assertEquals(listOf(3, 2), g.routes.map { it.rides.size })
        // newest first within a route, oldest ride gives the id
        assertEquals("c-${rides[0].id}", g.routes[0].id)
        assertEquals(rides[4].id, g.routes[0].last.id)
    }

    @Test fun routeDefsSurviveEncoding() {
        val shape = ride(*commute).shape!!
        val defs = listOf(RouteDef("a", "Arbeitsweg", shape), RouteDef("b", "Zur Oma\tüber Berg; 100%", shape))
        assertEquals(defs, RouteDef.decodeAll(RouteDef.encodeAll(defs)!!))
        assertNull(RouteDef.encodeAll(emptyList()))
    }

    @Test fun shapeHasFixedPointsAndIgnoresStandingJitter() {
        val r = ride(*commute)
        val s = r.shape!!
        assertEquals(RideShape.POINTS, s.points.size)
        assertTrue(s.start.distanceM(origin) < 10)
        assertTrue(s.end.distanceM(at(2900.0, 2050.0)) < 10)
        assertEquals(s, RideShape.decode(s.encode()))
    }

    @Test fun indexCachesTheShapeExactly() {
        val dir = tmp.newFolder("rides")
        val samples = (0 until 400).map { i ->
            val p = at(i * 5.0, i * 2.0)
            RideSample(1_762_000_000_000L + i * 1000, p.lat, p.lon, 18.0, 80, 2, 0.0, 1000 + i * 0.005)
        }
        File(dir, "2025-11-01_080000.csv").writeText(RideCsv.HEADER + "\n" + samples.joinToString("\n") { RideCsv.line(it) } + "\n")
        val fresh = RideIndex(dir).load()
        val cached = RideIndex(dir).load()
        assertNotNull(fresh.single().shape)
        assertEquals(fresh, cached)
    }
}
