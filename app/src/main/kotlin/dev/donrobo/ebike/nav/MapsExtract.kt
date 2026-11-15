package dev.donrobo.ebike.nav

import dev.donrobo.ebike.proto.Icon

/** Android-free pieces of the Maps notification reader (text interpretation, arrow mask). Unit-tested. */
object MapsExtract {
    private val DURATION = Regex("""\d+\s*(min|h|std)\b""", RegexOption.IGNORE_CASE)

    /**
     * A "summary" line such as "12 min · 3,4 km · 14:05": carries the remaining distance, not the next turn.
     * Recognised by the duration only: on Android 16 the maneuver title itself contains a dot
     * ("150 m · Nach links abbiegen, um auf Lindenweg zu bleiben", seen on a Pixel 10).
     */
    fun isSummary(s: String) = DURATION.containsMatchIn(s)

    private val TURNS = listOf(
        Regex("""\b(wenden|kehrtwende|u-turn)""") to (Icon.UTURN to Icon.UTURN),
        Regex("""\bscharf (nach )?(links|rechts)|\bsharp (left|right)""") to (Icon.SHARP_LEFT to Icon.SHARP_RIGHT),
        Regex("""\b(leicht|halb) ?(nach )?(links|rechts)|\b(links|rechts) halten|\bslight (left|right)|\bkeep (left|right)""") to (Icon.SLIGHT_LEFT to Icon.SLIGHT_RIGHT),
        Regex("""\b(links|rechts) abbiegen|\bnach (links|rechts)|\bturn (left|right)""") to (Icon.LEFT to Icon.RIGHT),
    )
    private val STRAIGHT = Regex("""\b(geradeaus|weiter auf|weiterfahren|continue|head )""")

    /** Maneuver from the instruction text (German/English), or null if it names none (e.g. roundabouts). */
    fun iconFromText(s: String): Icon? {
        val t = s.lowercase()
        if ("kreisverkehr" in t || "roundabout" in t) return null
        for ((re, icons) in TURNS) {
            val m = re.find(t) ?: continue
            return if ("links" in m.value || "left" in m.value) icons.first
            else if ("rechts" in m.value || "right" in m.value) icons.second else icons.first
        }
        return if (STRAIGHT.containsMatchIn(t)) Icon.STRAIGHT else null
    }

    data class Texts(val distM: Int?, val remainingM: Long?, val arrival: Boolean, val textIcon: Icon? = null)

    /**
     * Interprets notification texts in priority order (title, text, bigText, subText — or the
     * RemoteViews texts in tree order). Distance to the turn = first non-summary text with a
     * distance; remaining = distance in the first summary text. Arrival only counts in
     * non-summary texts, since summaries may mention the destination ("Ziel").
     */
    fun parseTexts(texts: List<String>): Texts {
        val (summary, maneuver) = texts.partition(::isSummary)
        return Texts(
            distM = maneuver.firstNotNullOfOrNull(MapsText::distanceM),
            remainingM = summary.firstNotNullOfOrNull(MapsText::distanceM)?.toLong(),
            arrival = maneuver.any(MapsText::isArrival),
            textIcon = maneuver.firstNotNullOfOrNull(::iconFromText),
        )
    }

    /**
     * Q2: Maps' title without a distance still counts as an instruction when it names a way to go (the first
     * step of a route, "Auf Lindenweg nach Nordwesten"), but not when it only names the app ("Mit Google Maps
     * fahren", "Driving with Google Maps"): that is no maneuver and must not reach the display.
     */
    fun isInstructionWithoutDistance(title: String?): Boolean =
        !title.isNullOrBlank() && !isSummary(title) && "google maps" !in title.lowercase()

    /** Turns parsed texts into a [NavState]; null if neither a distance nor an arrival was found. */
    fun navState(t: Texts, icon: Icon): NavState? = when {
        t.arrival -> NavState(t.distM ?: 0, Icon.ARRIVED, t.remainingM ?: 0)
        t.distM != null -> NavState(t.distM, icon, t.remainingM ?: 0)
        else -> null
    }

    /**
     * Foreground mask from ARGB pixels: alpha >= 128 if the image has any transparent pixel,
     * otherwise pixels whose colour differs clearly from the top-left corner.
     */
    fun mask(argb: IntArray): BooleanArray {
        if (argb.any { (it ushr 24) < 128 }) return BooleanArray(argb.size) { (argb[it] ushr 24) >= 128 }
        val bg = argb.firstOrNull() ?: return BooleanArray(0)
        fun diff(a: Int, shift: Int) = kotlin.math.abs(((a shr shift) and 0xFF) - ((bg shr shift) and 0xFF))
        return BooleanArray(argb.size) { val p = argb[it]; diff(p, 16) + diff(p, 8) + diff(p, 0) > 96 }
    }
}
