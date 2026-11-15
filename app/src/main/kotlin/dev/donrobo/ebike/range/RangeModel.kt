package dev.donrobo.ebike.range

import dev.donrobo.ebike.ride.RideSummary
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** How much a per-level number can be trusted (R3). */
enum class Confidence {
    /** Enough of this level's own data. */
    LEARNED,
    /** A little data: pulled towards the overall consumption, shown as an approximation. */
    ESTIMATED,
    /** No usable data for this level. */
    UNKNOWN,
}

/** Why a ride does or does not count for learning. */
enum class RideUse {
    OK,
    /** Shorter than [RangeModel.MIN_RIDE_KM]: one battery step would dominate it. */
    TOO_SHORT,
    /** No plausible battery reading in the whole ride. */
    NO_BATTERY,
    /** Consumption far above anything an e-bike does (e.g. the display jumped down). */
    IMPLAUSIBLE,
}

/**
 * Learned consumption of one assist level. [km] = distance ridden in the level, [pct] = the battery use the
 * model attributes to it ([pctPerKm] x [km], 0 when unknown); [pctPerKm] is null for [Confidence.UNKNOWN].
 */
data class LevelRate(val level: Int, val km: Double, val pct: Double, val pctPerKm: Double?, val confidence: Confidence)

/** Consumption learned from the usable rides of one calendar month (R6); null = not enough data that month. */
data class MonthRate(val month: YearMonth, val pctPerKm: Double?)

/**
 * How far one display percent in the battery band ([lowPct], [highPct]] goes, relative to the average percent:
 * [factor] 1 = as far as the overall consumption says, 2 = twice as far. [pct] = the band's own data (effective
 * display percent observed in it, see [RangeModel.bands]); [factor] is 1 for [Confidence.UNKNOWN].
 */
data class BandRate(val lowPct: Int, val highPct: Int, val pct: Double, val factor: Double, val confidence: Confidence)

/**
 * The learned part of the range model of one bike. Recomputed only when the ride list or the model
 * settings change, never per live report.
 *
 * @param ridesUsed usable rides that went into [pctPerKm] (at most the configured window).
 * @param km, pct raw totals of those rides (for the "still learning" state).
 * @param pctPerKm battery % per km over all levels (the rider's mix); null = not learned yet.
 * @param levels assist levels 1.. seen in the data (level 0 = no assist is left out).
 * @param months the last six calendar months, oldest first.
 * @param bands the battery's discharge curve in [RangeModel.BAND_PCT] wide bands, 0 up to 100 %; empty =
 *   linear (every percent worth the same, e.g. nothing learned yet).
 */
data class LearnedRange(
    val ridesUsed: Int,
    val km: Double,
    val pct: Double,
    val pctPerKm: Double?,
    val levels: List<LevelRate>,
    val months: List<MonthRate>,
    val bands: List<BandRate> = emptyList(),
) {
    val ready: Boolean get() = pctPerKm != null

    fun level(level: Int): LevelRate? = levels.firstOrNull { it.level == level }

    /** Relative worth of the display percent from [pct] - 1 to [pct] (1..100), see [BandRate]. */
    fun factor(pct: Int): Double = bands.firstOrNull { pct > it.lowPct && pct <= it.highPct }?.factor ?: 1.0

    /**
     * The battery from 0 to [batteryPct] in "average percent", the sum of [factor] over its percents:
     * distance = this / consumption in % per km. Equals [batteryPct] for a linear battery.
     */
    fun averagePct(batteryPct: Int): Double = (1..batteryPct.coerceIn(0, 100)).sumOf { factor(it) }

    companion object {
        val EMPTY = LearnedRange(0, 0.0, 0.0, null, emptyList(), emptyList())
    }
}

