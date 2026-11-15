package dev.donrobo.ebike.units

import android.content.Context
import androidx.annotation.StringRes
import dev.donrobo.ebike.R

/** String resource of a unit symbol; for text built outside Compose (widget, notifications). */
@StringRes
fun UnitLabel.res(): Int = when (this) {
    UnitLabel.KM -> R.string.unit_km
    UnitLabel.MI -> R.string.unit_mi
    UnitLabel.KMH -> R.string.unit_kmh
    UnitLabel.MPH -> R.string.unit_mph
    UnitLabel.M -> R.string.unit_m
    UnitLabel.FT -> R.string.unit_ft
}

/** "4,2 km" as one string, outside Compose. */
fun Measure.text(ctx: Context): String = "$value ${ctx.getString(unit.res())}"
