package dev.donrobo.ebike.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.range.BandRate
import dev.donrobo.ebike.range.BikePosition
import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.HomeSettings
import dev.donrobo.ebike.range.LearnedRange
import dev.donrobo.ebike.range.LevelRate
import dev.donrobo.ebike.range.MonthRate
import dev.donrobo.ebike.range.RangeEngine
import dev.donrobo.ebike.range.RangeReading
import dev.donrobo.ebike.ui.range.RangeActions
import dev.donrobo.ebike.ui.range.RangeContent
import dev.donrobo.ebike.ui.range.RangeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

private const val TALL = "w390dp-h2000dp-port-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RangeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val actions = object : RangeActions {
        override fun back() {}
        override fun setWindow(rides: Int) {}
        override fun setMargin(pct: Int) {}
        override fun setHomeEnabled(on: Boolean) {}
        override fun setReserveKm(km: Double) {}
        override fun pickHome() {}
        override fun openBands() {}
    }

    private val home = GeoPoint(48.2085, 16.3721)
    private val here = GeoPoint(48.2375, 16.3721) // 3.2 km straight line = 4.2 km way home
    private val nov = YearMonth.of(2026, 11)

    /** The artboard's numbers, run through the real assembly: at 75 % with 10 % margin, 118/89/64/48/37 km per level. */
    private val learned = LearnedRange(
        ridesUsed = 20, km = 260.0, pct = 312.0, pctPerKm = 1.2,
        levels = listOf(118.0, 89.0, 64.0, 48.0, 37.0).mapIndexed { i, km -> LevelRate(i + 1, 40.0, 40.0, 75 * 0.9 / km, Confidence.LEARNED) },
        months = (5 downTo 0).map { back -> MonthRate(nov.minusMonths(back.toLong()), 90.0 / (91.0 - (5 - back)) ) },
        // a linear battery (every band at the average), so the numbers above stay as on the artboard; the bands only
        // make the Akku-Bänder link appear
        bands = (0 until 10).map { BandRate(it * 10, it * 10 + 10, 20.0, 1.0, if (it < 2) Confidence.UNKNOWN else Confidence.LEARNED) },
    )

    private fun state(
        learned: LearnedRange = this.learned,
        position: BikePosition? = BikePosition(here, 0, BikePosition.Source.LIVE),
        limit: LocationLimit? = null,
        home: GeoPoint? = this.home,
        reading: RangeReading? = RangeReading(75, 3, 5, live = true),
    ) = RangeUiState(
        detail = RangeEngine.compose(learned, 20, reading, 10, position, limit, HomeSettings(true, home, 10.0)),
        window = 20, marginPct = 10, homeEnabled = true, home = home, reserveKm = 10.0,
    )

    @Test @Config(qualifiers = "de-$TALL")
    fun range_de() = compose.screenshot("range_de") { RangeContent(state(), actions) }

    @Test @Config(qualifiers = "en-$TALL")
    fun range_en() = compose.screenshot("range_en") { RangeContent(state(), actions) }

    /** First rides: nothing learned yet, no home set. */
    @Test @Config(qualifiers = "de-$TALL")
    fun range_learning_de() = compose.screenshot("range_learning_de") {
        val first = LearnedRange(2, 5.4, 6.0, null, emptyList(), (5 downTo 0).map { MonthRate(nov.minusMonths(it.toLong()), null) })
        RangeContent(state(learned = first, home = null), actions)
    }

    /** Learned, but the battery level is unknown (bike not connected since the update): full range only. */
    @Test @Config(qualifiers = "de-$TALL")
    fun range_battery_unknown_de() = compose.screenshot("range_battery_unknown_de") {
        val parked = BikePosition(here, 0, BikePosition.Source.PARKED)
        RangeContent(state(position = parked, reading = null), actions)
    }

    /** Riding with only "while in use" location: no position, so no ring and no answer about the way home. */
    @Test @Config(qualifiers = "de-$TALL")
    fun range_no_location_de() = compose.screenshot("range_no_location_de") {
        val oneMonth = learned.copy(months = learned.months.mapIndexed { i, m -> if (i < 5) m.copy(pctPerKm = null) else m })
        RangeContent(state(learned = oneMonth, position = null, limit = LocationLimit.NEEDS_BACKGROUND), actions)
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation unless named) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun range_scrolled_de() = compose.scrolled("range_scrolled_de", scroll = Scroll.UNDER) { RangeContent(state(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun range_end_de() = compose.scrolled("range_end_de", scroll = Scroll.ToEnd) { RangeContent(state(), actions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun range_end_buttons_de() = compose.scrolled("range_end_buttons_de", scroll = Scroll.ToEnd, bars = SystemBars.BUTTONS) { RangeContent(state(), actions) }
}
