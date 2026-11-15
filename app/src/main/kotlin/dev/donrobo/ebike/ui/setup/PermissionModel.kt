package dev.donrobo.ebike.ui.setup

/**
 * The permissions of the setup's "Was die App darf" page (E2), in display order. Each one unlocks one feature and
 * is requested on its own.
 */
enum class AppPermission {
    /** BLUETOOTH_SCAN + BLUETOOTH_CONNECT ("nearby devices"): the link to the display. The only required one. */
    BLUETOOTH,
    /** Notification listener access: Google Maps turn instructions (N1). A special access, not a runtime permission. */
    NOTIFICATION_ACCESS,
    /** ACCESS_FINE_LOCATION (coarse alone = partial): sun position, rain, range map, ride track. */
    LOCATION,
    /**
     * ACCESS_BACKGROUND_LOCATION (owner decision 2026-11-14): without it a service started while the phone is in the
     * pocket gets no location. Android only lets the app ask after [LOCATION] was granted.
     */
    BACKGROUND_LOCATION,
    /**
     * Running in the pocket (Q1): exempt from battery optimisation and no "restricted" background usage. The companion
     * association grants the exemption by itself (REQUEST_COMPANION_RUN_IN_BACKGROUND: the system puts the app on the
     * power allowlist while the display is present, and lets it start its service from the background), so after
     * pairing only "restricted" can still be wrong. The app does not hold REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * (Play policy); without an association the system's battery-optimisation list opens instead.
     */
    BACKGROUND_RUN,
    /** POST_NOTIFICATIONS (API 33+): the connection notification with battery, range and Disconnect (K3). */
    NOTIFICATIONS,
}

enum class PermissionState {
    GRANTED,
    /** Location: only approximate. */
    PARTIAL,
    MISSING,
    /** Cannot be asked yet: needs another permission first (background location before location). */
    BLOCKED,
}

/** What the system currently says; read by [readPermissionFacts]. */
data class PermissionFacts(
    val bluetoothScan: Boolean,
    val bluetoothConnect: Boolean,
    val notificationListener: Boolean,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val backgroundLocation: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val backgroundRestricted: Boolean,
    val postNotifications: Boolean,
    /** At least one display is associated through the Companion Device Manager. */
    val companionAssociated: Boolean = false,
)

data class PermissionItem(val permission: AppPermission, val state: PermissionState) {
    val granted: Boolean get() = state == PermissionState.GRANTED
    val required: Boolean get() = permission == AppPermission.BLUETOOTH
}

fun permissionItems(f: PermissionFacts): List<PermissionItem> = AppPermission.entries.map { p ->
    PermissionItem(
        p,
        when (p) {
            AppPermission.BLUETOOTH -> granted(f.bluetoothScan && f.bluetoothConnect)
            AppPermission.NOTIFICATION_ACCESS -> granted(f.notificationListener)
            AppPermission.LOCATION -> when {
                f.fineLocation -> PermissionState.GRANTED
                f.coarseLocation -> PermissionState.PARTIAL
                else -> PermissionState.MISSING
            }
            AppPermission.BACKGROUND_LOCATION -> when {
                !f.fineLocation && !f.coarseLocation -> PermissionState.BLOCKED
                else -> granted(f.backgroundLocation)
            }
            AppPermission.BACKGROUND_RUN -> granted((f.batteryOptimizationIgnored || f.companionAssociated) && !f.backgroundRestricted)
            AppPermission.NOTIFICATIONS -> granted(f.postNotifications)
        },
    )
}

private fun granted(b: Boolean) = if (b) PermissionState.GRANTED else PermissionState.MISSING

/** The setup can go on once the required permission is there; everything else can be skipped. */
fun canContinue(items: List<PermissionItem>): Boolean = items.filter { it.required }.all { it.granted }

/** What tapping "Erlauben" does. */
enum class PermissionAction {
    /** Runtime permission dialog. */
    REQUEST,
    /** The app's system settings page (permission denied for good, or background usage set to "restricted"). */
    APP_SETTINGS,
    /** The notification-access toggle of this app. */
    LISTENER_SETTINGS,
    /** The system's battery-optimisation list (no direct exemption dialog: that needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    BATTERY_SETTINGS,
    NONE,
}

/**
 * @param askedBefore the app has shown this runtime dialog before
 * @param showRationale Android's shouldShowRequestPermissionRationale: false after a first request means the
 *   rider denied it for good, and the dialog would no longer appear
 */
fun actionFor(item: PermissionItem, askedBefore: Boolean, showRationale: Boolean, backgroundRestricted: Boolean): PermissionAction = when {
    item.state == PermissionState.GRANTED || item.state == PermissionState.BLOCKED -> PermissionAction.NONE
    item.permission == AppPermission.NOTIFICATION_ACCESS -> PermissionAction.LISTENER_SETTINGS
    item.permission == AppPermission.BACKGROUND_RUN -> if (backgroundRestricted) PermissionAction.APP_SETTINGS else PermissionAction.BATTERY_SETTINGS
    askedBefore && !showRationale -> PermissionAction.APP_SETTINGS
    else -> PermissionAction.REQUEST
}