/**
 * Learns battery % per km from recorded rides (A1: the display only reports whole battery percent, derived
 * from the sagging pack voltage, see [dev.donrobo.ebike.battery.BatteryReadings]).
 *
 * Method:
 * - The only measurement is a ride's total use, [RideSummary.batteryUsedPct] (rest level before minus rest
 *   level after). Every ride with a plausible battery and enough distance counts ([use]); a rise of the
 *   reading within a ride is recovery from sag, already absorbed by the rest levels, not a reason to drop
 *   the ride (a ride file never spans a charge).
 * - The newest [window] usable rides form the basis (R7).
 * - Consumption is a ratio of sums (battery % / km), so long rides weigh more than short ones: a ride's
 *   total is uncertain by about one display step whatever its length. One ride's weight is capped at
 *   [CAP_KM] km so a single long tour does not drown the everyday rides.
 * - With enough rides, rides consuming more than [OUTLIER_FACTOR] times the median rate are dropped.
 *   Rides with very low consumption stay: riding without assist is real.
 * - Per level (R3): single percent steps are mostly sag noise, so they are not attributed to levels.
 *   Instead each ride's total is explained by the distance it spent in each level,
 *   `used = sum over levels of rate(level) x km(level)`, and the rates are fitted over all rides by
 *   weighted least squares (weight = ride weight / ride km, which makes a one-level data set give exactly
 *   the ratio of sums). Rides in different mixes of levels tell the levels apart. A ridge term of
 *   [SHRINK_KM] km of pseudo-data at the overall rate keeps levels with little or always-mixed data close
 *   to the mix instead of letting them run off. A level is LEARNED when its own information (the
 *   effective km that pin it, at least [LEVEL_LEARNED_KM]) and its use over that ([LEVEL_LEARNED_PCT]) are
 *   large enough, else ESTIMATED; below [LEVEL_MIN_KM] km ridden it is UNKNOWN.
 * - Per battery level: the display percent is not worth the same distance everywhere (voltage-derived, not
 *   linear in the charge), so a discharge curve is fitted from the same ride totals ([bands]). The rates
 *   above are the average over the percents the rides used; the curve says how far each percent goes
 *   relative to that average. Range at p % = sum over 1..p of the curve / rate ([LearnedRange.averagePct]).
 */
object RangeModel {
    /** Rides shorter than this do not count: one display step would dominate them. */
    const val MIN_RIDE_KM = 1.0
    /** More consumption than this is not an e-bike ride (the display jumped). */
    const val MAX_PCT_PER_KM = 10.0
    /** One ride counts at most like a ride of this length. */
    const val CAP_KM = 25.0
    /** Before this much distance and battery use nothing is published (first rides). */
    const val READY_KM = 8.0
    const val READY_PCT = 8.0
    /** A level is learned from its own data from here on. */
    const val LEVEL_LEARNED_KM = 5.0
    const val LEVEL_LEARNED_PCT = 5.0
    /** Below this a level is unknown. */
    const val LEVEL_MIN_KM = 1.0
    /** Pseudo-distance at the overall rate added to every level (ridge term of the per-level fit). */
    const val SHRINK_KM = 2.0
    /** Outlier rule: only with this many rides of at least [OUTLIER_MIN_KM] km. */
    const val OUTLIER_MIN_RIDES = 5
    const val OUTLIER_MIN_KM = 3.0
    const val OUTLIER_FACTOR = 3.0
    /** A month needs this much to give an R6 value. */
    const val MONTH_KM = 15.0
    const val MONTH_PCT = 10.0
    const val MONTHS = 6
    /** Width of one battery band of the discharge curve, in display percent. */
    const val BAND_PCT = 10
    /** Pseudo-data per band at factor 1 (ridge term of the band fit), in display percent. */
    const val BAND_SHRINK_PCT = 10.0
    /** A band is learned from its own data from this many effective display percent on. */
    const val BAND_LEARNED_PCT = 10.0
    /** A band's factor stays within these bounds, whatever a few odd rides say. */
    const val BAND_FACTOR_MIN = 1 / 3.0
    const val BAND_FACTOR_MAX = 3.0

    /** Allowed values of the window setting (R7). */
    val WINDOWS = listOf(10, 20, 50)

    fun use(r: RideSummary): RideUse = when {
        r.batteryStartPct <= 0 || r.batteryStartPct > 100 -> RideUse.NO_BATTERY
        r.distanceKm < MIN_RIDE_KM -> RideUse.TOO_SHORT
        r.batteryUsedPct / r.distanceKm > MAX_PCT_PER_KM -> RideUse.IMPLAUSIBLE
        else -> RideUse.OK
    }

    private fun weight(r: RideSummary) = if (r.distanceKm <= CAP_KM) 1.0 else CAP_KM / r.distanceKm

    /** Weighted battery % per km of [rides]; null without distance. */
    private fun rate(rides: List<RideSummary>): Double? {
        val km = rides.sumOf { weight(it) * it.distanceKm }
        return if (km <= 0) null else rides.sumOf { weight(it) * it.batteryUsedPct } / km
    }

