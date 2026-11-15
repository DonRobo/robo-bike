package dev.donrobo.ebike.ui.disclosure

import dev.donrobo.ebike.ui.setup.AppPermission
import dev.donrobo.ebike.ui.setup.PermissionAction

/**
 * Prominent disclosures: shown in the app right before the system's permission dialog or settings page, they say
 * what is read, what for and where it goes; only an explicit "Agree" moves on. Back, a tap outside or "Not now" are
 * no consent and ask for nothing. The texts are in `strings_disclosure.xml`.
 */
enum class Disclosure {
    /** Before ACCESS_BACKGROUND_LOCATION ("allow all the time"): location while the app is closed. */
    BACKGROUND_LOCATION,
    /** Before the notification-access settings page: the Google Maps navigation notification only. */
    NOTIFICATION_ACCESS,
}

/** The disclosure to show before [action] for [permission], or null when it can go straight to the system. */
fun disclosureBefore(permission: AppPermission, action: PermissionAction): Disclosure? = when {
    action == PermissionAction.NONE -> null
    permission == AppPermission.BACKGROUND_LOCATION -> Disclosure.BACKGROUND_LOCATION
    permission == AppPermission.NOTIFICATION_ACCESS -> Disclosure.NOTIFICATION_ACCESS
    else -> null
}
