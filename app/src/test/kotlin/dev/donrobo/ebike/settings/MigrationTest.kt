package dev.donrobo.ebike.settings

import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightSettings
import dev.donrobo.ebike.light.RainLevel
import dev.donrobo.ebike.ride.RideSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The keys the pre-redesign Prefs class wrote. */
    private fun oldStore() = MemoryStore(
        mapOf(
            "mac" to "00:11:22:33:44:55", "autoLight" to false, "autoConnect" to true,
            "userStopped" to true, "lastPosition" to "48.2016,16.3663", "gpsTrack" to true, "forwardNav" to false,
        ),
    )

    @Test fun singleBikeStateMovesIntoOneBike() {
        val dir = tmp.newFolder()
        val rides = File(dir, "rides").apply { mkdirs() }
        File(rides, "2026-11-10_203300.csv").writeText("header\n1\n")
        File(rides, "2026-11-11_075000.csv").writeText("header\n2\n")
        val settings = Settings(oldStore())

        Migration.run(settings, dir, "Mein Rad") { "b1" }

        val bikes = Bikes(settings, dir)
        assertEquals(listOf(dev.donrobo.ebike.settings.Bike("b1", "Mein Rad", "00:11:22:33:44:55")), bikes.all())
        assertEquals("b1", bikes.active()?.id)
        assertEquals(LightMode.OFF, settings.get(LightSettings.mode, "b1")) // autoLight=false -> the app leaves the light alone
        assertFalse(settings.store.contains("bike.b1.light.auto"))
        // global keys keep their names and values
        assertTrue(settings[ConnectionSettings.autoConnect])
        assertTrue(settings[RideSettings.gpsTrack])
        assertEquals(dev.donrobo.ebike.location.GeoPoint(48.2016, 16.3663), settings[dev.donrobo.ebike.location.LocationSettings.lastFix]?.point)
        // old per-bike keys are gone (one fact, one place)
        assertFalse(settings.store.contains("mac"))
        assertFalse(settings.store.contains("autoLight"))
        // ride CSVs moved without loss
        val moved = File(dir, "bikes/b1/rides")
        assertEquals(setOf("2026-11-10_203300.csv", "2026-11-11_075000.csv"), moved.list()!!.toSet())
        assertEquals("header\n2\n", File(moved, "2026-11-11_075000.csv").readText())
        assertFalse(rides.exists())
    }

    @Test fun repeatedRunChangesNothing() {
        val dir = tmp.newFolder()
        val settings = Settings(oldStore())
        Migration.run(settings, dir, "A") { "b1" }
        val keys = settings.store.keys().associateWith { settings.store.getString(it) }
        Migration.run(settings, dir, "B") { "b2" }
        assertEquals(keys, settings.store.keys().associateWith { settings.store.getString(it) })
        assertEquals(1, Bikes(settings, dir).all().size)
    }

    @Test fun interruptedRunResumes() {
        // A crash after the bike was created but before the schema was written: the next run reuses that bike.
        val dir = tmp.newFolder()
        val settings = Settings(oldStore())
        Bikes(settings, dir).add("Mein Rad", id = "b1")
        File(dir, "rides").mkdirs(); File(dir, "rides/x.csv").writeText("x")
        Migration.run(settings, dir, "Other") { "b2" }
        assertEquals(listOf("b1"), Bikes(settings, dir).ids())
        assertEquals("00:11:22:33:44:55", Bikes(settings, dir).active()?.displayMac)
        assertTrue(File(dir, "bikes/b1/rides/x.csv").exists())
    }

    @Test fun existingTargetFileIsNotOverwritten() {
        val dir = tmp.newFolder()
        val settings = Settings(oldStore())
        Bikes(settings, dir).add("Mein Rad", id = "b1")
        File(dir, "bikes/b1/rides").mkdirs(); File(dir, "bikes/b1/rides/x.csv").writeText("new")
        File(dir, "rides").mkdirs(); File(dir, "rides/x.csv").writeText("old")
        Migration.run(settings, dir, "A") { "b1" }
        assertEquals("new", File(dir, "bikes/b1/rides/x.csv").readText())
        assertEquals("old", File(dir, "rides/x.csv").readText()) // kept, not lost
    }

    @Test fun freshInstallGetsOneBike() {
        val dir = tmp.newFolder()
        val settings = Settings(MemoryStore())
        Migration.run(settings, dir, "My bike") { "b1" }
        val b = Bikes(settings, dir).active()!!
        assertEquals("My bike", b.name)
        assertNull(b.displayMac)
        assertEquals(LightMode.AUTO, settings.get(LightSettings.mode, "b1")) // default
        assertEquals(RainLevel.DRIZZLE, settings.get(LightSettings.rainLevel, "b1"))
    }

    @Test fun autoLightOnBecomesModeAuto() {
        val dir = tmp.newFolder()
        val settings = Settings(MemoryStore(mapOf("mac" to "00:11:22:33:44:55", "autoLight" to true)))
        Migration.run(settings, dir, "A") { "b1" }
        assertEquals(LightMode.AUTO, settings.get(LightSettings.mode, "b1"))
    }

    /** A v1 store (redesign builds before the light modes): every bike's light.auto is converted. */
    @Test fun v1LightAutoMigratesPerBike() {
        val dir = tmp.newFolder()
        val settings = Settings(MemoryStore())
        val bikes = Bikes(settings, dir)
        bikes.add("A", id = "b1"); bikes.add("B", id = "b2"); bikes.add("C", id = "b3")
        settings.store.edit {
            putInt("schema", 1)
            putBoolean("bike.b1.light.auto", true)
            putBoolean("bike.b2.light.auto", false)
        }
        Migration.run(settings, dir, "X") { "new" }
        assertEquals(LightMode.AUTO, settings.get(LightSettings.mode, "b1"))
        assertEquals(LightMode.OFF, settings.get(LightSettings.mode, "b2"))
        assertEquals(LightMode.AUTO, settings.get(LightSettings.mode, "b3")) // never set: default
        assertFalse(settings.store.keys().any { it.endsWith("light.auto") })
        assertEquals(listOf("b1", "b2", "b3"), bikes.ids())
    }
}
