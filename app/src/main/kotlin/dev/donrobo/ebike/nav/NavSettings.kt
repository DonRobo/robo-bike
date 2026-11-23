package dev.donrobo.ebike.nav

import dev.donrobo.ebike.settings.Setting

object NavSettings {
    /** Forward Google Maps turn-by-turn to the display (N6). */
    val forward = Setting.bool("forwardNav", true)
}
