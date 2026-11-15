package dev.donrobo.ebike.nav

import dev.donrobo.ebike.settings.Setting

object NavSettings {
    /** Forward Google Maps turn-by-turn to the display (N6). */
    val forward = Setting.bool("forwardNav", true)
    /**
     * "Hinweis zeigen ab" (N3). Default [ShowFrom.ALWAYS] = the behaviour before the setting existed
     * (every hint is sent as soon as Maps shows it).
     */
    val showFrom = Setting.enum("nav.showFrom", ShowFrom.ALWAYS)
}
