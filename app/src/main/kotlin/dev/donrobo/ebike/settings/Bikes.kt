package dev.donrobo.ebike.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** One bike (A2). Its settings, ride log and last known state are keyed by [id]. */
data class Bike(val id: String, val name: String, val displayMac: String?)

/** Keys describing the bikes themselves. */
object BikeKeys {
    /** Comma-separated bike ids in creation order. Ids are generated ([newId]) and never contain commas. */
    val ids = Setting.string("bikes")
    val active = Setting.string("bikes.active")
    val name = Setting.string("name", "", Scope.PER_BIKE)
    /** MAC of the bike's display (upper case), set by the companion association or a scan. */
    val mac = Setting.string("mac", Scope.PER_BIKE)
    /**
     * Usable battery capacity in Wh, entered by the rider (A1: the display only reports percent). 0 = not set;
     * anything that needs energy (electricity cost, F7) is hidden until it is. Edited on the Rad screen.
     */
    val batteryWh = Setting.int("battery.capacityWh", 0, Scope.PER_BIKE)
}

/**
 * The bikes, read from [Settings] on every access (no cached list). Per-bike files live in
 * `filesDir/bikes/<id>/`.
 */
class Bikes(private val settings: Settings, private val filesDir: File) {

    fun ids(): List<String> = settings[BikeKeys.ids].orEmpty().split(',').filter { it.isNotEmpty() }

    fun get(id: String): Bike? = if (id in ids()) read(id) else null

    fun all(): List<Bike> = ids().map(::read)

    /** The selected bike; falls back to the first one if the stored selection is gone. */
    fun active(): Bike? {
        val ids = ids()
        val id = settings[BikeKeys.active]?.takeIf { it in ids } ?: ids.firstOrNull() ?: return null
        return read(id)
    }

    fun activeFlow(): Flow<Bike?> = settings.anyChange().map { active() }.distinctUntilChanged()
    fun allFlow(): Flow<List<Bike>> = settings.anyChange().map { all() }.distinctUntilChanged()

    fun add(name: String, mac: String? = null, id: String = newId(), sync: Boolean = false): Bike {
        settings.store.edit(sync) {
            putString(BikeKeys.ids.key, (ids() + id).joinToString(","))
            putString(settings.storageKey(BikeKeys.name, id), name)
            putString(settings.storageKey(BikeKeys.mac, id), mac)
            if (settings[BikeKeys.active] == null) putString(BikeKeys.active.key, id)
        }
        return read(id)
    }

    fun setActive(id: String) { require(id in ids()); settings[BikeKeys.active] = id }
    fun rename(id: String, name: String) = settings.set(BikeKeys.name, id, name)
    fun setMac(id: String, mac: String?) = settings.set(BikeKeys.mac, id, mac?.uppercase())

    /**
     * Removes the bike with everything that belongs to it: all its per-bike settings (`bike.<id>.*`) and its
     * directory (ride log, ...). Rides are deleted, not kept: they are stored per bike and nothing in the app can
     * show rides of a bike that no longer exists, so keeping them would only leave unreachable files behind.
     * The UI must say so before calling this. Other bikes are not touched; if the removed bike was active, the
     * selection falls back to the first remaining bike. The companion association is the caller's job
     * ([dev.donrobo.ebike.BikeCompanion.removeBike]).
     */
    fun remove(id: String) {
        if (id !in ids()) return
        val prefix = Settings.bikePrefix(id)
        settings.store.edit(sync = true) {
            putString(BikeKeys.ids.key, ids().filter { it != id }.joinToString(",").ifEmpty { null })
            if (settings[BikeKeys.active] == id) remove(BikeKeys.active.key)
            settings.store.keys().filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
        dir(id).deleteRecursively()
    }

    /** Directory for a bike's own files (ride log, ...). */
    fun dir(id: String): File = File(filesDir, "bikes/$id")

    private fun read(id: String) = Bike(id, settings.get(BikeKeys.name, id), settings.get(BikeKeys.mac, id))

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
