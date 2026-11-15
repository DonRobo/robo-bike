package dev.donrobo.ebike.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {
    @Test fun notificationIdsAreDistinct() {
        assertEquals(NotificationIds.all.size, NotificationIds.all.toSet().size)
    }

    @Test fun requestCodesAreDistinct() {
        assertEquals(RequestCodes.all.size, RequestCodes.all.toSet().size)
    }

    @Test fun everyAlertKindHasItsOwnListedId() {
        val ids = AlertKind.entries.map { it.notificationId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(NotificationIds.all.containsAll(ids))
        assertTrue(RequestCodes.all.containsAll(ids)) // an alert's tap uses its id as request code
    }
}
