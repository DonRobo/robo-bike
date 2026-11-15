package dev.donrobo.ebike.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BikesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun addRenameRemoveLeaveOtherBikesAlone() {
        val s = Settings(MemoryStore())
        val dir: File = tmp.root
        val bikes = Bikes(s, dir)
        val light = Setting.bool("light.auto", true, Scope.PER_BIKE)
        val a = bikes.add("Stadtrad", "00:11:22:33:44:55")
        s.set(light, a.id, false)
        File(bikes.dir(a.id), "rides").apply { mkdirs() }.resolve("r.csv").writeText("a")

        val b = bikes.add("MTB", "11:22:33:44:55:66")
        s.set(light, b.id, true)
        File(bikes.dir(b.id), "rides").apply { mkdirs() }.resolve("r.csv").writeText("b")
        bikes.setActive(b.id)
        bikes.rename(b.id, "Bergrad")
        assertEquals("Stadtrad", bikes.get(a.id)!!.name)

        bikes.remove(b.id)
        assertEquals(listOf(a), bikes.all())
        assertEquals(a, bikes.active()) // selection falls back
        assertFalse(s.get(light, a.id))  // the other bike's settings ...
        assertTrue(File(bikes.dir(a.id), "rides/r.csv").exists()) // ... and rides survive
        assertFalse(bikes.dir(b.id).exists()) // the removed bike's rides are gone with it
        assertTrue(s.store.keys().none { it.startsWith("bike.${b.id}.") })
        assertNull(bikes.get(b.id))

        bikes.remove(a.id)
        assertTrue(bikes.all().isEmpty())
        assertNull(bikes.active())
    }
}
