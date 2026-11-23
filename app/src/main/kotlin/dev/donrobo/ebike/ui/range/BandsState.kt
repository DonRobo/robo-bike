package dev.donrobo.ebike.ui.range

import dev.donrobo.ebike.range.Confidence
import dev.donrobo.ebike.range.RangeCalc
import dev.donrobo.ebike.range.RangeDetail

/**
 * One battery band of the Akku-Bänder screen (R8): how far its [lowPct]..[highPct] percent go on their own ([km],
 * with the safety margin, so all bands add up to "Voll geladen"). [measured] = learned from enough own data
 * (solid bar), else estimated from the course of the measured bands (dotted, "≈"). [restFraction] = the part of
 * the band still in the battery now (1 above the current level, 0 below); [current] = the band the battery is in.
 * [basisKm] / [rides] = what it was measured on.
 */
data class BandRow(
    val lowPct: Int, val highPct: Int, val km: Double, val measured: Boolean,
    val restFraction: Double, val current: Boolean, val basisKm: Double, val rides: Int,
) {
    /** Consumption in this band, % per km. */
    val pctPerKm: Double get() = (highPct - lowPct) / km
}

/** Rows top band first, plus the sums ("Voll geladen" and "Jetzt"); null while nothing is learned. */
data class BandsUiState(val rows: List<BandRow>, val fullKm: Double, val restKm: Double?, val batteryPct: Int?) {
    /** Bar length of each row relative to the farthest-reaching band (0..1). */
    fun barFraction(row: BandRow): Float = (row.km / rows.maxOf { it.km }).toFloat()
}

fun bandsUiState(d: RangeDetail?): BandsUiState? {
    val learned = d?.learned ?: return null
    if (learned.bands.isEmpty()) return null
    val rate = RangeCalc.rateFor(learned, d.reading?.level)?.takeIf { it > 0 } ?: return null
    val margin = RangeCalc.marginFactor(d.marginPct)
    val battery = d.reading?.batteryPct
    val rows = learned.bands.sortedByDescending { it.lowPct }.map { b ->
        // the band's percents are worth `factor` average percents each, as in LearnedRange.averagePct
        val km = (b.highPct - b.lowPct) * b.factor / rate * margin
        val rest = battery?.let { ((it - b.lowPct).toDouble() / (b.highPct - b.lowPct)).coerceIn(0.0, 1.0) } ?: 1.0
        BandRow(
            b.lowPct, b.highPct, km, b.confidence == Confidence.LEARNED, rest,
            current = battery != null && battery > b.lowPct && battery <= b.highPct, basisKm = b.km, rides = b.rides,
        )
    }
    return BandsUiState(rows, rows.sumOf { it.km }, battery?.let { rows.sumOf { r -> r.km * r.restFraction } }, battery)
}

/** The band to show in the detail card: the picked one, else the current one, else the top band. */
fun BandsUiState.selected(picked: Int?): BandRow =
    rows.firstOrNull { it.lowPct == picked } ?: rows.firstOrNull { it.current } ?: rows.first()
