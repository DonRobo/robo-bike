package dev.donrobo.ebike.ride

import dev.donrobo.ebike.battery.BatteryReadings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One CSV line of a ride. [gear] is the assist level (0 = no assist). [altM] is metres above sea level
 * where the phone could convert it (see [dev.donrobo.ebike.location.LocationFix.altitudeM]); null in
 * files written before altitude was recorded and whenever no fresh fix had one.
 */
data class RideSample(
    val epochMs: Long, val lat: Double?, val lon: Double?,
    val speedKmh: Double, val batteryPct: Int, val gear: Int, val tripKm: Double, val odoKm: Double,
    val altM: Double? = null,
)

/** Pure CSV / summary / GPX code for the ride log (no Context, unit-tested). */
object RideCsv {
    /** Current format. Files may also have [HEADER_V1] (8 columns, no altitude); both parse. */
    const val HEADER = "epochMs,lat,lon,speedKmh,batteryPct,gear,tripKm,odoKm,altM"
    const val HEADER_V1 = "epochMs,lat,lon,speedKmh,batteryPct,gear,tripKm,odoKm"
    private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun fileName(startMs: Long, zone: ZoneId = ZoneId.systemDefault()) =
        NAME.format(Instant.ofEpochMilli(startMs).atZone(zone)) + ".csv"

    fun line(s: RideSample) = String.format(
        Locale.ROOT, "%d,%s,%s,%.1f,%d,%d,%.2f,%.2f,%s",
        s.epochMs, s.lat?.let { "%.6f".format(Locale.ROOT, it) } ?: "", s.lon?.let { "%.6f".format(Locale.ROOT, it) } ?: "",
        s.speedKmh, s.batteryPct, s.gear, s.tripKm, s.odoKm, s.altM?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
    )

    /**
     * Parses a ride CSV of either format: the header fixes the column count (8 = before altitude,
     * 9 = current), so a half-written last line cannot pass as a shorter valid one. The header and
     * malformed lines are skipped.
     */
    fun parse(text: String): List<RideSample> = parseLines(text.lineSequence())

    fun parse(file: File): List<RideSample> = file.useLines(block = ::parseLines)

    private fun parseLines(lines: Sequence<String>): List<RideSample> {
        var columns = 0 // 0 = no header seen: accept both
        return lines.mapNotNull { l ->
            when (l) {
                HEADER -> { columns = 9; null }
                HEADER_V1 -> { columns = 8; null }
                else -> parseLine(l, columns)
            }
        }.toList()
    }

    /** Speed in 8-column (pre-altitude) ride files is 10x the real value; every 9-column file has the right scale. */
    private const val LEGACY_SPEED_FACTOR = 10.0

    private fun parseLine(l: String, columns: Int): RideSample? {
        val f = l.split(',')
        if (if (columns == 0) f.size != 8 && f.size != 9 else f.size != columns) return null
        return runCatching {
            RideSample(
                f[0].toLong(), f[1].toDoubleOrNull(), f[2].toDoubleOrNull(),
                // 8-column files were written while the report decoder scaled speed 10x too high (see BikeReport).
                f[3].toDouble() / (if (f.size == 8) LEGACY_SPEED_FACTOR else 1.0),
                f[4].toInt(), f[5].toInt(), f[6].toDouble(), f[7].toDouble(),
                f.getOrNull(8)?.toDoubleOrNull(),
            )
        }.getOrNull()
    }

