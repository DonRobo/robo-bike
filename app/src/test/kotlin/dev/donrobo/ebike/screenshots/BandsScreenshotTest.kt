package dev.donrobo.ebike.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.donrobo.ebike.R
import dev.donrobo.ebike.range.BandRate
import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.HomeSettings
import dev.donrobo.ebike.range.LearnedRange
import dev.donrobo.ebike.range.LevelRate
import dev.donrobo.ebike.range.RangeEngine
import dev.donrobo.ebike.range.RangeReading
import dev.donrobo.ebike.ui.components.InfoSheet
import dev.donrobo.ebike.ui.range.BandsContent
import dev.donrobo.ebike.ui.range.BandsInfo
import dev.donrobo.ebike.ui.range.bandsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Akku-Bänder (R8) with the artboard's band distances: the two bottom bands not measured yet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BandsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val kmPerBand = listOf(8.0, 8.0, 8.0, 8.6, 8.8, 8.8, 8.9, 9.2, 9.5, 8.2)
    private val basis = listOf(0.0, 0.0, 31.0, 88.0, 133.0, 171.0, 202.0, 214.0, 188.0, 96.0)
    private val rides = listOf(0, 0, 4, 11, 18, 24, 29, 31, 27, 14)

    private val learned = LearnedRange(
        ridesUsed = 20, km = 260.0, pct = 312.0, pctPerKm = 1.2,
        levels = listOf(LevelRate(3, 200.0, 240.0, 1.2, Confidence.LEARNED)),
        months = emptyList(),
        bands = kmPerBand.indices.map { i ->
            BandRate(
                i * 10, i * 10 + 10, if (basis[i] > 0) 20.0 else 0.0, kmPerBand[i] / kmPerBand.average(),
                if (basis[i] > 0) Confidence.LEARNED else Confidence.UNKNOWN, basis[i], rides[i],
            )
        },
    )

    private fun state(battery: Int = 75) =
        bandsUiState(RangeEngine.compose(learned, 20, RangeReading(battery, 3, 5, live = true), 10, null, null, HomeSettings(false, null, 10.0)))

    @Test @Config(qualifiers = "de-$PHONE")
    fun bands_de() = compose.screenshot("bands_de") { BandsContent(state(), {}) }

    @Test @Config(qualifiers = "en-$PHONE")
    fun bands_en() = compose.screenshot("bands_en") { BandsContent(state(), {}) }

    /** An estimated band picked: the detail card explains the estimate. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun bands_estimated_de() = compose.screenshot("bands_estimated_de") { BandsContent(state(battery = 15), {}, initialPick = 0) }

    @Test @Config(qualifiers = "de-$PHONE")
    fun bands_empty_de() = compose.screenshot("bands_empty_de") { BandsContent(null, {}) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun bands_scrolled_de() = compose.scrolled("bands_scrolled_de", scroll = Scroll.UNDER) { BandsContent(state(), {}) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun bands_end_de() = compose.scrolled("bands_end_de", scroll = Scroll.ToEnd) { BandsContent(state(), {}) }

    @Test @Config(qualifiers = "de-$PHONE")
    fun info_bands_de() = compose.screenshotWindows("info_bands_de") {
        BandsContent(state(), {})
        InfoSheet(stringResource(R.string.range_bands_title), {}) { BandsInfo() }
    }
}
