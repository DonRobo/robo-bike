package dev.donrobo.ebike.maintenance

import dev.donrobo.ebike.units.UnitSystem
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** The built-in maintenance items (W1); their names are localised in the UI. [CUSTOM] = named by the rider. */
enum class MaintenanceKind { CHAIN, BRAKES, GEAR, CUSTOM }

/**
 * One maintenance reminder of a bike (W1), counted by odometer. All distances are kilometres; the UI converts.
 *
 * [doneAtKm] is the odometer when the rider last tapped "Erledigt"; null = never marked done in the app, then the
 * interval counts from the bike's baseline (the first odometer the app knew, see [MaintenanceSettings.baselineKm]),
 * because nobody can know when it was really done last.
 */
data class MaintenanceItem(
    val id: String,
    val kind: MaintenanceKind,
    /** Rider's name for [MaintenanceKind.CUSTOM]; empty for the built-ins. */
    val name: String,
    val intervalKm: Double,
    val doneAtKm: Double?,
)

enum class DueLevel { OK, DUE_SOON, OVERDUE }

/**
 * [item] evaluated at an odometer. [usedKm]/[remainingKm] are null while the odometer is unknown (bike never
 * connected); then [level] is OK, nothing can be due.
 */
data class MaintenanceStatus(
    val item: MaintenanceItem,
    val startKm: Double?,
    val usedKm: Double?,
    val remainingKm: Double?,
    val level: DueLevel,
) {
    /** Share of the interval used, 0..1; null = unknown. */
    val fraction: Float? get() = usedKm?.let { (it / item.intervalKm).coerceIn(0.0, 1.0).toFloat() }
}

/** Pure maintenance model (unit-tested). */
object Maintenance {
    /** An item is "due soon" in the last 20 % of its interval, but never more than [DUE_SOON_MAX_KM] ahead. */
    const val DUE_SOON_FRACTION = 0.2
    const val DUE_SOON_MAX_KM = 100.0
    const val MIN_INTERVAL_KM = 10.0
    const val MAX_INTERVAL_KM = 100_000.0
    const val MAX_NAME_LENGTH = 40

    /**
     * Built-in items with round intervals in the rider's units: chain 300 km / 200 mi, brake pads 1000 km /
     * 600 mi, gear hub 3000 km / 2000 mi.
     */
    fun defaults(units: UnitSystem): List<MaintenanceItem> {
        fun km(metric: Double, miles: Double) = if (units == UnitSystem.METRIC) metric else units.fromUnit(miles)
        return listOf(
            MaintenanceItem("chain", MaintenanceKind.CHAIN, "", km(300.0, 200.0), null),
            MaintenanceItem("brakes", MaintenanceKind.BRAKES, "", km(1000.0, 600.0), null),
            MaintenanceItem("gear", MaintenanceKind.GEAR, "", km(3000.0, 2000.0), null),
        )
    }

    fun dueSoonWindowKm(intervalKm: Double) = min(intervalKm * DUE_SOON_FRACTION, DUE_SOON_MAX_KM)

    /**
     * Evaluates [item] at [odoKm]. The interval starts at [MaintenanceItem.doneAtKm], else at [baselineKm], else
     * (odometer known but no baseline stored yet) at [odoKm]. An odometer below the start (different display,
     * reset) counts as nothing used.
     */
    fun status(item: MaintenanceItem, odoKm: Double?, baselineKm: Double?): MaintenanceStatus {
        if (odoKm == null) return MaintenanceStatus(item, item.doneAtKm ?: baselineKm, null, null, DueLevel.OK)
        val start = item.doneAtKm ?: baselineKm ?: odoKm
        val used = max(0.0, odoKm - start)
        val remaining = item.intervalKm - used
        val level = when {
            remaining < 0 -> DueLevel.OVERDUE
            remaining <= dueSoonWindowKm(item.intervalKm) -> DueLevel.DUE_SOON
            else -> DueLevel.OK
        }
        return MaintenanceStatus(item, start, used, remaining, level)
    }

    fun statuses(items: List<MaintenanceItem>, odoKm: Double?, baselineKm: Double?) = items.map { status(it, odoKm, baselineKm) }

