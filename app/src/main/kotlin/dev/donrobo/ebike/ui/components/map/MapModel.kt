package dev.donrobo.ebike.ui.components.map

import androidx.compose.runtime.Immutable
import dev.donrobo.ebike.location.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Everything [BikeMap] draws on top of the base map. All parts are optional; the camera fits all of them.
 *
 * - [tracks]: ride tracks (F3), drawn as a casing + primary line with a hollow start and a filled end dot.
 * - [heat]: many tracks for the heatmap (F8); ways ridden more often are drawn wider and stronger.
 *   Build it once with [HeatLines.build] (off the main thread for many rides).
 * - [area]: a filled, outlined area, e.g. the reachable area (R4, [MapGeometry.ellipse]).
 * - [guide]: a dotted line, e.g. one example way there and home across [area].
 * - [markers]: current position, home, parked bike.
 */
@Immutable
data class MapContent(
    val tracks: List<MapTrack> = emptyList(),
    val heat: HeatLines? = null,
    val area: MapArea? = null,
    val guide: List<GeoPoint> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.all { it.points.isEmpty() } && heat?.lines.isNullOrEmpty() && area == null && guide.isEmpty() && markers.isEmpty()
}

/** One track. [showEnds] draws the start (hollow) and end (filled) dots. */
@Immutable
data class MapTrack(val points: List<GeoPoint>, val showEnds: Boolean = true)

/** A closed polygon (first point repeated last), drawn filled with an outline. */
@Immutable
data class MapArea(val points: List<GeoPoint>)

enum class MarkerKind {
    /** Where the phone is now (filled dot). */
    CURRENT,
    /** Home location (house in a circle). */
    HOME,
    /** Where the bike was parked (pin, anchored at its tip). */
    PARKED,
    /** Track start / end dots; used by [MapTrack], not meant to be placed directly. */
    TRACK_START, TRACK_END,
}

@Immutable
data class MapMarker(val position: GeoPoint, val kind: MarkerKind)

/** Latitude/longitude box. */
@Immutable
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val isPoint: Boolean get() = north - south < 1e-7 && east - west < 1e-7
    val center: GeoPoint get() = GeoPoint((south + north) / 2, (west + east) / 2)

    companion object {
        fun of(points: Iterable<GeoPoint>): GeoBounds? {
            var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0; var any = false
            for (p in points) { any = true; s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
            return if (any) GeoBounds(s, w, n, e) else null
        }
    }
}

/** Pure map geometry (unit-tested; shared by the MapLibre view and the stand-in). */
object MapGeometry {
    /** Bounds of everything in [c]; null if empty. */
    fun bounds(c: MapContent): GeoBounds? {
        val pts = ArrayList<GeoPoint>()
        c.tracks.forEach { pts += it.points }
        c.heat?.lines?.forEach { pts += it.points }
        c.markers.forEach { pts += it.position }
        c.area?.let { pts += it.points }
        pts += c.guide
        return GeoBounds.of(pts)
    }

    /**
     * Closed polygon (first point repeated last) of the ellipse with foci [a] and [b] whose points P have
     * |aP| + |Pb| = [sumM] (straight line); a circle of [sumM] / 2 around [a] when the foci coincide (e.g. the bike at home). Built from
     * the focal polar form r(θ) = (A² − C²) / (A − C·cos(θ − φ)) around [a] (A = sumM / 2, C = |ab| / 2, φ = bearing
     * a→b) and walked out along great circles, accurate to well under 1 % at range scales. Null if [sumM] does not
     * exceed |ab| (no area).
     */
    fun ellipse(a: GeoPoint, b: GeoPoint, sumM: Double, segments: Int = 96): List<GeoPoint>? {
        val bigA = sumM / 2; val c = a.distanceM(b) / 2
        if (bigA <= c || bigA <= 0) return null
        val phi = if (c > 0) bearing(a, b) else 0.0
        return (0..segments).map { i ->
            val theta = 2 * PI * (i % segments) / segments
            destination(a, theta, (bigA * bigA - c * c) / (bigA - c * cos(theta)), phi)
        }
    }

    /**
     * An example way across the [ellipse]: [a] → the end of its minor axis → [b] (the minor-axis end lies at [a]
     * plus the half major axis, where θ − φ = acos(C / A)). For coincident foci: out and back along one radius.
     */
    fun ellipseGuide(a: GeoPoint, b: GeoPoint, sumM: Double): List<GeoPoint>? {
        val bigA = sumM / 2; val c = a.distanceM(b) / 2
        if (bigA <= c || bigA <= 0) return null
        val phi = if (c > 0) bearing(a, b) else 0.0
        val far = destination(a, kotlin.math.acos(c / bigA), bigA, phi)
        return listOf(a, far, b)
    }

    /** Initial great-circle bearing from [p] to [q], radians clockwise from north. */
    private fun bearing(p: GeoPoint, q: GeoPoint): Double {
        val l1 = Math.toRadians(p.lat); val l2 = Math.toRadians(q.lat); val dl = Math.toRadians(q.lon - p.lon)
        return kotlin.math.atan2(sin(dl) * cos(l2), cos(l1) * sin(l2) - sin(l1) * cos(l2) * cos(dl))
    }

    /** The point [distM] from [p] at bearing [phi] + [theta] (radians). */
    private fun destination(p: GeoPoint, theta: Double, distM: Double, phi: Double): GeoPoint {
        val d = distM / GeoPoint.EARTH_RADIUS_M
        val brg = phi + theta
        val lat1 = Math.toRadians(p.lat); val lon1 = Math.toRadians(p.lon)
        val lat2 = kotlin.math.asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
        val lon2 = lon1 + kotlin.math.atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /** Web Mercator, normalised to 0..1 (x east, y south). */
    fun mercX(lon: Double) = (lon + 180.0) / 360.0
    fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }

