package dev.donrobo.ebike.ui.rides

import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.LocationPermissions
import dev.donrobo.ebike.ride.RecurringRoute
import dev.donrobo.ebike.ride.RideSample
import dev.donrobo.ebike.ride.RideStats
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.ride.StatsBucket
import dev.donrobo.ebike.ride.StatsPeriod
import dev.donrobo.ebike.ui.components.chart.ChartPoint
import dev.donrobo.ebike.ui.components.chart.ChartScale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One ride in the Fahrten list. [route] is set when the ride is on a recurring route (named or not). */
data class RideItem(val ride: RideSummary, val route: RecurringRoute?, val recording: Boolean)

data class DayGroup(val date: LocalDate, val items: List<RideItem>)

/** Fahrten list: this week's totals, the GPS track switch and the rides grouped by day (newest first). */
data class RidesUiState(
    val week: StatsBucket,
    val gpsTrack: Boolean,
    /** Why GPS will not deliver a track (shown only while [gpsTrack] is on). */
    val gpsLimit: LocationLimit?,
    val days: List<DayGroup>,
    /** False until the ride list has been read once (no "no rides" flash on open). */
    val loaded: Boolean = true,
)

/**
 * Pure mapping for the Fahrten list. [recording] = a ride is being recorded now; it is the newest ride.
 * The week counts rides started this calendar week (Monday first, local time).
 */
fun ridesUiState(
    rides: List<RideSummary>, groups: RouteGroups, gpsTrack: Boolean, gpsLimit: LocationLimit?, recording: Boolean,
    today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), loaded: Boolean = true,
): RidesUiState {
    val newest = rides.maxByOrNull { it.startMs }
    val days = rides.sortedByDescending { it.startMs }
        .groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        .map { (d, list) -> DayGroup(d, list.map { RideItem(it, groups.byRide[it.id], recording && it === newest) }) }
    return RidesUiState(RideStats.aggregate(rides, StatsPeriod.WEEK, today, zone).total, gpsTrack, if (gpsTrack) gpsLimit else null, days, loaded)
}

/**
 * What keeps GPS from recording a track, for the hint under the switch. The provider's own [statusLimit]
 * wins (it knows whether the system location is off and how the service was started); without it the
 * permissions decide: none, only approximate, or no background access (then a track is only recorded
 * when the app was open at ride start, owner decision E2).
 */
fun trackLimit(statusLimit: LocationLimit?, permissions: LocationPermissions): LocationLimit? = statusLimit ?: when {
    !permissions.any -> LocationLimit.NO_PERMISSION
    !permissions.fine -> LocationLimit.TRACK_NEEDS_PRECISE
    !permissions.background -> LocationLimit.NEEDS_BACKGROUND
    else -> null
}

/** Average speed in km/h over [RideSummary.durationS] (as the Fahrtdetail tiles: distance / duration). */
fun RideSummary.avgSpeedKmh(): Double? = if (durationS >= 30 && distanceKm > 0) distanceKm / (durationS / 3600.0) else null

/** Battery % per km; null for very short rides where it is meaningless. */
fun RideSummary.pctPerKm(): Double? = if (distanceKm >= 0.5) batteryUsedPct / distanceKm else null

/** Chart data of one ride over distance (F4/A3), in metric units; the UI converts for miles. */
data class RideCharts(
    val distanceKm: Double,
    val speed: List<ChartPoint>,
    val battery: List<ChartPoint>,
    /** Null when no sample has an altitude (no GPS track, or a file from before altitude was recorded). */
    val altitude: List<ChartPoint>?,
    val level: List<ChartPoint>,
    val maxLevel: Int,
    val track: List<GeoPoint>,
)

/** Pure: samples -> chart series (x = km from the ride start by odometer), downsampled for drawing. */
fun rideCharts(samples: List<RideSample>): RideCharts {
    if (samples.isEmpty()) return RideCharts(0.0, emptyList(), emptyList(), null, emptyList(), 0, emptyList())
    val odo0 = samples.first().odoKm
    fun x(s: RideSample) = (s.odoKm - odo0).coerceAtLeast(0.0).toFloat()
    val speed = samples.map { ChartPoint(x(it), it.speedKmh.toFloat()) }
    val battery = samples.map { ChartPoint(x(it), it.batteryPct.toFloat()) }
    val alt = samples.filter { it.altM != null }.map { ChartPoint(x(it), it.altM!!.toFloat()) }
    // levels: keep only the points where the level changes (plus the end), so steps stay exact
    val level = ArrayList<ChartPoint>()
    for ((i, s) in samples.withIndex()) {
        if (i == 0 || i == samples.lastIndex || s.gear != samples[i - 1].gear) level += ChartPoint(x(s), s.gear.toFloat())
    }
    return RideCharts(
        distanceKm = (samples.last().odoKm - odo0).coerceAtLeast(0.0),
        speed = ChartScale.downsample(speed),
        battery = ChartScale.downsample(battery),
        altitude = alt.takeIf { it.size >= 2 }?.let { ChartScale.downsample(it) },
        level = level,
        maxLevel = samples.maxOf { it.gear },
        track = samples.mapNotNull { s -> if (s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else null },
    )
}
