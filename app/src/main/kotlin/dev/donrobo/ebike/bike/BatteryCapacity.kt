package dev.donrobo.ebike.bike

import dev.donrobo.ebike.maintenance.Maintenance
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The optional battery capacity (A1/F7, stored as `BikeKeys.batteryWh`, 0 = not set). Riders often know the pack's
 * voltage and ampere-hours (printed on the battery: "36 V 14 Ah") rather than watt-hours: Wh = V x Ah.
 */
object BatteryCapacity {
    const val MIN_WH = 50
    const val MAX_WH = 5_000
    val VOLT_RANGE = 12.0..100.0
    val AH_RANGE = 1.0..60.0

    sealed interface Result {
        data class Ok(val wh: Int) : Result
        /** Something is missing or not a number. */
        data object Invalid : Result
        /** A number, but no plausible e-bike battery. */
        data object OutOfRange : Result
    }

    fun fromVoltAh(volt: Double, ah: Double): Int = (volt * ah).roundToInt()

    /** Watt-hours typed directly. */
    fun parseWh(text: String, locale: Locale): Result {
        val v = Maintenance.parseNumber(text, locale) ?: return Result.Invalid
        return checked(v.roundToInt())
    }

    /** Voltage and ampere-hours typed separately ("36", "14,5"). */
    fun parseVoltAh(volt: String, ah: String, locale: Locale): Result {
        val v = Maintenance.parseNumber(volt, locale) ?: return Result.Invalid
        val a = Maintenance.parseNumber(ah, locale) ?: return Result.Invalid
        if (v !in VOLT_RANGE || a !in AH_RANGE) return Result.OutOfRange
        return checked(fromVoltAh(v, a))
    }

    private fun checked(wh: Int) = if (wh in MIN_WH..MAX_WH) Result.Ok(wh) else Result.OutOfRange
}
