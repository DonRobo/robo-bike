package dev.donrobo.ebike.ride

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A route the rider named ("Arbeitsweg"); [shape] is the outline rides are compared with. */
data class RouteDef(val id: String, val name: String, val shape: RideShape) {
    companion object {
        /** One route per line: `id \t urlencoded name \t shape`. Unreadable lines are skipped. */
        fun encodeAll(defs: List<RouteDef>): String? =
            defs.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.id}\t${URLEncoder.encode(it.name, "UTF-8")}\t${it.shape.encode()}" }

        fun decodeAll(s: String): List<RouteDef> = s.lines().mapNotNull { l ->
            val f = l.split('\t')
            if (f.size != 3) return@mapNotNull null
            val shape = RideShape.decode(f[2]) ?: return@mapNotNull null
            runCatching { RouteDef(f[0], URLDecoder.decode(f[1], "UTF-8"), shape) }.getOrNull()
        }
    }
}

/**
 * A recurring route and its rides (newest first). [id] is stable while the route exists: `n-<def id>`
 * for a named route, `c-<ride id>` (its oldest ride) for one recognised but not named yet.
 */
data class RecurringRoute(val id: String, val def: RouteDef?, val rides: List<RideSummary>) {
    val name: String? get() = def?.name
    val last: RideSummary get() = rides.first()
    /** Typical length: median odometer distance of its rides. */
    val distanceKm: Double get() = rides.map { it.distanceKm }.sorted()[rides.size / 2]
    val avgDurationS: Double get() = rides.map { it.durationS }.average()
    val avgBatteryPct: Double get() = rides.map { it.batteryUsedPct }.average()
    /** The outline new names are stored with: the oldest ride's. */
    val seedShape: RideShape? get() = def?.shape ?: rides.last().shape
}

/** Result of [RouteMatcher.group]: routes (most rides first) and the route of each ride id. */
data class RouteGroups(val routes: List<RecurringRoute>, val byRide: Map<String, RecurringRoute>) {
    fun route(id: String) = routes.firstOrNull { it.id == id }

    companion object {
        val EMPTY = RouteGroups(emptyList(), emptyMap())
    }
}

/**
 * Recognises recurring routes (F5) from the rides' GPS outlines ([RideShape]).
 *
 * Two rides are on the same route when all of these hold:
 * 1. **Same ends**: start near start and end near end, *or* start near end and end near start (the way
 *    back counts as the same route), each within [END_RADIUS_M].
 * 2. **Similar length**: odometer distances differ by at most the factor [MAX_LENGTH_RATIO]. The
 *    odometer is used, not the GPS path, because it is not affected by GPS noise.
 * 3. **Similar course**: at least [MIN_COVERAGE] of each outline's points lie within [CORRIDOR_M] of
 *    the other outline (distance to the polyline, not to the point with the same index). Being
 *    direction-free, this also works for the way back; the corridor is several times typical GPS error,
 *    and the coverage threshold lets a small detour (up to ~20 % of the way) still count, while a
 *    different road for a longer part of the way does not.
 *
 * Rides without a GPS outline are never matched; there is nothing to compare, so no guessing.
 * Grouping: each ride first joins the named route it matches best; the others are clustered greedily,
 * oldest first, each joining the best-matching cluster seed or starting a new cluster. A cluster
 * becomes a recurring route at [MIN_RIDES] rides; named routes are shown with any number of rides.
 * The cheap checks (ends, length) run first, so years of rides stay fast.
 */
object RouteMatcher {
    const val END_RADIUS_M = 300.0
    const val MAX_LENGTH_RATIO = 1.3
    const val CORRIDOR_M = 150.0
    const val MIN_COVERAGE = 0.8
    const val MIN_RIDES = 2

    /** Mean outline distance in metres if [a] and [b] are on the same route, else null. */
    fun match(a: RideSummary, b: RideSummary): Double? {
        val sa = a.shape ?: return null
        val sb = b.shape ?: return null
        return match(sa, a.distanceKm, sb, b.distanceKm)
    }

    fun match(a: RideShape, aKm: Double, b: RideShape, bKm: Double): Double? {
        val forward = a.start.distanceM(b.start) <= END_RADIUS_M && a.end.distanceM(b.end) <= END_RADIUS_M
        val reverse = a.start.distanceM(b.end) <= END_RADIUS_M && a.end.distanceM(b.start) <= END_RADIUS_M
        if (!forward && !reverse) return null
        if (aKm > 0 && bKm > 0 && max(aKm, bKm) / min(aKm, bKm) > MAX_LENGTH_RATIO) return null
        val ab = distances(a, b)
        val ba = distances(b, a)
        if (coverage(ab) < MIN_COVERAGE || coverage(ba) < MIN_COVERAGE) return null
        return (ab.sum() + ba.sum()) / (ab.size + ba.size)
    }

    /** Matching against a named route uses the route's own outline and leaves the length check out. */
    private fun matchDef(r: RideSummary, d: RouteDef): Double? = r.shape?.let { match(it, 0.0, d.shape, 0.0) }

    fun group(rides: List<RideSummary>, defs: List<RouteDef>): RouteGroups {
        val named = LinkedHashMap<String, MutableList<RideSummary>>()
        defs.forEach { named[it.id] = mutableListOf() }
        class Cluster(val seed: RideSummary) { val rides = mutableListOf(seed) }
        val clusters = ArrayList<Cluster>()
        for (r in rides.sortedBy { it.startMs }) {
            if (r.shape == null) continue
            val def = defs.mapNotNull { d -> matchDef(r, d)?.let { d to it } }.minByOrNull { it.second }?.first
            if (def != null) { named.getValue(def.id) += r; continue }
            val c = clusters.mapNotNull { c -> match(r, c.seed)?.let { c to it } }.minByOrNull { it.second }?.first
            if (c != null) c.rides += r else clusters += Cluster(r)
        }
        val routes = defs.mapNotNull { d ->
            named.getValue(d.id).takeIf { it.isNotEmpty() }?.let { RecurringRoute("n-${d.id}", d, it.sortedByDescending { r -> r.startMs }) }
        } + clusters.filter { it.rides.size >= MIN_RIDES }.map {
            RecurringRoute("c-${it.seed.id}", null, it.rides.sortedByDescending { r -> r.startMs })
        }
        val sorted = routes.sortedWith(compareByDescending<RecurringRoute> { it.rides.size }.thenByDescending { it.last.startMs })
        return RouteGroups(sorted, sorted.flatMap { route -> route.rides.map { it.id to route } }.toMap())
    }

    /** For each point of [a]: distance in metres to the polyline [b]. */
    private fun distances(a: RideShape, b: RideShape): DoubleArray {
        val lat0 = a.start.lat
        val kx = M_PER_DEG * cos(Math.toRadians(lat0)); val ky = M_PER_DEG
        val bx = DoubleArray(b.points.size) { b.points[it].lon * kx }
        val by = DoubleArray(b.points.size) { b.points[it].lat * ky }
        return DoubleArray(a.points.size) { i ->
            val px = a.points[i].lon * kx; val py = a.points[i].lat * ky
            var best = Double.MAX_VALUE
            for (j in 1 until bx.size) best = min(best, segment(px, py, bx[j - 1], by[j - 1], bx[j], by[j]))
            best
        }
    }

    private fun segment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx - px; val y = ay + t * dy - py
        return sqrt(x * x + y * y)
    }

    private fun coverage(d: DoubleArray) = d.count { it <= CORRIDOR_M }.toDouble() / d.size

    private const val M_PER_DEG = 111_320.0
}
