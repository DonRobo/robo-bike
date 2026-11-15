package dev.donrobo.ebike.light

import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting

/** What the app does with the headlight (L1, L5). */
enum class LightMode {
    /** Follows the sun curve in both directions, plus rain if enabled. */
    AUTO,
    /** Daytime running light: switched on at every connection. */
    ALWAYS_ON,
    /** The app never switches; the rider does it by hand. */
    OFF,
}

object LightSettings {
    /**
     * Per bike. Replaces the boolean "light.auto" (Migration v2: true -> AUTO, false -> OFF).
     * The default AUTO equals the old default true.
     */
    val mode = Setting.enum("light.mode", LightMode.AUTO, Scope.PER_BIKE)

    /**
     * Sun elevation (degrees, geometric, negative = below the horizon) at which the light goes off in the
     * morning while the sun rises, and on in the evening while it sets. Stored as elevations, not clock
     * times, so the points fit the whole year. Default 1° = the old fixed "dark below 1°" threshold.
     */
    val morningOffDeg = Setting.double("light.morningOffDeg", Sun.DARK_BELOW_DEG, Scope.PER_BIKE)
    val eveningOnDeg = Setting.double("light.eveningOnDeg", Sun.DARK_BELOW_DEG, Scope.PER_BIKE)

    /**
     * Rain as a second condition (L2) and from which intensity it counts (L4). Defaults = the behaviour
     * before these settings existed: rain on, from the first drop.
     */
    val rain = Setting.bool("light.rain", true, Scope.PER_BIKE)
    val rainLevel = Setting.enum("light.rainLevel", RainLevel.DRIZZLE, Scope.PER_BIKE)

    /** Range the switching points can be moved in (civil twilight is -6°, nautical -12°). */
    const val MIN_DEG = -12.0
    const val MAX_DEG = 20.0

    // The last position ("lastPosition") moved to dev.donrobo.ebike.location.LocationSettings.lastFix (same key).
}
