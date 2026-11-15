package dev.donrobo.ebike.ui.disclosure

import dev.donrobo.ebike.ui.setup.AppPermission
import dev.donrobo.ebike.ui.setup.PermissionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Prominent disclosures before sensitive permission requests (Play permission review). */
class DisclosureTest {
    @Test fun disclosureBeforeSensitiveRequestsOnly() {
        assertEquals(Disclosure.BACKGROUND_LOCATION, disclosureBefore(AppPermission.BACKGROUND_LOCATION, PermissionAction.REQUEST))
        // denied for good: the rider is sent to the app settings to pick "allow all the time" — disclosed as well
        assertEquals(Disclosure.BACKGROUND_LOCATION, disclosureBefore(AppPermission.BACKGROUND_LOCATION, PermissionAction.APP_SETTINGS))
        assertEquals(Disclosure.NOTIFICATION_ACCESS, disclosureBefore(AppPermission.NOTIFICATION_ACCESS, PermissionAction.LISTENER_SETTINGS))
        // nothing to ask (granted, or background location blocked until location is allowed)
        assertNull(disclosureBefore(AppPermission.BACKGROUND_LOCATION, PermissionAction.NONE))
        assertNull(disclosureBefore(AppPermission.NOTIFICATION_ACCESS, PermissionAction.NONE))
        // the others go straight to the system dialog
        for (p in listOf(AppPermission.BLUETOOTH, AppPermission.LOCATION, AppPermission.NOTIFICATIONS)) {
            assertNull(disclosureBefore(p, PermissionAction.REQUEST))
        }
        assertNull(disclosureBefore(AppPermission.BACKGROUND_RUN, PermissionAction.BATTERY_SETTINGS))
    }
}
