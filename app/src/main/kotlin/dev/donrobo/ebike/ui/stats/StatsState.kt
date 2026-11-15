package dev.donrobo.ebike.ui.stats

import dev.donrobo.ebike.ride.PeriodStats
import dev.donrobo.ebike.ride.RecurringRoute
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.ride.RideStats
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.ride.StatsPeriod
import dev.donrobo.ebike.stats.Cost
import dev.donrobo.ebike.stats.ElectricityCost
import dev.donrobo.ebike.ui.components.map.HeatLines
import dev.donrobo.ebike.location.GeoPoint
import java.time.LocalDate
import java.time.ZoneId

/**
 * Statistik: totals and bar chart of the selected [period] (F6), electricity cost of that period (F7;
 * [cost] null while the battery capacity is not set), recurring routes (F5) and the heatmap (F8).
 */
data class StatsUiState(
    val period: StatsPeriod,
    val stats: PeriodStats,
    val cost: Cost?,
    val priceCentPerKwh: Double,
    val routes: List<RecurringRoute>,
    /** Some ride has a GPS track (otherwise routes and heatmap explain how to get them). */
    val anyTrack: Boolean,
    /** Null while being computed (or when there are no tracks). */
    val heat: HeatLines?,
    /** Why there are no tracks yet (only meaningful while [anyTrack] is false). */
    val noTrackReason: NoTrackReason = NoTrackReason.RECORDING_OFF,
)

/** Why routes and heatmap have nothing to show: GPS recording off, or on but no ride has positions yet. */
enum class NoTrackReason { RECORDING_OFF, NO_POSITIONS }

/** How many recurring routes the card shows (most-ridden first). */
const val STATS_MAX_ROUTES = 3

fun statsUiState(
    rides: List<RideSummary>, groups: RouteGroups, period: StatsPeriod, today: LocalDate,
    capacityWh: Int, priceCentPerKwh: Double, heat: HeatLines?, zone: ZoneId = ZoneId.systemDefault(),
    gpsTrack: Boolean = false,
): StatsUiState {
    val stats = RideStats.aggregate(rides, period, today, zone)
    return StatsUiState(
        period, stats,
        ElectricityCost.of(stats.total.batteryPct, stats.total.distanceKm, capacityWh, priceCentPerKwh),
        priceCentPerKwh,
        groups.routes.take(STATS_MAX_ROUTES),
        rides.any { it.hasTrack },
        heat,
        if (gpsTrack) NoTrackReason.NO_POSITIONS else NoTrackReason.RECORDING_OFF,
    )
}

/**
 * Heatmap of all tracks of a bike, built off the main thread from the full tracks (the outlines are too
 * coarse for the 20 m grid). The last result is kept per bike and reused while the set of rides with a
 * track is unchanged, so switching between Liste and Statistik does not parse years of tracks again.
 */
object HeatCache {
    private data class Key(val bikeId: String, val rides: List<Pair<String, Long>>)
    @Volatile private var last: Pair<Key, HeatLines>? = null

    fun cached(bikeId: String, rides: List<RideSummary>): HeatLines? = last?.takeIf { it.first == key(bikeId, rides) }?.second

    suspend fun get(bikeId: String, rides: List<RideSummary>): HeatLines? {
        val k = key(bikeId, rides)
        if (k.rides.isEmpty()) return null
        last?.let { if (it.first == k) return it.second }
        val tracks = RideRepository.tracks(rides).map { t -> t.map { GeoPoint(it.lat!!, it.lon!!) } }
        val heat = HeatLines.build(tracks)
        last = k to heat
        return heat
    }

    private fun key(bikeId: String, rides: List<RideSummary>) = Key(bikeId, rides.filter { it.hasTrack }.map { it.id to it.endMs })
}
