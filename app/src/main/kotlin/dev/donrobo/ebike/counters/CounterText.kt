package dev.donrobo.ebike.counters

import androidx.annotation.StringRes
import dev.donrobo.ebike.R

/** Name of the counter, as shown on Start, the widget, the handlebar tiles and in Settings. */
@StringRes
fun DistanceCounter.labelRes(): Int = when (this) {
    DistanceCounter.THIS_RIDE -> R.string.counters_this_ride
    DistanceCounter.DISPLAY_TRIP -> R.string.counters_display_trip
    DistanceCounter.SINCE_CHARGE -> R.string.counters_since_charge
    DistanceCounter.SINCE_HOME -> R.string.counters_since_home
    DistanceCounter.TOTAL -> R.string.counters_total
}

/** One line on what the counter counts (Settings picker). */
@StringRes
fun DistanceCounter.descriptionRes(): Int = when (this) {
    DistanceCounter.THIS_RIDE -> R.string.counters_this_ride_desc
    DistanceCounter.DISPLAY_TRIP -> R.string.counters_display_trip_desc
    DistanceCounter.SINCE_CHARGE -> R.string.counters_since_charge_desc
    DistanceCounter.SINCE_HOME -> R.string.counters_since_home_desc
    DistanceCounter.TOTAL -> R.string.counters_total_desc
}

/** Decimals a counter is shown with: the odometer in whole km, the others to 100 m. */
fun DistanceCounter.decimals(): Int = if (this == DistanceCounter.TOTAL) 0 else 1