    /** The item that needs attention first: due soon or overdue, least remaining distance; null = nothing due. */
    fun mostUrgent(statuses: List<MaintenanceStatus>): MaintenanceStatus? =
        statuses.filter { it.level != DueLevel.OK && it.remainingKm != null }.minByOrNull { it.remainingKm!! }

    /**
     * Key of the maintenance alert for [s]: item + the odometer it is due at ("chain@1330"). Stays the same from
     * "due soon" to "overdue" (one alert per service interval) and changes with "Erledigt" or a new interval.
     */
    fun alertKey(s: MaintenanceStatus): String =
        "${s.item.id}@${Math.round((s.startKm ?: 0.0) + s.item.intervalKm)}"

    /** "Erledigt": the item's interval restarts at [odoKm]. */
    fun markDone(items: List<MaintenanceItem>, id: String, odoKm: Double) = items.map { if (it.id == id) it.copy(doneAtKm = odoKm) else it }

    fun setInterval(items: List<MaintenanceItem>, id: String, intervalKm: Double) =
        items.map { if (it.id == id) it.copy(intervalKm = clampInterval(intervalKm)) else it }

    /** Renaming a built-in turns it into a custom item with that name (it keeps its interval and progress). */
    fun rename(items: List<MaintenanceItem>, id: String, name: String) = items.map {
        if (it.id == id && name.isNotBlank()) it.copy(kind = MaintenanceKind.CUSTOM, name = name.trim().take(MAX_NAME_LENGTH)) else it
    }

    /** New own item; counts from [odoKm] (now) when known, else from the baseline like the built-ins. */
    fun add(items: List<MaintenanceItem>, name: String, intervalKm: Double, odoKm: Double?, id: String = newId()): List<MaintenanceItem> =
        items + MaintenanceItem(id, MaintenanceKind.CUSTOM, name.trim().take(MAX_NAME_LENGTH), clampInterval(intervalKm), odoKm)

    fun remove(items: List<MaintenanceItem>, id: String) = items.filter { it.id != id }

    fun clampInterval(km: Double) = km.coerceIn(MIN_INTERVAL_KM, MAX_INTERVAL_KM)

    fun newId(): String = "c" + UUID.randomUUID().toString().replace("-", "").take(8)

    /** Interval typed by the rider in their units ("250", "1.500", "2,5") -> km; null if not a valid interval. */
    fun parseInterval(text: String, units: UnitSystem, locale: Locale): Double? {
        val v = parseNumber(text, locale) ?: return null
        val km = units.fromUnit(v)
        return km.takeIf { it in MIN_INTERVAL_KM..MAX_INTERVAL_KM }
    }

    /** Interval in the rider's units, rounded to whole units, for prefilling the edit field. */
    fun intervalInUnits(km: Double, units: UnitSystem): Long =
        Math.round(units.toUnit(km))

    /** Locale-aware number parse that rejects trailing garbage ("12abc"). */
    fun parseNumber(text: String, locale: Locale): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val pos = ParsePosition(0)
        val n = NumberFormat.getNumberInstance(locale).parse(t, pos) ?: return null
        if (pos.index != t.length) return null
        return n.toDouble().takeIf { it.isFinite() }
    }

    // --- storage codec: one item per line, "id\tkind\tintervalKm\tdoneAtKm\tname" (name URL-encoded) ---

    fun encode(items: List<MaintenanceItem>): String = items.joinToString("\n") {
        listOf(
            it.id, it.kind.name, String.format(Locale.ROOT, "%.3f", it.intervalKm),
            it.doneAtKm?.let { d -> String.format(Locale.ROOT, "%.3f", d) }.orEmpty(),
            URLEncoder.encode(it.name, "UTF-8"),
        ).joinToString("\t")
    }

    /** Malformed lines are skipped; an empty string is an empty list (all items removed). */
    fun decode(s: String): List<MaintenanceItem> = s.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val f = l.split('\t')
        if (f.size != 5) return@mapNotNull null
        runCatching {
            MaintenanceItem(
                id = f[0].also { require(it.isNotEmpty()) }, kind = MaintenanceKind.valueOf(f[1]),
                name = URLDecoder.decode(f[4], "UTF-8"), intervalKm = f[2].toDouble().also { require(it > 0) },
                doneAtKm = f[3].takeIf { it.isNotEmpty() }?.toDouble(),
            )
        }.getOrNull()
    }.toList()
}
