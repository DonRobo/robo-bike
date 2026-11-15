package dev.donrobo.ebike.notify

import dev.donrobo.ebike.settings.Setting

/**
 * Settings of the notifications (Einstellungen › Mitteilungen). App-wide: they describe how the rider wants
 * to be told things, not a bike. The settings screen wires them; this module only reads them.
 */
object NotifySettings {
    /**
     * Settings row "Akku in der Verbindungsmitteilung" (K3). A foreground service must always show a notification while
     * the app holds the bike connection, so this cannot remove it. On: the connection notification carries
     * battery, range and light plus the "Licht an/aus" action and follows them live. Off: it only says that the
     * bike is connected (plus "Trennen"); it then never changes during a ride, so once swiped away (possible
     * on Android 14+ while unlocked) it stays away until the next connection.
     */
    val connectionDetails = Setting.bool("notify.connectionDetails", true)

    /** Row "Erinnerungen — Wartung, Laden, Heimweg": one switch per kind; see [AlertKind]. */
    val homeRange = Setting.bool("notify.homeRange", true)
    val charge = Setting.bool("notify.charge", true)
    val maintenance = Setting.bool("notify.maintenance", true)
    /** Not listed in the row's subtitle; a drive error is not a reminder, so it gets its own switch. */
    val driveError = Setting.bool("notify.driveError", true)
}
