package dev.donrobo.ebike.units

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Kilometres or miles (A5). Holds the one distance conversion; callers that only need numbers (parsing,
 * steppers, chart axes) use these, text goes through [UnitFormatter].
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL;

    /** Kilometres in one distance unit (1 km or 1 mi). */
    val kmPerUnit: Double get() = if (this == IMPERIAL) UnitFormatter.KM_PER_MI else 1.0

    /** [km] in this system's distance unit. */
    fun toUnit(km: Double): Double = km / kmPerUnit

    /** [units] of this system's distance unit in km. */
    fun fromUnit(units: Double): Double = units * kmPerUnit

    /** A per-km quantity (battery % per km, cent per km) per distance unit (per km or per mile). */
    fun perDistance(perKm: Double): Double = perKm * kmPerUnit

    /** The unit symbol of distances (km / mi). */
    val distanceUnit: UnitLabel get() = if (this == IMPERIAL) UnitLabel.MI else UnitLabel.KM

    /** The unit symbol of speeds (km/h / mph). */
    val speedUnit: UnitLabel get() = if (this == IMPERIAL) UnitLabel.MPH else UnitLabel.KMH
}

/** Unit symbol of a [Measure]; the UI maps it to a string resource. */
enum class UnitLabel { KM, MI, KMH, MPH, M, FT }

/** A number formatted for display plus its unit, rendered separately (the designs style them differently). */
data class Measure(val value: String, val unit: UnitLabel)

/**
 * The single place where distances and speeds become text. All inputs are metric (what the display
 * reports); conversion and number formatting follow [system] and [locale].
 */
class UnitFormatter(val system: UnitSystem, val locale: Locale) {

    /** Distance with [decimals] fraction digits and grouping (1.284 km, 10,6 km). */
    fun distance(km: Double, decimals: Int = 1): Measure = Measure(number(system.toUnit(km), decimals), system.distanceUnit)

    /** Short distances (navigation): metres below 1 km / feet below 0.1 mi, else [distance] with one decimal. */
    fun shortDistance(m: Double): Measure = when (system) {
        UnitSystem.METRIC -> if (abs(m) < 1000) Measure(number(m, 0), UnitLabel.M) else distance(m / 1000, 1)
        UnitSystem.IMPERIAL -> {
            val mi = m / 1000 / KM_PER_MI
            if (abs(mi) < 0.1) Measure(number(m / M_PER_FT, 0), UnitLabel.FT) else Measure(number(mi, 1), UnitLabel.MI)
        }
    }

    fun speed(kmh: Double, decimals: Int = 0): Measure = Measure(number(system.toUnit(kmh), decimals), system.speedUnit)

    /** The unit symbol of distances (km / mi), e.g. for an empty value or an axis. */
    val distanceUnit: UnitLabel get() = system.distanceUnit

    /** The unit symbol of speeds (km/h / mph). */
    val speedUnit: UnitLabel get() = system.speedUnit

    /** [km] in the rider's distance unit, as a number (charts). */
    fun toUnit(km: Double): Double = system.toUnit(km)

    /** A per-km quantity (battery % per km, cent per km) per the rider's distance unit. */
    fun perDistance(perKm: Double): Double = system.perDistance(perKm)

    /** Plain number in the locale (grouping on, fixed decimals). */
    fun number(v: Double, decimals: Int): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        isGroupingUsed = true
    }.format(v)

    companion object {
        const val KM_PER_MI = 1.609344
        const val M_PER_FT = 0.3048
    }
}
