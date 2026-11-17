package dev.donrobo.ebike.ride

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.ride.RideEndpoints.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RideEndsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = 1_800_000_000_000L
    private val end = start + 40 * 60_000L
    private val min = 60_000L
    private fun p(i: Int) = GeoPoint(48.2 + i / 1000.0, 16.37)
    private fun o(i: Int, t: Long) = Observation(p(i), t)

    @Test fun standingSideBeatsRidingSide() {
        // start: a fix 8 min before the first movement (connect) beats one 30 s into the ride
        assertEquals(p(1), RideEndpoints.at(listOf(o(1, start - 8 * min), o(2, start + 30_000)), start, stillBefore = true))
        // end: a fix 4 min after the last movement beats one 20 s before it
        assertEquals(p(3), RideEndpoints.at(listOf(o(4, end - 20_000), o(3, end + 4 * min)), end, stillBefore = false))
        // on the same side the closer one wins
        assertEquals(p(5), RideEndpoints.at(listOf(o(6, start - 9 * min), o(5, start - 1 * min)), start, stillBefore = true))
    }

    @Test fun ridingSideOnlyWithinTwoMinutes() {
        assertEquals(p(2), RideEndpoints.at(listOf(o(2, start + 90_000)), start, stillBefore = true))
        assertNull(RideEndpoints.at(listOf(o(2, start + 3 * min)), start, stillBefore = true))
        assertNull(RideEndpoints.at(listOf(o(2, end - 3 * min)), end, stillBefore = false))
        // and the standing side only within ten
        assertNull(RideEndpoints.at(listOf(o(2, start - 11 * min)), start, stillBefore = true))
        assertNull(RideEndpoints.at(listOf(o(2, end + 11 * min)), end, stillBefore = false))
    }

    @Test fun bothEndsOrNothing() {
        val obs = listOf(o(1, start - min), o(2, end + min))
        assertEquals(RideEnds(p(1), p(2)), RideEndpoints.pick(obs, start, end))
        assertNull(RideEndpoints.pick(obs.take(1), start, end))
    }

    @Test fun observationsCombineFixesAndPositionedSamples() {
        val samples = listOf(
            RideSample(start, null, null, 12.0, 80, 2, 0.0, 100.0),
            RideSample(end, 48.3, 16.4, 12.0, 70, 2, 0.0, 110.0),
        )
        val obs = RideEndpoints.observations(samples, listOf(LocationFix(p(1), start - min), LocationFix(p(9), 0)))
        assertEquals(listOf(o(1, start - min), Observation(GeoPoint(48.3, 16.4), end)), obs) // fixes without time are useless
    }

    @Test fun summaryAndIndexUseTheFixesFile() {
        val dir = tmp.newFolder("rides")
        val csv = File(dir, "2027-01-15_090000.csv")
        csv.writeText(
            RideCsv.HEADER + "\n" + listOf(
                RideSample(start, null, null, 15.0, 80, 2, 0.0, 100.0),
                RideSample(end, null, null, 15.0, 70, 2, 0.0, 112.0),
            ).joinToString("\n") { RideCsv.line(it) } + "\n",
        )
        val idx = RideIndex(dir)
        assertNull(idx.load().single().ends)
        // fixes arrive later (end of ride, disconnect): the cached summary is recomputed
        RideFixes.append(csv, LocationFix(GeoPoint(48.2085, 16.3721), start - 2 * min))
        RideFixes.append(csv, LocationFix(GeoPoint(48.2805, 16.3721), end + 3 * min))
        val ends = idx.load().single().ends!!
        assertEquals(8.0, ends.straightKm, 0.05)
        // a fresh index reads the cache and gets the same summary
        assertEquals(idx.load(), RideIndex(dir).load())
        // deleting the ride deletes its fixes
        RideStore.delete(idx.load().single())
        assertFalse(RideFixes.file(csv).exists())
    }

    @Test fun disconnectFixOnlyForARecentRide() {
        assertTrue(RideFixes.belongsToRide(csvModifiedMs = end, fixMs = end + 5 * min))
        assertFalse(RideFixes.belongsToRide(csvModifiedMs = end, fixMs = end + 30 * min))
    }
}
