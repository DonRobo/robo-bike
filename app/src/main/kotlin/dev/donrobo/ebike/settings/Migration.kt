package dev.donrobo.ebike.settings

import java.io.File

/**
 * Upgrades stored data to the current schema. Runs once per process start (App.onCreate) before
 * anything else reads settings; each step is idempotent so an interrupted run can simply be repeated.
 */
object Migration {
    const val CURRENT = 2
    private const val SCHEMA = "schema"

    /**
     * @param filesDir the app's filesDir (ride CSVs move from `rides/` to `bikes/<id>/rides/`)
     * @param defaultBikeName name for the bike created from the single-bike data (localised by the caller)
     */
    fun run(settings: Settings, filesDir: File, defaultBikeName: String, newId: () -> String = Bikes::newId) {
        val store = settings.store
        val from = store.getInt(SCHEMA, 0)
        if (from >= CURRENT) return
        if (from < 1) toV1(settings, filesDir, defaultBikeName, newId)
        if (from < 2) toV2(settings, filesDir)
        store.edit(sync = true) { putInt(SCHEMA, CURRENT) }
    }

    /** v0 (single bike, flat keys) -> v1: one [Bike] holding the old display MAC, auto-light flag and rides. */
    private fun toV1(settings: Settings, filesDir: File, defaultBikeName: String, newId: () -> String) {
        val store = settings.store
        val bikes = Bikes(settings, filesDir)
        // Written to disk before any file moves, so an interrupted run finds the same bike again.
        val bike = bikes.active() ?: bikes.add(defaultBikeName, id = newId(), sync = true)

        // Old global keys -> per-bike keys (only if the old key exists, so a repeated run changes nothing).
        store.edit(sync = true) {
            if (store.contains("mac")) {
                putString(settings.storageKey(BikeKeys.mac, bike.id), store.getString("mac"))
                remove("mac")
            }
            if (store.contains("autoLight")) {
                putBoolean("${Settings.bikePrefix(bike.id)}light.auto", store.getBoolean("autoLight", true))
                remove("autoLight")
            }
        }

        val oldRides = File(filesDir, "rides")
        val files = oldRides.listFiles().orEmpty()
        if (files.isNotEmpty()) {
            val target = File(bikes.dir(bike.id), "rides").apply { mkdirs() }
            for (f in files) {
                val dest = File(target, f.name)
                // renameTo is atomic within filesDir; never overwrite a file that is already there
                if (!dest.exists() && !f.renameTo(dest)) {
                    f.copyTo(dest); f.delete()
                }
            }
        }
        if (oldRides.isDirectory && oldRides.listFiles().isNullOrEmpty()) oldRides.delete()
    }

    /**
     * v1 -> v2: the per-bike boolean `light.auto` becomes the mode `light.mode` (true -> AUTO, false -> OFF:
     * "Aus" leaves the light alone, as the old switch did). The other light settings default to the old
     * behaviour (switching at 1° sun elevation, rain from the first drop), so nothing else needs writing.
     */
    private fun toV2(settings: Settings, filesDir: File) {
        val store = settings.store
        val ids = Bikes(settings, filesDir).ids()
        store.edit(sync = true) {
            for (id in ids) {
                val prefix = Settings.bikePrefix(id)
                if (!store.contains("${prefix}light.auto")) continue
                putString("${prefix}light.mode", if (store.getBoolean("${prefix}light.auto", true)) "AUTO" else "OFF")
                remove("${prefix}light.auto")
            }
        }
    }
}
