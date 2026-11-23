package dev.donrobo.ebike.ride

import dev.donrobo.ebike.battery.BatteryReadings
import dev.donrobo.ebike.location.GeoPoint
import java.io.File

/**
 * Cached [RideSummary]s of the ride CSVs in one directory, so a list of years of rides does not parse
 * every file on each screen open.
 *
 * The CSV files (and each ride's [RideFixes] file) stay the only source of truth. Each cache entry carries
 * both files' size and modification time and is used only while they still match; new or changed files are re-summarised,
 * entries of deleted files are dropped, and the whole cache is rebuilt when [VERSION] changes. So the
 * cache can be stale only in time, never in content: every [load] validates it against the files.
 * **Bump [VERSION] whenever [RideCsv.summarize], [RideStats.levelBreakdown], [dev.donrobo.ebike.battery.BatteryReadings.restLevels], [RideShape.of] or [RideEndpoints] change what they compute.**
 */
class RideIndex(private val dir: File) {
    /** [fixes] = size and mtime of the ride's [RideFixes] file ("" without one). */
    private class Entry(val size: Long, val mtime: Long, val fixes: String, val summary: RideSummary?)

    /**
     * Summaries of all ride files, newest first; files with fewer than 2 samples are left out. End levels are
     * settled across rides ([BatteryReadings.settleEnds]); the cache holds each file's own values, since a
     * ride's settled end depends on the next file.
     */
    @Synchronized
    fun load(): List<RideSummary> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }.orEmpty()
        val cached = read()
        var dirty = cached.keys.any { name -> files.none { it.name == name } }
        val entries = files.associate { f ->
            val c = cached[f.name]
            val e = if (c != null && c.size == f.length() && c.mtime == f.lastModified() && c.fixes == fixesKey(f)) c else {
                dirty = true
                // size/mtime are taken before parsing: a write during parsing makes the entry stale, not wrong
                val size = f.length(); val mtime = f.lastModified(); val fixes = fixesKey(f)
                Entry(size, mtime, fixes, runCatching { RideCsv.summarize(f, RideCsv.parse(f), RideFixes.read(f)) }.getOrNull())
            }
            f.name to e
        }
        if (dirty) write(entries)
        return BatteryReadings.settleEnds(entries.values.mapNotNull { it.summary }.sortedByDescending { it.startMs })
    }

    private val file get() = File(dir, FILE)

    private fun fixesKey(csv: File): String = RideFixes.file(csv).let { if (it.exists()) "${it.length()}:${it.lastModified()}" else "" }

    private fun read(): Map<String, Entry> {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyMap()
        if (lines.firstOrNull() != "$MAGIC $VERSION") return emptyMap()
        return lines.drop(1).mapNotNull { decode(it) }.toMap()
    }

    private fun write(entries: Map<String, Entry>) {
        val tmp = File(dir, "$FILE.tmp")
        runCatching {
            tmp.writeText(buildString {
                append("$MAGIC $VERSION\n")
                entries.forEach { (name, e) -> append(encode(name, e)).append('\n') }
            })
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun encode(name: String, e: Entry): String {
        val s = e.summary ?: return "$name\t${e.size}\t${e.mtime}\t-\t${e.fixes}"
        return listOf(
            name, e.size, e.mtime, s.startMs, s.durationS, d(s.distanceKm), s.batteryStartPct, s.batteryEndPct, s.endMs,
            s.movingS, d(s.maxSpeedKmh), if (s.hasTrack) 1 else 0, s.climbM?.let(::d) ?: "",
            s.levels.joinToString(";") { "${it.level}:${d(it.distanceKm)}:${it.timeS}" },
            s.shape?.encode().orEmpty(), e.fixes,
            s.ends?.let { "${it.start.lat},${it.start.lon};${it.end.lat},${it.end.lon}" }.orEmpty(),
        ).joinToString("\t")
    }

    private fun decode(line: String): Pair<String, Entry>? = runCatching {
        val f = line.split('\t')
        val name = f[0]
        val size = f[1].toLong(); val mtime = f[2].toLong()
        if (f.size == 5 && f[3] == "-") return@runCatching name to Entry(size, mtime, f[4], null)
        require(f.size == 17)
        val levels = f[13].split(';').filter { it.isNotEmpty() }.map {
            val p = it.split(':')
            LevelStats(p[0].toInt(), p[1].toDouble(), p[2].toLong())
        }
        name to Entry(
            size, mtime, f[15],
            RideSummary(
                file = File(dir, name), startMs = f[3].toLong(), durationS = f[4].toLong(), distanceKm = f[5].toDouble(),
                batteryStartPct = f[6].toInt(), batteryEndPct = f[7].toInt(), endMs = f[8].toLong(), movingS = f[9].toLong(),
                maxSpeedKmh = f[10].toDouble(), hasTrack = f[11] == "1",
                climbM = f[12].toDoubleOrNull(), levels = levels,
                shape = f[14].takeIf { it.isNotEmpty() }?.let(RideShape::decode),
                ends = f[16].takeIf { it.isNotEmpty() }?.let(::decodeEnds),
            ),
        )
    }.getOrNull()

    private fun decodeEnds(s: String): RideEnds {
        val (a, b) = s.split(';').map { p -> p.split(',').let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
        return RideEnds(a, b)
    }

    // Full precision: a cached summary must equal a freshly computed one.
    private fun d(v: Double) = v.toString()

    companion object {
        // 2: levelBreakdown spreads battery drops over the distance since the previous drop; 3: RideSummary.shape;
        // 4: speed of 8-column files /10;
        // 5: battery start/end are rest levels (BatteryReadings), used = start - end, no per-level battery share;
        // 6: RideSummary.ends (R9) and the RideFixes file in the cache key
        const val VERSION = 6
        const val FILE = "summaries.idx"
        private const val MAGIC = "ride-index"
    }
}
