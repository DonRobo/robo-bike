package dev.donrobo.ebike.ui.nav

import dev.donrobo.ebike.nav.DisplayNav
import dev.donrobo.ebike.nav.NavOrigin
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.proto.Icon
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateTest {
    private val hint = NavState(130, Icon.LEFT, 2500)

    private fun status(connected: Boolean = true, shown: DisplayNav? = null, test: NavState? = null, forward: Boolean = true) =
        navigationUiState(connected, shown, test, forward, true, 0).status

    @Test fun cardStatus() {
        assertEquals(NavCardStatus.RUNNING, status(shown = DisplayNav(hint, NavOrigin.GOOGLE_MAPS, 0, active = true)))
        assertEquals(NavCardStatus.READY, status(shown = DisplayNav(hint, NavOrigin.GOOGLE_MAPS, 0, active = false)))
        assertEquals(NavCardStatus.NOT_CONNECTED, status(connected = false))
        assertEquals(NavCardStatus.OFF, status(forward = false))
        // a test maneuver is forwarded even with Maps forwarding off
        assertEquals(NavCardStatus.READY, status(forward = false, test = hint))
    }

    @Test fun ago() {
        assertEquals(Ago(3, Ago.Unit.SECONDS), Ago.of(10_000, 13_400))
        assertEquals(Ago(0, Ago.Unit.SECONDS), Ago.of(10_000, 9_000))
        assertEquals(Ago(2, Ago.Unit.MINUTES), Ago.of(0, 150_000))
        assertEquals(Ago(1, Ago.Unit.HOURS), Ago.of(0, 3_700_000))
        assertEquals(Ago(2, Ago.Unit.DAYS), Ago.of(0, 2 * 86_400_000L + 5))
    }
}
