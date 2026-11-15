package dev.donrobo.ebike.ride

import dev.donrobo.ebike.location.GeoPoint
import kotlin.math.roundToLong

/**
 * Compact outline of a ride's GPS track: [POINTS] positions evenly spaced along the travelled path,
 * first = start, last = end. Small enough to keep in the [RideIndex] for every ride, so the list
 * thumbnails and the recurring-route recognition ([RouteMatcher]) never parse whole tracks.
 */
data class RideShape(val points: List<GeoPoint>) {
    val start: GeoPoint get() = points.first()
    val end: GeoPoint get() = points.last()

    fun encode(): String = points.joinToString(";") { "${it.lat},${it.lon}" }

    companion object {
        const val POINTS = 32
        /** Tracks shorter than this (metres of GPS path) have no meaningful shape. */
        const val MIN_LENGTH_M = 150.0
        /**
         * A position further from the previous kept one than this speed allows (m/s, ~108 km/h) is a GPS
         * outlier and dropped, so a single bad fix does not bend the outline.
         */
        const val MAX_SPEED_MS = 30.0

        fun decode(s: String): RideShape? = runCatching {
            val pts = s.split(';').map { p -> p.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
            if (pts.size >= 2) RideShape(pts) else null
        }.getOrNull()

        /**
         * Shape of [samples] (null without a usable track). Only moving samples count, so GPS jitter
         * while standing does not add path length; outliers are dropped (see [MAX_SPEED_MS]).
         * Coordinates are rounded to 1e-5° (~1 m) so a cached shape equals a freshly computed one.
         */
        fun of(samples: List<RideSample>): RideShape? {
            val kept = ArrayList<Pair<Long, GeoPoint>>()
            for (s in samples) {
                if (s.lat == null || s.lon == null || s.speedKmh <= 0) continue
                val p = GeoPoint(s.lat, s.lon)
                val last = kept.lastOrNull()
                if (last != null) {
                    val dt = (s.epochMs - last.first).coerceAtLeast(1000) / 1000.0
                    if (last.second.distanceM(p) > MAX_SPEED_MS * dt) continue
                }
                kept += s.epochMs to p
            }
            if (kept.size < 2) return null
            val pts = kept.map { it.second }
            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceM(pts[i])
            val total = cum.last()
            if (total < MIN_LENGTH_M) return null
            val out = ArrayList<GeoPoint>(POINTS)
            var j = 1
            for (k in 0 until POINTS) {
                val d = total * k / (POINTS - 1)
                while (j < pts.size - 1 && cum[j] < d) j++
                val seg = cum[j] - cum[j - 1]
                val f = if (seg <= 0) 0.0 else ((d - cum[j - 1]) / seg).coerceIn(0.0, 1.0)
                val a = pts[j - 1]; val b = pts[j]
                out += GeoPoint(round5(a.lat + (b.lat - a.lat) * f), round5(a.lon + (b.lon - a.lon) * f))
            }
            return RideShape(out)
        }

        private fun round5(v: Double) = (v * 1e5).roundToLong() / 1e5
    }
}