    /**
     * Maps points into a [width] x [height] pixel box so that [b] fits inside [padding] px (uniform scale,
     * centred). A point-sized box is shown about 400 m wide.
     */
    class Projection(b: GeoBounds, private val width: Float, private val height: Float, padding: Float) {
        private val cx: Double
        private val cy: Double
        private val scale: Double // px per normalised mercator unit

        init {
            val x0 = mercX(b.west); val x1 = mercX(b.east)
            val y0 = mercY(b.north); val y1 = mercY(b.south)
            cx = (x0 + x1) / 2; cy = (y0 + y1) / 2
            val aw = (width - 2 * padding).coerceAtLeast(1f); val ah = (height - 2 * padding).coerceAtLeast(1f)
            val spanX = x1 - x0; val spanY = y1 - y0
            // ~400 m across for a single point (1 mercator unit = earth circumference at the equator)
            val minSpan = 400.0 / (2 * PI * GeoPoint.EARTH_RADIUS_M * cos(Math.toRadians(b.center.lat)).coerceAtLeast(0.01))
            scale = min(aw / max(spanX, minSpan), ah / max(spanY, minSpan))
        }

        fun x(p: GeoPoint) = (width / 2 + (mercX(p.lon) - cx) * scale).toFloat()
        fun y(p: GeoPoint) = (height / 2 + (mercY(p.lat) - cy) * scale).toFloat()
        /** Metres to pixels at latitude [lat]. */
        fun px(meters: Double, lat: Double) = (meters / (2 * PI * GeoPoint.EARTH_RADIUS_M * cos(Math.toRadians(lat))) * scale).toFloat()
    }

}

/**
 * Heatmap lines (F8): tracks snapped to a grid of [cellM] metres (drawn at the mean position within each cell); each grid edge counts the number of
 * distinct tracks that used it. Consecutive edges with the same count are merged into one polyline.
 * [weight] is 0..1 on a log scale of the count (1 = the most ridden way).
 */
@Immutable
class HeatLines(val lines: List<Line>, val maxCount: Int) {
    @Immutable
    data class Line(val points: List<GeoPoint>, val count: Int, val weight: Float)

    companion object {
        /** Jumps longer than this (GPS gap) are not drawn as a way. */
        const val MAX_JUMP_M = 250.0

        fun build(tracks: List<List<GeoPoint>>, cellM: Double = 20.0): HeatLines {
            val all = tracks.flatten()
            if (all.isEmpty()) return HeatLines(emptyList(), 0)
            val lat0 = all.sumOf { it.lat } / all.size
            val dLat = cellM / 111_320.0
            val dLon = dLat / cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
            fun cell(p: GeoPoint) = Cell(floor(p.lat / dLat).toInt(), floor(p.lon / dLon).toInt())
            // a cell is drawn at the mean of the points that fell into it, so diagonals do not become staircases
            val sums = HashMap<Cell, DoubleArray>()
            for (p in all) sums.getOrPut(cell(p)) { DoubleArray(3) }.let { it[0] += p.lat; it[1] += p.lon; it[2] += 1.0 }
            fun center(c: Cell) = sums.getValue(c).let { GeoPoint(it[0] / it[2], it[1] / it[2]) }

            // per track: cell paths (consecutive duplicates removed, split at GPS gaps)
            val perTrack: List<List<List<Cell>>> = tracks.map { t ->
                val runs = ArrayList<MutableList<Cell>>()
                var prev: GeoPoint? = null
                for (p in t) {
                    if (prev == null || prev.distanceM(p) > MAX_JUMP_M) { runs += mutableListOf<Cell>() }
                    val c = cell(p)
                    if (runs.last().lastOrNull() != c) runs.last() += c
                    prev = p
                }
                runs.filter { it.size >= 2 }
            }
            // each edge counts once per track (a track riding back and forth is still one ride)
            val counts = HashMap<Edge, Int>()
            for (paths in perTrack) {
                val set = HashSet<Edge>()
                for (path in paths) for (i in 1 until path.size) set += Edge.of(path[i - 1], path[i])
                set.forEach { counts.merge(it, 1, Int::plus) }
            }
            val paths = perTrack.flatten()
            val max = counts.values.maxOrNull() ?: 0
            fun weight(n: Int) = if (max <= 1) 1f else (ln(n.toDouble()) / ln(max.toDouble())).toFloat()

            val emitted = HashSet<Edge>()
            val lines = ArrayList<Line>()
            for (path in paths) {
                var cur: MutableList<GeoPoint>? = null
                var runCount = -1
                for (i in 1 until path.size) {
                    val e = Edge.of(path[i - 1], path[i])
                    val n = counts[e] ?: 0
                    if (!emitted.add(e)) { cur?.let { lines += Line(it, runCount, weight(runCount)) }; cur = null; continue }
                    if (cur == null || n != runCount) {
                        cur?.let { lines += Line(it, runCount, weight(runCount)) }
                        cur = mutableListOf(center(path[i - 1])); runCount = n
                    }
                    cur += center(path[i])
                }
                cur?.let { lines += Line(it, runCount, weight(runCount)) }
            }
            return HeatLines(lines, max)
        }

    }

    private data class Cell(val y: Int, val x: Int)
    private data class Edge(val a: Cell, val b: Cell) {
        companion object {
            fun of(p: Cell, q: Cell) = if (p.y < q.y || (p.y == q.y && p.x <= q.x)) Edge(p, q) else Edge(q, p)
        }
    }
}
