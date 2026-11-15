package dev.donrobo.ebike.settings

import dev.donrobo.ebike.units.UnitSystem

/** App-wide settings that belong to no single feature. The language is not here: see [AppLanguage]. */
object GeneralSettings {
    val units = Setting.enum("units", UnitSystem.METRIC)

    /** Colours from the wallpaper (Material You) instead of the Robo Bike green. */
    val wallpaperColors = Setting.bool("theme.wallpaperColors", false)
}
