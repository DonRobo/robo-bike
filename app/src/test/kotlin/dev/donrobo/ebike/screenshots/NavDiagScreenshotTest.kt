package dev.donrobo.ebike.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.NavDebug
import dev.donrobo.ebike.clock.ClockCheck
import dev.donrobo.ebike.nav.DisplayNav
import dev.donrobo.ebike.nav.NavOrigin
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.nav.ShowFrom
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.ui.diagnostics.DiagnosticsActions
import dev.donrobo.ebike.ui.diagnostics.DiagnosticsContent
import dev.donrobo.ebike.ui.diagnostics.DiagnosticsUiState
import dev.donrobo.ebike.ui.diagnostics.ErrorMemoryRead
import dev.donrobo.ebike.ui.nav.NavigationActions
import dev.donrobo.ebike.ui.nav.NavigationContent
import dev.donrobo.ebike.ui.nav.navigationUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavDiagScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val navActions = object : NavigationActions {
        override fun setForward(on: Boolean) {}
        override fun setShowFrom(v: ShowFrom) {}
        override fun openNotificationAccess() {}
    }
    private val diagActions = object : DiagnosticsActions {
        override fun selectTestIcon(icon: Icon) {}
        override fun selectTestDistance(m: Int) {}
        override fun sendTest() {}
        override fun stopTest() {}
        override fun setClockNow() {}
        override fun setBigEndian(on: Boolean) {}
        override fun readErrorMemory() {}
        override fun openFullLog() {}
        override fun shareLog() {}
    }

    /** The artboard's sample: 130 m left, sent 3 s ago, threshold 200 m, access granted. */
    private fun running() = navigationUiState(
        connected = true, displayNav = DisplayNav(NavState(130, Icon.LEFT, 2500), NavOrigin.GOOGLE_MAPS, 7_000, active = true),
        mapsNav = NavState(130, Icon.LEFT, 2500), testNav = null, forward = true, showFrom = ShowFrom.M200,
        notificationAccess = true, nowMs = 10_000,
    )

    @Test @Config(qualifiers = "de-$PHONE")
    fun navigation_de() = compose.screenshot("navigation_de") { NavigationContent(running(), navActions) {} }

    @Test @Config(qualifiers = "en-$PHONE")
    fun navigation_en() = compose.screenshot("navigation_en") { NavigationContent(running(), navActions) {} }

    /** Idle: connected, no navigation yet, notification access missing. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun navigation_idle_de() = compose.screenshot("navigation_idle_de") {
        NavigationContent(navigationUiState(true, null, null, null, true, ShowFrom.ALWAYS, false, 0), navActions) {}
    }

    /** Maps navigates, the turn is farther than the threshold. */
    @Test @Config(qualifiers = "de-$PHONE")
    fun navigation_waiting_de() = compose.screenshot("navigation_waiting_de") {
        NavigationContent(navigationUiState(true, null, NavState(650, Icon.RIGHT, 4000), null, true, ShowFrom.M200, true, 0), navActions) {}
    }

    private fun diag() = DiagnosticsUiState(
        connected = true, testIcon = Icon.LEFT, testDistM = 200, testNav = null,
        navDebug = NavDebug(
            texts = listOf("key: 0|com.google.android.apps.maps|…", "category: navigation, ongoing: true", "extra android.title: Mit Google Maps fahren"),
            icon = null, parsed = null,
            summary = listOf("category: navigation, ongoing: true", "title: Mit Google Maps fahren", "classifier (arrow shape): STRAIGHT"),
            shapeIcon = Icon.STRAIGHT,
        ),
        bigEndian = false,
        clock = ClockCheck(1_763_150_000_000, false, 1_763_150_000, 1_763_150_001, "f1 6b 17 69", automatic = true),
        errorRaw = 0,
        errorMemory = ErrorMemoryRead(List(16) { 0 } + "EKD01-BF ".map { it.code } + List(7) { 0 }, null),
        logTail = listOf(
            "21:45:56 Maps nav started: LEFT, 50 m",
            "21:45:57 Maps nav maneuver: LEFT in 0 m",
            "21:46:06 Maps nav maneuver: STRAIGHT in 0 m",
            "21:46:44 Maps nav ended (notification removed)",
        ),
    )

    @Test @Config(qualifiers = "de-w390dp-h2100dp-port-xhdpi")
    fun diagnostics_de() = compose.screenshot("diagnostics_de") { DiagnosticsContent(diag(), diagActions) {} }

    @Test @Config(qualifiers = "en-w390dp-h2100dp-port-xhdpi")
    fun diagnostics_en() = compose.screenshot("diagnostics_en") { DiagnosticsContent(diag(), diagActions) {} }

    // --- scrolled (SCROLL_PHONE, gesture navigation) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun navigation_end_de() = compose.scrolled("navigation_end_de", scroll = Scroll.ToEnd) { NavigationContent(running(), navActions) {} }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun diagnostics_scrolled_de() = compose.scrolled("diagnostics_scrolled_de", scroll = Scroll.UNDER) { DiagnosticsContent(diag(), diagActions) {} }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun diagnostics_end_de() = compose.scrolled("diagnostics_end_de", scroll = Scroll.ToEnd) { DiagnosticsContent(diag(), diagActions) {} }

    /** The full log opens at its newest line; 200 lines. */
    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun diagnostics_log_end_de() = compose.scrolled("diagnostics_log_end_de", scroll = Scroll.ToEnd) {
        dev.donrobo.ebike.ui.diagnostics.FullLog(List(200) { i -> "21:%02d:%02d Maps nav maneuver: LEFT in %d m".format(i / 60, i % 60, 200 - i) }, {}, {})
    }
}
