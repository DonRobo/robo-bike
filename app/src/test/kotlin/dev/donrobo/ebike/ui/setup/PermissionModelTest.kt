package dev.donrobo.ebike.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionModelTest {
    private val none = PermissionFacts(false, false, false, false, false, false, false, false, false)
    private val all = PermissionFacts(true, true, true, true, true, true, true, false, true)

    private fun state(f: PermissionFacts, p: AppPermission) = permissionItems(f).first { it.permission == p }.state

    @Test fun permissionStates() {
        assertEquals(AppPermission.entries, permissionItems(none).map { it.permission })
        assertTrue(permissionItems(all).all { it.granted })
        assertEquals(PermissionState.MISSING, state(none.copy(bluetoothScan = true), AppPermission.BLUETOOTH)) // needs both
        assertEquals(PermissionState.PARTIAL, state(none.copy(coarseLocation = true), AppPermission.LOCATION))
        // background location only after foreground location
        assertEquals(PermissionState.BLOCKED, state(none, AppPermission.BACKGROUND_LOCATION))
        assertEquals(PermissionState.MISSING, state(none.copy(coarseLocation = true), AppPermission.BACKGROUND_LOCATION))
        // "restricted" background usage breaks the pocket case even with the battery exemption
        assertEquals(PermissionState.MISSING, state(all.copy(backgroundRestricted = true), AppPermission.BACKGROUND_RUN))
        // the companion association is the exemption (no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), "restricted" still wins
        assertEquals(PermissionState.GRANTED, state(none.copy(companionAssociated = true), AppPermission.BACKGROUND_RUN))
        assertEquals(PermissionState.MISSING, state(none.copy(companionAssociated = true, backgroundRestricted = true), AppPermission.BACKGROUND_RUN))
        assertEquals(PermissionState.MISSING, state(none, AppPermission.BACKGROUND_RUN))
    }

    @Test fun onlyBluetoothIsRequired() {
        assertFalse(canContinue(permissionItems(none)))
        assertTrue(canContinue(permissionItems(none.copy(bluetoothScan = true, bluetoothConnect = true))))
    }

    @Test fun actions() {
        val items = permissionItems(none.copy(coarseLocation = true))
        fun item(p: AppPermission) = items.first { it.permission == p }
        assertEquals(PermissionAction.REQUEST, actionFor(item(AppPermission.BLUETOOTH), askedBefore = false, showRationale = false, backgroundRestricted = false))
        assertEquals(PermissionAction.REQUEST, actionFor(item(AppPermission.BLUETOOTH), askedBefore = true, showRationale = true, backgroundRestricted = false))
        // denied for good: the dialog would not show any more, so the app settings open
        assertEquals(PermissionAction.APP_SETTINGS, actionFor(item(AppPermission.BLUETOOTH), askedBefore = true, showRationale = false, backgroundRestricted = false))
        assertEquals(PermissionAction.LISTENER_SETTINGS, actionFor(item(AppPermission.NOTIFICATION_ACCESS), false, false, false))
        assertEquals(PermissionAction.BATTERY_SETTINGS, actionFor(item(AppPermission.BACKGROUND_RUN), false, false, false))
        assertEquals(PermissionAction.APP_SETTINGS, actionFor(item(AppPermission.BACKGROUND_RUN), false, false, backgroundRestricted = true))
        assertEquals(PermissionAction.REQUEST, actionFor(item(AppPermission.LOCATION), false, false, false)) // coarse -> ask for precise
        assertEquals(PermissionAction.NONE, actionFor(permissionItems(none).first { it.permission == AppPermission.BACKGROUND_LOCATION }, false, false, false))
        assertEquals(PermissionAction.NONE, actionFor(permissionItems(all).first(), false, false, false))
    }
}
