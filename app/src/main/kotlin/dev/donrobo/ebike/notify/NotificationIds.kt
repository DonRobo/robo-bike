package dev.donrobo.ebike.notify

/**
 * Every notification id of the app, in one place so no two kinds collide. Alerts are posted with the bike id as
 * tag, so one id per [AlertKind] covers all bikes.
 */
object NotificationIds {
    /** The foreground-service notification (BikeService). */
    const val CONNECTION = 1
    /** Lenkermodus auto-start fallback (M5). */
    const val HANDLEBAR = 20
    const val ALERT_HOME_RANGE = 101
    const val ALERT_CHARGE = 102
    const val ALERT_MAINTENANCE = 103
    const val ALERT_DRIVE_ERROR = 104

    val all = listOf(CONNECTION, HANDLEBAR, ALERT_HOME_RANGE, ALERT_CHARGE, ALERT_MAINTENANCE, ALERT_DRIVE_ERROR)
}

/**
 * Every PendingIntent request code of the app. Two PendingIntents with the same code and an equal intent are the
 * same PendingIntent (FLAG_UPDATE_CURRENT overwrites the other's extras), so each surface action has its own code.
 * An alert's tap uses its notification id ([NotificationIds]) as request code.
 */
object RequestCodes {
    /** "Trennen" action of the connection notification. */
    const val SERVICE_DISCONNECT = 1
    const val HANDLEBAR_OPEN = 20
    const val CONNECTION_OPEN = 50
    const val CONNECTION_LIGHT = 51
    const val WIDGET_OPEN = 60
    const val WIDGET_LIGHT = 61

    val all = listOf(
        SERVICE_DISCONNECT, HANDLEBAR_OPEN, CONNECTION_OPEN, CONNECTION_LIGHT, WIDGET_OPEN, WIDGET_LIGHT,
        NotificationIds.ALERT_HOME_RANGE, NotificationIds.ALERT_CHARGE, NotificationIds.ALERT_MAINTENANCE, NotificationIds.ALERT_DRIVE_ERROR,
    )
}
