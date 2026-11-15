package dev.donrobo.ebike.nav

import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.proto.Icon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestManeuverTest {
    @After fun tearDown() = TestManeuver.stop()

    @Test fun startShowsItStopClearsIt() {
        val s = NavState(200, Icon.LEFT, 1500)
        TestManeuver.start(s)
        assertEquals(s, Repo.testNav.value)
        val next = NavState(100, Icon.RIGHT, 1400)
        TestManeuver.start(next) // a newer test replaces it (and restarts the timeout)
        assertEquals(next, Repo.testNav.value)
        TestManeuver.stop()
        assertNull(Repo.testNav.value)
    }
}