    /** Null for fewer than 2 samples. See [RideSummary] for what each field means. */
    fun summarize(file: File, s: List<RideSample>): RideSummary? {
        if (s.size < 2) return null
        val end = s.lastOrNull { it.speedKmh > 0 } ?: s.last()
        val rest = BatteryReadings.restLevels(s)
        var movingS = 0.0
        for (i in 1 until s.size) {
            val dt = (s[i].epochMs - s[i - 1].epochMs) / 1000.0
            if (s[i - 1].speedKmh > 0 && dt in 0.0..MAX_STEP_S) movingS += dt
        }
        return RideSummary(
            file = file, startMs = s.first().epochMs,
            durationS = (end.epochMs - s.first().epochMs) / 1000,
            distanceKm = s.last().odoKm - s.first().odoKm,
            batteryStartPct = rest?.startPct ?: 0, batteryEndPct = rest?.endPct ?: 0,
            endMs = end.epochMs,
            movingS = movingS.toLong(),
            maxSpeedKmh = s.maxOf { it.speedKmh },
            hasTrack = s.any { it.lat != null && it.lon != null },
            climbM = climb(s),
            levels = RideStats.levelBreakdown(s),
            shape = RideShape.of(s),
        )
    }

    /** Sum of climbs with [CLIMB_NOISE_M] hysteresis; null if no sample has an altitude. */
    fun climb(s: List<RideSample>): Double? {
        var ref: Double? = null
        var sum = 0.0
        for (a in s.mapNotNull { it.altM }) {
            val r = ref
            if (r == null || a < r - CLIMB_NOISE_M) ref = a
            else if (a > r + CLIMB_NOISE_M) { sum += a - r; ref = a }
        }
        return if (ref == null) null else sum
    }

    /** Sample gaps longer than this (connection lost) do not count as riding time. */
    const val MAX_STEP_S = 10.0
    /** Hysteresis for the climb sum, so GPS altitude jitter does not add up. */
    const val CLIMB_NOISE_M = 3.0

    /** GPX 1.1 track; samples without a position are skipped, speed/battery/gear go into extensions. */
    fun gpx(name: String, samples: List<RideSample>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Robo Bike" xmlns="http://www.topografix.com/GPX/1/1" xmlns:eb="https://github.com/DonRobo/robo-bike">""").append('\n')
        append("<trk><name>").append(name.xmlEscape()).append("</name><trkseg>\n")
        for (s in samples) {
            if (s.lat == null || s.lon == null) continue
            append(String.format(Locale.ROOT, """<trkpt lat="%.6f" lon="%.6f">""", s.lat, s.lon))
            s.altM?.let { append(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
            append("<time>").append(Instant.ofEpochMilli(s.epochMs)).append("</time>")
            append(String.format(Locale.ROOT, "<extensions><eb:speedKmh>%.1f</eb:speedKmh><eb:batteryPct>%d</eb:batteryPct><eb:gear>%d</eb:gear></extensions>", s.speedKmh, s.batteryPct, s.gear))
            append("</trkpt>\n")
        }
        append("</trkseg></trk>\n</gpx>\n")
    }

    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Ride start/stop decisions. A ride starts on the first moving sample when none is open or the last
 * movement is older than [gapMs]; while open, at most one sample per [intervalMs] is written. A ride belongs
 * to one bike: a sample of another bike ([bikeId]) never continues it, also within the gap (A2).
 */
class RideTracker(private val gapMs: Long = 5 * 60_000L, private val intervalMs: Long = 1000) {
    enum class Action { SKIP, START, APPEND }

    private var open = false
    private var lastMovingMs = 0L
    private var lastWrittenMs = 0L
    /** The bike of the open ride (null = not given, e.g. in tests that only look at the timing). */
    var bikeId: String? = null; private set

    fun onSample(nowMs: Long, speedKmh: Double, bikeId: String? = this.bikeId): Action {
        if (bikeId != this.bikeId) { open = false; this.bikeId = bikeId }
        val moving = speedKmh > 0
        if (moving && !isActive(nowMs)) {
            open = true; lastMovingMs = nowMs; lastWrittenMs = nowMs
            return Action.START
        }
        if (!isActive(nowMs)) { open = false; return Action.SKIP }
        if (moving) lastMovingMs = nowMs
        if (nowMs - lastWrittenMs < intervalMs) return Action.SKIP
        lastWrittenMs = nowMs
        return Action.APPEND
    }

    /** A ride is open and its last movement lies within the gap. */
    fun isActive(nowMs: Long) = open && nowMs - lastMovingMs <= gapMs
}
