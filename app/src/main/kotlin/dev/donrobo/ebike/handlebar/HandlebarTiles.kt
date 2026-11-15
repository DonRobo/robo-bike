package dev.donrobo.ebike.handlebar

import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting

/**
 * A value the handlebar mode can show as a tile (M2). [id] is what gets stored: never rename an id,
 * only add new ones. Removed ids are dropped when the stored layout is read.
 */
enum class HandlebarTile(val id: String, val counter: DistanceCounter? = null) {
    BATTERY("battery"),
    RANGE("range"),
    ASSIST("assist"),
    /** The app's ride counter ("Diese Fahrt"). */
    RIDE("ride", DistanceCounter.THIS_RIDE),
    /** The display's trip counter ("Trip am Display"); id kept from when it was the only trip tile. */
    TRIP("trip", DistanceCounter.DISPLAY_TRIP),
    SINCE_CHARGE("sinceCharge", DistanceCounter.SINCE_CHARGE),
    SINCE_HOME("sinceHome", DistanceCounter.SINCE_HOME),
    ODO("odo", DistanceCounter.TOTAL),
    SPEED("speed"),
    CLOCK("clock"),
    LIGHT("light"),
    HOME("home");

    companion object {
        fun byId(id: String): HandlebarTile? = entries.firstOrNull { it.id == id }
    }
}

/** Selection and order of the handlebar tiles. Pure; stored per bike in [HandlebarSettings.tiles]. */
object HandlebarTiles {
    /** The artboard's four tiles; its "Diese Fahrt" is the app's ride counter. */
    val DEFAULT = listOf(HandlebarTile.BATTERY, HandlebarTile.RANGE, HandlebarTile.ASSIST, HandlebarTile.RIDE)

    /** Three rows of two: more would make each tile too small to read at a glance. */
    const val MAX = 6

    /** "battery,range,assist". */
    fun encode(tiles: List<HandlebarTile>): String = tiles.joinToString(",") { it.id }

    /**
     * Inverse of [encode]. Unknown ids (from a newer or older app version) and duplicates are dropped,
     * the list is cut to [MAX]. An empty string is a valid choice (only the speed is shown). Returns null
     * (= use the default) if ids were stored but none of them is known any more, so a rider never ends
     * up with an empty screen they did not choose.
     */
    fun decode(s: String): List<HandlebarTile>? {
        val ids = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()
        val known = ids.mapNotNull(HandlebarTile::byId).distinct()
        return if (known.isEmpty()) null else known.take(MAX)
    }

    fun canAdd(tiles: List<HandlebarTile>, tile: HandlebarTile) = tile !in tiles && tiles.size < MAX

    /** Appends [tile] at the end; no-op if it is already shown or the layout is full. */
    fun add(tiles: List<HandlebarTile>, tile: HandlebarTile): List<HandlebarTile> = if (canAdd(tiles, tile)) tiles + tile else tiles

    fun remove(tiles: List<HandlebarTile>, tile: HandlebarTile): List<HandlebarTile> = tiles - tile

    /** Moves [tile] by [delta] positions (negative = towards the top), clamped to the list. */
    fun move(tiles: List<HandlebarTile>, tile: HandlebarTile, delta: Int): List<HandlebarTile> {
        val from = tiles.indexOf(tile)
        if (from < 0) return tiles
        val to = (from + delta).coerceIn(0, tiles.lastIndex)
        if (to == from) return tiles
        return tiles.toMutableList().apply { removeAt(from); add(to, tile) }
    }

    /** Tiles that are not shown, in their natural order (the editor's "add" list). */
    fun available(tiles: List<HandlebarTile>): List<HandlebarTile> = HandlebarTile.entries.filter { it !in tiles }
}

object HandlebarSettings {
    /** Which tiles the handlebar mode shows, in order. Per bike: each bike may have its own mount and needs. */
    val tiles = Setting.mapped("handlebar.tiles", HandlebarTiles.DEFAULT, Scope.PER_BIKE, HandlebarTiles::decode, HandlebarTiles::encode)

    /** M5: open the handlebar mode when the bike connects. Global: it is about this phone, not the bike. */
    val autoStart = Setting.bool("handlebar.autoStart", false)
}
