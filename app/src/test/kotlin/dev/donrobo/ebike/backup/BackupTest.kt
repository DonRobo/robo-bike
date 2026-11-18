package dev.donrobo.ebike.backup

import dev.donrobo.ebike.ride.RideIndex
import dev.donrobo.ebike.ride.RideTemperature
import dev.donrobo.ebike.ride.RideTemperatures
import dev.donrobo.ebike.settings.Bikes
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Migration
import dev.donrobo.ebike.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backup and restore of all app data (F10). */
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val meta = Backup.Meta(1_763_100_000_000, Migration.CURRENT, "0.1", "de")

    /** A ride file exactly as the pre-altitude app wrote it (8 columns). */
    private val oldRide = """
        epochMs,lat,lon,speedKmh,batteryPct,gear,tripKm,odoKm
        1762803180000,48.208500,16.372100,12.3,80,3,1.50,1234.56
        1762803181000,48.208550,16.372200,14.0,80,3,1.51,1234.57
        1762803182000,,,15.0,79,3,1.52,1234.58
    """.trimIndent() + "\n"

    /** Phone A: one bike with an old-format ride, an error log, a cache and the debug log. */
    private fun phoneA(): Pair<File, MemoryStore> {
        val dir = tmp.newFolder("phoneA")
        val store = MemoryStore()
        val bikes = Bikes(Settings(store), dir)
        val bike = bikes.add("Stadtrad", "00:11:22:33:44:55", id = "b1")
        store.edit { putInt("schema", Migration.CURRENT); putDouble("stats.priceCentPerKwh", 31.5); putLong("x.long", 5_000_000_000L); putBoolean("gpsTrack", true) }
        store.edit { putString("bike.b1.rides.routes", "r1\tArbeits%09weg\t48.2,16.3;48.2016,16.4") }
        val rides = File(bikes.dir(bike.id), "rides").apply { mkdirs() }
        File(rides, "2025-11-10_203300.csv").writeText(oldRide)
        File(rides, RideIndex.FILE).writeText("stale cache")
        File(bikes.dir(bike.id), "errors.csv").writeText("1,LIVE,0x10\n")
        File(dir, "log.txt").writeText("debug log of phone A\n")
        return dir to store
    }

    private fun backupOf(dir: File, store: MemoryStore): ByteArray =
        ByteArrayOutputStream().also { Backup.write(it, dir, store, meta) }.toByteArray()

    /** Phone B with its own, different data that a restore must replace completely. */
    private fun phoneB(): Pair<File, MemoryStore> {
        val dir = tmp.newFolder("phoneB")
        val store = MemoryStore()
        val bikes = Bikes(Settings(store), dir)
        bikes.add("Altes Rad", null, id = "zz")
        store.edit { putInt("schema", Migration.CURRENT); putString("only.on.b", "x") }
        File(bikes.dir("zz"), "rides").apply { mkdirs() }.resolve("2026-01-01_100000.csv").writeText(oldRide)
        File(dir, "log.txt").writeText("debug log of phone B\n")
        return dir to store
    }

    @Test fun roundTripReplacesEverything() {
        val (a, sa) = phoneA()
        val zip = backupOf(a, sa)
        val (b, sb) = phoneB()
        val r = Restore(b, sb)
        val c = r.stage(ByteArrayInputStream(zip), Migration.CURRENT)
        assertEquals(meta, c.meta)
        // staging touched nothing yet
        assertEquals("x", sb.getString("only.on.b"))
        assertTrue(File(b, "bikes/zz").exists())
        r.commit()

        assertEquals(sa.all(), sb.all())
        assertNull(sb.getString("only.on.b"))
        assertEquals(31.5, sb.getDouble("stats.priceCentPerKwh", 0.0), 0.0)
        assertEquals(5_000_000_000L, sb.getLong("x.long", 0))
        assertEquals("r1\tArbeits%09weg\t48.2,16.3;48.2016,16.4", sb.getString("bike.b1.rides.routes"))
        assertFalse(File(b, "bikes/zz").exists())
        assertEquals(oldRide, File(b, "bikes/b1/rides/2025-11-10_203300.csv").readText())
        assertEquals("1,LIVE,0x10\n", File(b, "bikes/b1/errors.csv").readText())
        // caches are rebuilt, this phone's debug log stays, no working files are left
        assertFalse(File(b, "bikes/b1/rides/${RideIndex.FILE}").exists())
        assertEquals("debug log of phone B\n", File(b, "log.txt").readText())
        assertFalse(File(b, Restore.DIR).exists())
        // the old-format ride is listed after the restore
        val rides = RideIndex(File(b, "bikes/b1/rides")).load()
        assertEquals(1, rides.size)
        assertEquals(80, rides[0].batteryStartPct)
        assertEquals(listOf("Stadtrad"), Bikes(Settings(sb), b).all().map { it.name })
    }

    @Test fun manifestAndSettingsEncodingSurviveSpecialCharacters() {
        val values = mapOf("a" to "tab\there\nnew\\line", "b" to true, "c" to 7, "d" to 1.25, "e" to Long.MAX_VALUE)
        assertEquals(values, Backup.decodeSettings(Backup.encodeSettings(values)))
        assertEquals(meta, Backup.decodeManifest(Backup.encodeManifest(meta)))
    }

    private fun assertRejected(bytes: ByteArray, problem: Backup.Problem) {
        val (b, sb) = phoneB()
        val before = sb.all()
        val files = b.walkTopDown().map { it.relativeTo(b).path to (if (it.isFile) it.readText() else "") }.toList()
        try {
            Restore(b, sb).stage(ByteArrayInputStream(bytes), Migration.CURRENT)
            fail("accepted")
        } catch (e: Backup.Invalid) {
            assertEquals(e.message, problem, e.problem)
        }
        // nothing changed, nothing left over
        assertEquals(before, sb.all())
        assertEquals(files, b.walkTopDown().map { it.relativeTo(b).path to (if (it.isFile) it.readText() else "") }.toList().filterNot { it.first.startsWith(Restore.DIR) })
        assertFalse(File(b, "${Restore.DIR}/new").exists())
        b.deleteRecursively()
    }

    @Test fun truncatedBackupIsCorrupt() {
        val (a, sa) = phoneA()
        val zip = backupOf(a, sa)
        assertRejected(zip.copyOf(zip.size * 2 / 3), Backup.Problem.CORRUPT)
    }

    @Test fun damagedBytesAreCorrupt() {
        val (a, sa) = phoneA()
        val zip = backupOf(a, sa)
        // flip bytes in the compressed data of the ride file (right after its local header): CRC or inflate fails
        val name = "files/bikes/b1/rides/2025-11-10_203300.csv".toByteArray()
        val at = (0..zip.size - name.size).first { i -> name.indices.all { zip[i + it] == name[it] } } + name.size
        for (i in at + 16 until at + 36) zip[i] = (zip[i].toInt() xor 0x5A).toByte()
        assertRejected(zip, Backup.Problem.CORRUPT)
    }

    @Test fun foreignFilesAreRecognised() {
        assertRejected("just some text, not a zip".toByteArray(), Backup.Problem.FOREIGN)
        assertRejected(ByteArray(0), Backup.Problem.FOREIGN)
        val otherZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("photo.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() }
        }.toByteArray()
        assertRejected(otherZip, Backup.Problem.FOREIGN)
        val wrongManifest = zipOf(Backup.MANIFEST to "format=something-else\nversion=1\n")
        assertRejected(wrongManifest, Backup.Problem.FOREIGN)
    }

    @Test fun newerBackupsAreRefused() {
        val newer = zipOf(Backup.MANIFEST to Backup.encodeManifest(meta.copy(schema = Migration.CURRENT + 1)), Backup.SETTINGS to "")
        assertRejected(newer, Backup.Problem.NEWER)
        val newerFormat = zipOf(Backup.MANIFEST to "format=${Backup.MAGIC}\nversion=${Backup.VERSION + 1}\nschema=1\ncreated=1\n")
        assertRejected(newerFormat, Backup.Problem.NEWER)
    }

    @Test fun pathsOutsideTheAppAreRefused() {
        val evil = zipOf(Backup.MANIFEST to Backup.encodeManifest(meta), Backup.SETTINGS to "", "files/../../evil.txt" to "x")
        assertRejected(evil, Backup.Problem.CORRUPT)
        assertNull(Backup.safeRelative("/etc/passwd"))
        assertNull(Backup.safeRelative("a/./b"))
        assertEquals("bikes/b1/rides/x.csv", Backup.safeRelative("bikes/b1/rides/x.csv"))
    }

    @Test fun brokenSettingsAreCorrupt() {
        assertRejected(zipOf(Backup.MANIFEST to Backup.encodeManifest(meta), Backup.SETTINGS to "i\tcount\tnot-a-number\n"), Backup.Problem.CORRUPT)
        assertRejected(zipOf(Backup.MANIFEST to Backup.encodeManifest(meta)), Backup.Problem.CORRUPT)
    }

    @Test fun unconfirmedRestoreIsDiscardedAtStart() {
        val (a, sa) = phoneA()
        val (b, sb) = phoneB()
        Restore(b, sb).stage(ByteArrayInputStream(backupOf(a, sa)), Migration.CURRENT)
        assertFalse(Restore(b, sb).recover())
        assertEquals("x", sb.getString("only.on.b"))
        assertFalse(File(b, Restore.DIR).exists())
    }

    @Test fun interruptedCommitIsCompletedAtStart() {
        val (a, sa) = phoneA()
        val (b, sb) = phoneB()
        Restore(b, sb).stage(ByteArrayInputStream(backupOf(a, sa)), Migration.CURRENT)
        // simulate a process death during the swap: journal says COMMIT, old data half moved aside
        File(b, "${Restore.DIR}/state").writeText("COMMIT")
        File(b, "${Restore.DIR}/old").mkdirs()
        // a reader recreated an empty rides directory meanwhile
        assertTrue(File(b, "bikes").renameTo(File(b, "${Restore.DIR}/old/bikes")))
        File(b, "bikes/zz/rides").mkdirs()
        assertTrue(Restore(b, sb).recover())
        assertEquals(sa.all(), sb.all())
        assertEquals(oldRide, File(b, "bikes/b1/rides/2025-11-10_203300.csv").readText())
        assertFalse(File(b, "bikes/zz").exists())
        assertFalse(File(b, Restore.DIR).exists())
    }

    @Test fun interruptedInstallIsCompletedAtStart() {
        val (a, sa) = phoneA()
        val (b, sb) = phoneB()
        val r = Restore(b, sb)
        r.stage(ByteArrayInputStream(backupOf(a, sa)), Migration.CURRENT)
        // old data already aside, new files not yet installed, settings not yet written
        File(b, "${Restore.DIR}/old").mkdirs()
        assertTrue(File(b, "bikes").renameTo(File(b, "${Restore.DIR}/old/bikes")))
        File(b, "${Restore.DIR}/state").writeText("INSTALL")
        assertTrue(Restore(b, sb).recover())
        assertEquals(sa.all(), sb.all())
        assertTrue(File(b, "bikes/b1/errors.csv").exists())
        assertFalse(File(b, Restore.DIR).exists())
    }

    @Test fun rideTemperatureIsBackedUpAndRestored() {
        val (a, sa) = phoneA()
        val csv = File(a, "bikes/b1/rides/2025-11-10_203300.csv")
        RideTemperatures.append(csv, RideTemperatures.Reading(RideTemperatures.Moment.START, 1_762_803_180_000L, -1.5))
        val zip = backupOf(a, sa)
        val (b, sb) = phoneB()
        Restore(b, sb).apply { stage(ByteArrayInputStream(zip), Migration.CURRENT); commit() }
        assertEquals(RideTemperature(-1.5, null), RideTemperatures.read(File(b, "bikes/b1/rides/2025-11-10_203300.csv")))
    }

    @Test fun backupLeavesCachesAndDebugLogOut() {
        val (a, sa) = phoneA()
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(backupOf(a, sa))).use { z ->
            generateSequence { z.nextEntry }.map { it.name }.toList()
        }
        assertEquals(listOf(Backup.MANIFEST, Backup.SETTINGS, "files/bikes/b1/errors.csv", "files/bikes/b1/rides/2025-11-10_203300.csv"), names)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()
}
