package dev.donrobo.ebike.maintenance

import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MaintenanceTest {
    private val chain = MaintenanceItem("chain", MaintenanceKind.CHAIN, "", 300.0, 1030.0)

    @Test fun defaultsMatchTheArtboardAndUseRoundMiles() {
        val km = Maintenance.defaults(UnitSystem.METRIC)
        assertEquals(listOf(300.0, 1000.0, 3000.0), km.map { it.intervalKm })
        assertTrue(km.all { it.doneAtKm == null && it.kind != MaintenanceKind.CUSTOM })
        val mi = Maintenance.defaults(UnitSystem.IMPERIAL).map { Maintenance.intervalInUnits(it.intervalKm, UnitSystem.IMPERIAL) }
        assertEquals(listOf(200L, 600L, 2000L), mi)
    }

    @Test fun dueSoonOverdueAndOk() {
        // artboard: chain done at 1030, odometer 1284 -> 46 km left of 300 (85 %), due soon
        val s = Maintenance.status(chain, 1284.0, null)
        assertEquals(46.0, s.remainingKm!!, 1e-9)
        assertEquals(DueLevel.DUE_SOON, s.level)
        assertEquals(0.847f, s.fraction!!, 0.001f)
        assertEquals(DueLevel.OK, Maintenance.status(chain, 1260.0, null).level)          // 70 km left > window 60
        assertEquals(DueLevel.DUE_SOON, Maintenance.status(chain, 1330.0, null).level)   // exactly 0 left
        val over = Maintenance.status(chain, 1340.0, null)
        assertEquals(DueLevel.OVERDUE, over.level)
        assertEquals(-10.0, over.remainingKm!!, 1e-9)
        assertEquals(1f, over.fraction!!, 0f)
        // the window is capped at 100 km for long intervals
        val gear = MaintenanceItem("gear", MaintenanceKind.GEAR, "", 3000.0, 0.0)
        assertEquals(DueLevel.OK, Maintenance.status(gear, 2850.0, null).level)
        assertEquals(DueLevel.DUE_SOON, Maintenance.status(gear, 2900.0, null).level)
    }

    @Test fun neverDoneCountsFromBaselineOrNow() {
        val gear = MaintenanceItem("gear", MaintenanceKind.GEAR, "", 3000.0, null)
        assertEquals(1716.0, Maintenance.status(gear, 1284.0, 0.0).remainingKm!!, 1e-9)    // baseline 0 (artboard)
        assertEquals(3000.0, Maintenance.status(gear, 1284.0, null).remainingKm!!, 1e-9)   // no baseline yet: from now
        assertEquals(2900.0, Maintenance.status(gear, 1284.0, 1184.0).remainingKm!!, 1e-9)
    }

    @Test fun odometerUnknown() {
        val s = Maintenance.status(chain, null, null)
        assertNull(s.usedKm); assertNull(s.remainingKm); assertNull(s.fraction)
        assertEquals(DueLevel.OK, s.level)
        assertNull(Maintenance.mostUrgent(Maintenance.statuses(listOf(chain), null, null)))
        assertNull(MaintenanceMonitor.next(Maintenance.statuses(listOf(chain), null, null)))
    }

    @Test fun odometerBelowStartCountsAsNothingUsed() {
        assertEquals(0.0, Maintenance.status(chain, 500.0, null).usedKm!!, 0.0)
    }

    @Test fun erledigtRestartsTheInterval() {
        val items = Maintenance.markDone(listOf(chain), "chain", 1284.0)
        val s = Maintenance.status(items.single(), 1284.0, null)
        assertEquals(300.0, s.remainingKm!!, 1e-9)
        assertEquals(DueLevel.OK, s.level)
    }

    @Test fun intervalEditsAndClamping() {
        val items = Maintenance.setInterval(listOf(chain), "chain", 500.0)
        assertEquals(246.0, Maintenance.status(items.single(), 1284.0, null).remainingKm!!, 1e-9)
        assertEquals(Maintenance.MIN_INTERVAL_KM, Maintenance.setInterval(listOf(chain), "chain", 1.0).single().intervalKm, 0.0)
    }

    @Test fun customItemsAddRenameRemove() {
        var items = Maintenance.add(listOf(chain), "  Reifendruck prüfen ", 200.0, 1284.0, id = "c1")
        val custom = items.last()
        assertEquals(MaintenanceKind.CUSTOM, custom.kind)
        assertEquals("Reifendruck prüfen", custom.name)
        assertEquals(1284.0, custom.doneAtKm!!, 0.0)                                         // counts from now
        assertEquals(200.0, Maintenance.status(custom, 1284.0, 0.0).remainingKm!!, 1e-9)
        assertNull(Maintenance.add(emptyList(), "x", 200.0, null, id = "c2").single().doneAtKm) // odometer unknown
        items = Maintenance.rename(items, "chain", "Kette ölen")
        assertEquals(MaintenanceKind.CUSTOM, items.first().kind)
        assertEquals("Kette ölen", items.first().name)
        assertEquals(listOf("chain"), Maintenance.remove(items, "c1").map { it.id })
    }

    @Test fun mostUrgentAndPublishedState() {
        val brakes = MaintenanceItem("brakes", MaintenanceKind.BRAKES, "", 1000.0, 1300.0)
        val custom = MaintenanceItem("c1", MaintenanceKind.CUSTOM, "Licht", 100.0, 1200.0)
        val due = MaintenanceMonitor.next(Maintenance.statuses(listOf(chain, brakes, custom), 1284.0, 0.0))!!
        assertEquals("Licht", due.name)                 // 16 km left beats chain's 46 km
        assertEquals(16.0, due.dueInKm, 1e-9)
        val chainDue = MaintenanceMonitor.next(Maintenance.statuses(listOf(chain, brakes), 1284.0, 0.0))!!
        assertEquals(MaintenanceKind.CHAIN, chainDue.kind)
        assertNull(MaintenanceMonitor.next(Maintenance.statuses(listOf(brakes), 1284.0, 0.0))) // nothing due soon
    }

    @Test fun alertKeyIsStablePerIntervalAndChangesWithErledigt() {
        val a = Maintenance.status(chain, 1284.0, null)
        val b = Maintenance.status(chain, 1340.0, null) // overdue now: same interval, same alert
        assertEquals("chain@1330", Maintenance.alertKey(a))
        assertEquals(Maintenance.alertKey(a), Maintenance.alertKey(b))
        val done = Maintenance.markDone(listOf(chain), "chain", 1340.0).single()
        assertEquals("chain@1640", Maintenance.alertKey(Maintenance.status(done, 1600.0, null)))
    }

    @Test fun intervalInputInKmAndMiles() {
        assertEquals(1500.0, Maintenance.parseInterval("1.500", UnitSystem.METRIC, Locale.GERMANY)!!, 1e-9)
        assertEquals(250 * UnitFormatter.KM_PER_MI, Maintenance.parseInterval("250", UnitSystem.IMPERIAL, Locale.US)!!, 1e-9)
        assertEquals(2.5 * 100, Maintenance.parseInterval("250,0", UnitSystem.METRIC, Locale.GERMANY)!!, 1e-9)
        assertNull(Maintenance.parseInterval("5", UnitSystem.METRIC, Locale.GERMANY))     // below minimum
        assertNull(Maintenance.parseInterval("12abc", UnitSystem.METRIC, Locale.GERMANY))
        assertNull(Maintenance.parseInterval("", UnitSystem.METRIC, Locale.GERMANY))
        assertEquals(186L, Maintenance.intervalInUnits(300.0, UnitSystem.IMPERIAL))
    }

    @Test fun codecRoundTripAndPersistence() {
        val items = listOf(chain, MaintenanceItem("c1", MaintenanceKind.CUSTOM, "Öl\tund; Fett=%", 123.5, null))
        assertEquals(items, Maintenance.decode(Maintenance.encode(items)))
        assertEquals(emptyList<MaintenanceItem>(), Maintenance.decode(""))
        assertEquals(listOf(chain), Maintenance.decode(Maintenance.encode(listOf(chain)) + "\ngarbage\tline"))

        val s = Settings(MemoryStore())
        s[GeneralSettings.units] = UnitSystem.IMPERIAL
        assertEquals(Maintenance.defaults(UnitSystem.IMPERIAL), MaintenanceSettings.plan(s, "b1")) // never edited: defaults
        MaintenanceSettings.edit(s, "b1") { Maintenance.remove(it, "gear") }
        s[GeneralSettings.units] = UnitSystem.METRIC
        assertEquals(listOf("chain", "brakes"), MaintenanceSettings.plan(s, "b1").map { it.id })     // persisted, units kept
        MaintenanceSettings.edit(s, "b1") { emptyList() }
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSettings.plan(s, "b1"))               // all removed stays removed
        assertEquals(Maintenance.defaults(UnitSystem.METRIC), MaintenanceSettings.plan(s, "b2"))     // per bike
    }
}

