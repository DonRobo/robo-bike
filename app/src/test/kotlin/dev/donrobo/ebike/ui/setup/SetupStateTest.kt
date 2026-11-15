package dev.donrobo.ebike.ui.setup

import dev.donrobo.ebike.pairing.SeenDevice
import dev.donrobo.ebike.pairing.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    private val none = PermissionFacts(false, false, false, false, false, false, false, false, false)
    private val all = PermissionFacts(true, true, true, true, true, true, true, false, true)

    @Test fun searchStates() {
        val d = SeenDevice("00:11:22:33:44:55", "EKD01-BF ", -50)
        assertEquals(SearchState.NeedsPermission, searchState(false, true, false, d))
        assertEquals(SearchState.BluetoothOff, searchState(true, false, false, d))
        assertEquals(SearchState.Searching, searchState(true, true, false, null))
        assertEquals(SearchState.NotFound, searchState(true, true, true, null))
        assertEquals(SearchState.Found("EKD01", d.address, Signal.GOOD), searchState(true, true, true, d)) // found after the timeout
    }

    @Test fun doneSummary() {
        val items = permissionItems(all.copy(notificationListener = false, backgroundLocation = false))
        val s = doneUiState("Stadtrad", "EKD01", items, associated = true, autoConnect = true)
        assertEquals(DoneLine.Bike("Stadtrad", "EKD01"), s.ready.first())
        assertEquals(listOf(AppPermission.NOTIFICATION_ACCESS, AppPermission.BACKGROUND_LOCATION), s.skipped.map { it.permission })
        assertTrue(s.autoConnects)
        assertFalse(doneUiState("x", null, items, associated = false, autoConnect = true).autoConnects)
        assertFalse(doneUiState("x", null, permissionItems(none), associated = true, autoConnect = true).autoConnects)
    }
}
