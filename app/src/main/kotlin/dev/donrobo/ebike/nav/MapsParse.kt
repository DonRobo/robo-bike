package dev.donrobo.ebike.nav

import dev.donrobo.ebike.proto.Icon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

data class NavState(val distM: Int, val icon: Icon, val remainingM: Long)

/** Text parsing for the Google Maps navigation notification. Pure Kotlin, unit-tested. */
object MapsText {
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)
    private val ARRIVE = Regex("""\b(ziel|ankunft erreicht|angekommen|arrive|destination)""", RegexOption.IGNORE_CASE)

    /** First distance in the text, in metres ("200 m", "1,2 km", "0.3 mi", "500 ft"). */
    fun distanceM(s: CharSequence?): Int? {
        val m = DIST.find(s ?: return null) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDouble()
        val f = when (m.groupValues[2].lowercase()) {
            "km" -> 1000.0; "mi" -> 1609.344; "ft" -> 0.3048; "yd" -> 0.9144; else -> 1.0
        }
        return (v * f).roundToInt()
    }

    fun isArrival(s: CharSequence?) = s != null && ARRIVE.containsMatchIn(s)
}

/**
 * Classifies a maneuver arrow from its shape: the stem starts at the bottom, the arrow head is the
 * point farthest away *along the shape*; the direction of the last quarter of that path is the turn.
 */
object ArrowClassifier {
    /** [mask] is row-major, true = arrow pixel. */
    fun classify(raw: BooleanArray, w: Int, h: Int): Icon {
        val total = raw.count { it }
        if (total == 0) return Icon.UNKNOWN
        val (icon, reached) = trace(raw, w, h)
        if (reached >= total * 0.8) return icon
        // Pieces not connected to the stem's base (Maps draws the first "head off" arrow with a
        // dashed stem): grow the shape by 2 px so it becomes one piece, then trace again.
        val grown = BooleanArray(w * h)
        for (i in raw.indices) if (raw[i]) {
            for (y in maxOf(0, i / w - 2)..minOf(h - 1, i / w + 2)) for (x in maxOf(0, i % w - 2)..minOf(w - 1, i % w + 2)) grown[y * w + x] = true
        }
        return trace(grown, w, h).first
    }

    /** Returns the icon and how many mask pixels are connected to the base. */
    private fun trace(mask: BooleanArray, w: Int, h: Int): Pair<Icon, Int> {
        val bottom = (h - 1 downTo 0).firstOrNull { y -> (0 until w).any { mask[y * w + it] } } ?: return Icon.UNKNOWN to 0
        val dist = IntArray(w * h) { -1 }
        val queue = ArrayDeque<Int>()
        for (y in maxOf(0, bottom - 1)..bottom) for (x in 0 until w) if (mask[y * w + x]) { dist[y * w + x] = 0; queue += y * w + x }
        var tip = queue.first()
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (dist[p] > dist[tip]) tip = p
            val px = p % w; val py = p / w
            // straight neighbours first so paths prefer straight steps
            for ((dx, dy) in NEIGHBOURS) {
                val x = px + dx; val y = py + dy
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val q = y * w + x
                if (mask[q] && dist[q] < 0) { dist[q] = dist[p] + 1; queue += q }
            }
        }
        val len = dist[tip]
        val reached = dist.count { it >= 0 }
        if (len < 4) return Icon.UNKNOWN to reached
        // head = centroid of the pixels within the last 10 % of the path length (averages out the barbs)
        var hx = 0.0; var hy = 0.0; var n = 0
        for (i in dist.indices) if (dist[i] >= len * 0.9) { hx += i % w; hy += i / w; n++ }
        hx /= n; hy /= n
        // where the path was shortly before the head: centroid of the band at 55-70 % of the path length
        var bx = 0.0; var by = 0.0; var m = 0
        for (i in dist.indices) if (dist[i] >= len * 0.55 && dist[i] <= len * 0.7) { bx += i % w; by += i / w; m++ }
        if (m == 0) return Icon.UNKNOWN to reached
        bx /= m; by /= m
        val angle = Math.toDegrees(atan2(hx - bx, -(hy - by)))  // 0 = up, + = right
        val a = abs(angle)
        return when {
            a < 22 -> Icon.STRAIGHT
            a < 67 -> if (angle < 0) Icon.SLIGHT_LEFT else Icon.SLIGHT_RIGHT
            a < 112 -> if (angle < 0) Icon.LEFT else Icon.RIGHT
            a < 150 -> if (angle < 0) Icon.SHARP_LEFT else Icon.SHARP_RIGHT
            else -> Icon.UTURN
        } to reached
    }

    private val NEIGHBOURS = listOf(0 to -1, -1 to 0, 1 to 0, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)
}
