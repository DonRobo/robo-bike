package dev.donrobo.ebike.ui.range

import dev.donrobo.ebike.range.BandRate
import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.HomeSettings
import dev.donrobo.ebike.range.LearnedRange
import dev.donrobo.ebike.range.RangeCalc
import dev.donrobo.ebike.range.RangeEngine
import dev.donrobo.ebike.range.RangeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsStateTest {
    private val factors = listOf(0.5, 0.7, 0.9, 1.0, 1.0, 1.0, 1.0, 1.1, 1.3, 1.5)
    private val learned = LearnedRange(
        20, 260.0, 312.0, 1.2, emptyList(), emptyList(),
        factors.mapIndexed { i, f -> BandRate(i * 10, i * 10 + 10, 20.0, f, if (i == 0) Confidence.UNKNOWN else Confidence.LEARNED, 30.0, 4) },
    )

    private fun detail(battery: Int?) = RangeEngine.compose(
        learned, 20, battery?.let { RangeReading(it, 0, 5, live = true) }, 10, null, null, HomeSettings(false, null, 10.0),
    )

    @Test fun bandsAddUpToTheFullAndRemainingRange() {
        val d = detail(37)
        val s = bandsUiState(d)!!
        assertEquals(10, s.rows.size)
        assertEquals(90, s.rows.first().lowPct) // top band first
        assertEquals(d.estimate!!.fullKm, s.fullKm, 1e-9)
        assertEquals(d.estimate!!.remainingKm, s.restKm!!, 1e-9)
        // band 30-40 holds the battery: 7 of its 10 percents are left
        val current = s.rows.single { it.current }
        assertEquals(30, current.lowPct)
        assertEquals(0.7, current.restFraction, 1e-9)
        // above it the battery is used up, below it still all there
        assertTrue(s.rows.filter { it.lowPct >= 40 }.all { it.restFraction == 0.0 })
        assertTrue(s.rows.filter { it.lowPct < 30 }.all { it.restFraction == 1.0 })
    }

    @Test fun bandDistanceFollowsItsFactorAndMargin() {
        val s = bandsUiState(detail(80))!!
        val top = s.rows.first()
        assertEquals(10 * 1.5 / 1.2 * RangeCalc.marginFactor(10), top.km, 1e-9)
        assertEquals(10 / top.km, top.pctPerKm, 1e-9)
        assertTrue(top.measured)
        assertFalse(s.rows.last().measured) // UNKNOWN = estimated
        assertEquals(1f, s.barFraction(top), 1e-6f) // the farthest band has the full bar
    }

    @Test fun withoutBatteryReadingEverythingIsLeftAndThereIsNoNow() {
        val s = bandsUiState(detail(null))!!
        assertNull(s.restKm)
        assertTrue(s.rows.all { it.restFraction == 1.0 && !it.current })
    }

    @Test fun selectionDefaultsToTheCurrentBand() {
        val s = bandsUiState(detail(37))!!
        assertEquals(30, s.selected(null).lowPct)
        assertEquals(0, s.selected(0).lowPct)
        assertEquals(90, bandsUiState(detail(null))!!.selected(null).lowPct)
    }

    @Test fun nothingLearnedNoBands() {
        assertNull(bandsUiState(null))
        assertNull(bandsUiState(detail(50).copy(learned = LearnedRange.EMPTY)))
    }
}
