package dev.donrobo.ebike.maintenance

import android.content.Context
import androidx.annotation.StringRes
import dev.donrobo.ebike.R
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.text
import kotlin.math.abs

/** How a due item's remaining distance is worded (W1); one rule for the Rad card, the Start card and the alert. */
enum class DueWording { DUE_IN, DUE_NOW, OVERDUE }

/**
 * The wording for [remainingKm] as shown with whole units (see [dueDistance]): "Fällig in 46 km" while at least
 * half a unit is left, "Überfällig seit 12 km" from half a unit past due, "Jetzt fällig" in between (it would
 * read "0 km").
 */
fun dueWording(remainingKm: Double, fmt: UnitFormatter): DueWording {
    val units = fmt.toUnit(remainingKm)
    return when {
        units >= 0.5 -> DueWording.DUE_IN
        units > -0.5 -> DueWording.DUE_NOW
        else -> DueWording.OVERDUE
    }
}

/** The distance in the due text: whole units, without sign. */
fun dueDistance(remainingKm: Double, fmt: UnitFormatter) = fmt.distance(abs(remainingKm), 0)

@StringRes
fun DueWording.res(): Int = when (this) {
    DueWording.DUE_IN -> R.string.maint_due_in
    DueWording.DUE_NOW -> R.string.maint_due_now
    DueWording.OVERDUE -> R.string.maint_overdue
}

/** "Fällig in 46 km" / "Jetzt fällig" / "Überfällig seit 12 km", outside Compose (alerts). */
fun dueText(ctx: Context, remainingKm: Double, fmt: UnitFormatter): String {
    val w = dueWording(remainingKm, fmt)
    return if (w == DueWording.DUE_NOW) ctx.getString(w.res()) else ctx.getString(w.res(), dueDistance(remainingKm, fmt).text(ctx))
}

/** String resource of a built-in item's name; null for [MaintenanceKind.CUSTOM] (the rider's own name). */
@StringRes
fun MaintenanceKind.nameRes(): Int? = when (this) {
    MaintenanceKind.CHAIN -> R.string.bike_maint_chain
    MaintenanceKind.BRAKES -> R.string.bike_maint_brakes
    MaintenanceKind.GEAR -> R.string.bike_maint_gear
    MaintenanceKind.CUSTOM -> null
}

/** The item's name in the current language, outside Compose (alerts). */
fun MaintenanceItem.displayName(ctx: Context): String = kind.nameRes()?.let(ctx::getString) ?: name