    /** Drops rides consuming far more than the median, once there are enough rides to tell. */
    internal fun withoutOutliers(rides: List<RideSummary>): List<RideSummary> {
        val rated = rides.filter { it.distanceKm >= OUTLIER_MIN_KM }
        if (rated.size < OUTLIER_MIN_RIDES) return rides
        val rates = rated.map { it.batteryUsedPct / it.distanceKm }.sorted()
        val median = rates[rates.size / 2].let { if (rates.size % 2 == 0) (it + rates[rates.size / 2 - 1]) / 2 else it }
        if (median <= 0) return rides
        return rides.filterNot { it.distanceKm >= OUTLIER_MIN_KM && it.batteryUsedPct / it.distanceKm > median * OUTLIER_FACTOR }
    }

    /**
     * @param rides newest first, as [dev.donrobo.ebike.ride.RideRepository.summaries] delivers them; a ride still
     *   being recorded must already be left out by the caller.
     * @param window how many of the newest usable rides count (R7).
     */
    fun learn(rides: List<RideSummary>, window: Int, today: YearMonth, zone: ZoneId): LearnedRange {
        val usable = rides.filter { use(it) == RideUse.OK }
        val basis = withoutOutliers(usable.take(window.coerceAtLeast(1)))
        val km = basis.sumOf { it.distanceKm }
        val pct = basis.sumOf { it.batteryUsedPct }
        val overall = if (km >= READY_KM && pct >= READY_PCT) rate(basis) else null

        val byMonth = usable.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) }
        val months = (MONTHS - 1 downTo 0).map { back ->
            val m = today.minusMonths(back.toLong())
            val list = withoutOutliers(byMonth[m].orEmpty())
            val ok = list.sumOf { it.distanceKm } >= MONTH_KM && list.sumOf { it.batteryUsedPct } >= MONTH_PCT
            MonthRate(m, if (ok) rate(list) else null)
        }
        // level 0 (no assist) takes part in the fit, so its distance explains its (small) share of the use,
        // but it is not listed: the display's levels are 1..n
        val ids = basis.flatMap { r -> r.levels.filter { it.distanceKm > 0 }.map { it.level } }.distinct().sorted()
        val rates = overall?.let { fit(basis, ids, it) }
        return LearnedRange(basis.size, km, pct, overall, levels(basis, ids, rates), months, bands(basis, overall, rates))
    }

    /** Display percents q (the step from q - 1 to q) a ride used that lie in the band ([low], [low] + [BAND_PCT]]. */
    private fun covered(r: RideSummary, low: Int): Int =
        (minOf(r.batteryStartPct, low + BAND_PCT) - maxOf(r.batteryEndPct, low)).coerceAtLeast(0)

    /**
     * The discharge curve: how far one display percent goes in each battery band, relative to the average.
     * The display's percent is a function of the pack voltage, which is not linear in the charge: on the
     * owner's previous bike 99 -> 98 % lasted several km, 4 -> 3 % a few hundred metres.
     *
     * Like the per-level fit this uses only rest-to-rest ride totals (single steps are sag noise). A ride used
     * the display percents from its end to its start rest level; the consumption the level fit predicts for it
     * on a linear battery, `P_i = sum over levels of rate(level) x km(level)`, must equal the sum of the
     * factors of those percents, `P_i = sum_b c_ib f_b` (c_ib = its percents in band b). For a linear battery
     * every f_b is 1. Rides covering different bands tell the bands apart. Weighted least squares, weight = ride
     * weight / percents used (one band gives the ratio of sums), plus a ridge of [BAND_SHRINK_PCT] percent of
     * pseudo-data at factor 1 per band: bands never visited stay at 1 (the overall consumption), rarely
     * visited ones move only part of the way, and [BAND_FACTOR_MIN]..[BAND_FACTOR_MAX] bound the rest. A band
     * is LEARNED from [BAND_LEARNED_PCT] effective percent of its own on, UNKNOWN without any. Empty while
     * nothing is learned (= linear).
     */
    internal fun bands(basis: List<RideSummary>, overall: Double?, rates: Map<Int, Double>?): List<BandRate> {
        if (overall == null) return emptyList()
        val lows = (0 until 100 step BAND_PCT).toList()
        val n = lows.size
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        val info = DoubleArray(n)
        val seen = DoubleArray(n)
        for (r in basis) {
            val used = r.batteryUsedPct
            if (used < 1) continue
            val c = DoubleArray(n) { covered(r, lows[it]).toDouble() }
            val v = weight(r) / used
            val p = predictedPct(r, overall, rates)
            for (i in 0 until n) {
                b[i] += v * c[i] * p
                info[i] += v * c[i] * c[i]
                seen[i] += c[i]
                for (j in 0 until n) a[i][j] += v * c[i] * c[j]
            }
        }
        for (i in 0 until n) { a[i][i] += BAND_SHRINK_PCT; b[i] += BAND_SHRINK_PCT }
        val f = solve(a, b)
        return lows.indices.map { i ->
            val confidence = when {
                seen[i] <= 0 -> Confidence.UNKNOWN
                info[i] >= BAND_LEARNED_PCT -> Confidence.LEARNED
                else -> Confidence.ESTIMATED
            }
            val factor = if (confidence == Confidence.UNKNOWN) 1.0 else f[i].coerceIn(BAND_FACTOR_MIN, BAND_FACTOR_MAX)
            BandRate(lows[i], lows[i] + BAND_PCT, info[i], factor, confidence)
        }
    }

    /**
     * Battery % the ride would have used on a linear battery at the learned rates: its distance per level times
     * that level's rate (scaled to the ride's distance), or the overall rate without a level breakdown.
     */
    private fun predictedPct(r: RideSummary, overall: Double, rates: Map<Int, Double>?): Double {
        val total = rideKm(r)
        if (rates == null || total <= 0) return overall * r.distanceKm
        return r.levels.sumOf { (rates[it.level] ?: overall) * it.distanceKm } * r.distanceKm / total
    }

    /** Per-level rates (R3) from the regularised least-squares fit described at [RangeModel]. */
    private fun levels(basis: List<RideSummary>, ids: List<Int>, rates: Map<Int, Double>?): List<LevelRate> {
        return ids.filter { it > 0 }.map { l ->
            val km = basis.sumOf { levelKm(it, l) }
            // effective km that pin this level: a ride entirely in it counts fully, a 50/50 ride a quarter
            val info = basis.sumOf { r -> rideKm(r).takeIf { it > 0 }?.let { weight(r) * levelKm(r, l) * levelKm(r, l) / it } ?: 0.0 }
            val rate = rates?.get(l)
            when {
                rate == null || km < LEVEL_MIN_KM -> LevelRate(l, km, 0.0, null, Confidence.UNKNOWN)
                info >= LEVEL_LEARNED_KM && rate * info >= LEVEL_LEARNED_PCT -> LevelRate(l, km, rate * km, rate, Confidence.LEARNED)
                else -> LevelRate(l, km, rate * km, rate, Confidence.ESTIMATED)
            }
        }
    }

    private fun levelKm(r: RideSummary, level: Int) = r.levels.filter { it.level == level }.sumOf { it.distanceKm }

    /** The ride's distance as split over its levels (the same odometer as [RideSummary.distanceKm]). */
    private fun rideKm(r: RideSummary) = r.levels.sumOf { it.distanceKm }

    /**
     * Minimises `sum_i w_i (used_i - sum_l rate_l km_il)^2 / km_i + SHRINK_KM sum_l (rate_l - overall)^2`
     * (w_i = ride weight). Normal equations: (A + S I) rate = b + S overall with A_lm = sum_i w_i km_il km_im / km_i
     * and b_l = sum_i w_i used_i km_il / km_i. With a single level this is exactly the weighted ratio of sums.
     * Rates are kept non-negative.
     */
    private fun fit(basis: List<RideSummary>, ids: List<Int>, overall: Double): Map<Int, Double> {
        val n = ids.size
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (r in basis) {
            val total = rideKm(r)
            if (total <= 0) continue
            val k = DoubleArray(n) { levelKm(r, ids[it]) }
            val w = weight(r) / total
            for (i in 0 until n) {
                b[i] += w * r.batteryUsedPct * k[i]
                for (j in 0 until n) a[i][j] += w * k[i] * k[j]
            }
        }
        for (i in 0 until n) { a[i][i] += SHRINK_KM; b[i] += SHRINK_KM * overall }
        val x = solve(a, b)
        return ids.indices.associate { ids[it] to x[it].coerceAtLeast(0.0) }
    }

    /** Gaussian elimination with partial pivoting; the ridge term keeps [a] positive definite. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(a[it][c]) }
            if (p != c) { val t = a[p]; a[p] = a[c]; a[c] = t; val u = b[p]; b[p] = b[c]; b[c] = u }
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (b[r] - (r + 1 until n).sumOf { a[r][it] * x[it] }) / a[r][r]
        return x
    }
}
