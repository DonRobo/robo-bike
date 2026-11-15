package dev.donrobo.ebike.ride

import dev.donrobo.ebike.settings.Setting

object RideSettings {
    /** Continuous GPS while connected, for a track in the ride log. Off = one coarse position per connection. */
    val gpsTrack = Setting.bool("gpsTrack", false)
}
