package dev.donrobo.ebike.stats

import dev.donrobo.ebike.settings.Setting

object StatsSettings {
    /**
     * Electricity price in cents (of the currency shown, euro) per kWh, for the cost card (F7). App-wide:
     * the price belongs to where the rider charges, not to a bike. Default: a typical household price.
     */
    val priceCentPerKwh = Setting.double("stats.priceCentPerKwh", 30.0)

    /** Accepted range of the price input. */
    const val MIN_PRICE = 1.0
    const val MAX_PRICE = 200.0
}

/** Cost of the battery charge used: [total] in euros, [perKmCent] null when no distance was ridden. */
data class Cost(val kWh: Double, val total: Double, val perKmCent: Double?)

/**
 * Electricity cost of riding (F7). The display reports only battery percent (A1), so energy needs the
 * battery capacity the rider entered ([dev.donrobo.ebike.settings.BikeKeys.batteryWh]).
 *
 * All assumptions live here:
 * - Energy taken from the battery = used % x capacity. Percent is the display's state of charge, which
 *   is close to linear in energy for Li-ion packs over the normal range; good enough for a cost.
 * - Energy drawn from the socket is higher: charger and charging losses, [CHARGING_EFFICIENCY] (typical
 *   e-bike chargers plus battery charge losses reach 80-90 %).
 * - The price is a flat price per kWh (no time-of-day tariffs, no base fee).
 */
object ElectricityCost {
    const val CHARGING_EFFICIENCY = 0.85

    /** Null when the capacity is not set (<= 0): there is no honest number then. */
    fun of(batteryPct: Double, distanceKm: Double, capacityWh: Int, priceCentPerKwh: Double): Cost? {
        if (capacityWh <= 0) return null
        val kWh = batteryPct / 100.0 * capacityWh / 1000.0 / CHARGING_EFFICIENCY
        val total = kWh * priceCentPerKwh / 100.0
        return Cost(kWh, total, if (distanceKm > 0.05) total * 100.0 / distanceKm else null)
    }
}
