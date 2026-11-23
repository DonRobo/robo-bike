package dev.donrobo.ebike.ui.components.map

import androidx.compose.runtime.Immutable
import dev.donrobo.ebike.location.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Everything [BikeMap] draws on top of the base map. All parts are optional; the camera fits all of them.
 *
 * - [tracks]: ride tracks (F3), drawn as a casing + primary line with a hollow start and a filled end dot.
 * - [heat]: many tracks for the heatmap (F8); ways ridden more often are drawn wider and stronger.
 *   Build it once with [HeatLines.build] (off the main thread for many rides).
 * - [ring]: range ring (R4).
 * - [markers]: current position, home, parked bike.
 */
@Immutable
data class MapContent(
    val tracks: List<MapTrack> = emptyList(),
    val heat: HeatLines? = null,
    val ring: MapRing? = null,
    val markers: List<MapMarker> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.all { it.points.isEmpty() } && heat?.lines.isNullOrEmpty() && ring == null && markers.isEmpty()
}

/** One track. [showEnds] draws the start (hollow) and end (filled) dots. */
@Immutable
data class MapTrack(val points: List<GeoPoint>, val showEnds: Boolean = true)

/** A circle of [radiusM] metres around [center], e.g. the straight-line range there and back. */
@Immutable
data class MapRing(val center: GeoPoint, val radiusM: Double)

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
    private const val M_PER_DEG_LAT = 111_320.0

    /** Bounds of everything in [c] (a ring counts with its full circle); null if empty. */
    fun bounds(c: MapContent): GeoBounds? {
        val pts = ArrayList<GeoPoint>()
        c.tracks.forEach { pts += it.points }
        c.heat?.lines?.forEach { pts += it.points }
        c.markers.forEach { pts += it.position }
        c.ring?.let { r ->
            val dLat = r.radiusM / M_PER_DEG_LAT
            val dLon = dLat / cos(Math.toRadians(r.center.lat)).coerceAtLeast(0.01)
            pts += GeoPoint(r.center.lat - dLat, r.center.lon - dLon)
            pts += GeoPoint(r.center.lat + dLat, r.center.lon + dLon)
        }
        return GeoBounds.of(pts)
    }

    /** Closed polygon approximating a circle of [radiusM] around [center] (first point repeated last). */
    fun circle(center: GeoPoint, radiusM: Double, segments: Int = 72): List<GeoPoint> {
        val d = radiusM / GeoPoint.EARTH_RADIUS_M
        val lat1 = Math.toRadians(center.lat); val lon1 = Math.toRadians(center.lon)
        return (0..segments).map { i ->
            val brg = 2 * PI * (i % segments) / segments
            val lat2 = kotlin.math.asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
            val lon2 = lon1 + kotlin.math.atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
            GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
        }
    }

    /** Web Mercator, normalised to 0..1 (x east, y south). */
    fun mercX(lon: Double) = (lon + 180.0) / 360.0
    fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }

    /** Inverses of [mercX] and [mercY]. */
    fun lonOfMercX(x: Double) = x * 360.0 - 180.0
    fun latOfMercY(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

    /** MapLibre's world size at zoom 0, in logical pixels (512 px tiles). */
    const val TILE_SIZE = 512.0

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

        /** Geographic point at the centre of the box. */
        val center: GeoPoint get() = GeoPoint(latOfMercY(cy), lonOfMercX(cx))

        /**
         * MapLibre zoom that shows this projection's scale when one MapLibre logical pixel is [pxPerLogical]
         * of this projection's pixels (its world is [TILE_SIZE] logical px wide at zoom 0). With [center],
         * a MapLibre camera then puts every point exactly where [x]/[y] draw it.
         */
        fun zoom(pxPerLogical: Double = 1.0) = log2(scale / (TILE_SIZE * pxPerLogical))
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
