package dev.donrobo.ebike.pairing

import dev.donrobo.ebike.pairing.Pairing.Appeared
import dev.donrobo.ebike.pairing.Pairing.Assign
import dev.donrobo.ebike.pairing.Pairing.Target
import dev.donrobo.ebike.settings.Bike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val city = Bike("a", "Stadtrad", "00:11:22:33:44:AA")
    private val mtb = Bike("b", "MTB", "11:22:33:44:55:66")
    private val fresh = Bike("c", "Mein Rad", null)

    @Test fun knownDisplayNames() {
        assertTrue(Pairing.isKnownDisplay("EKD01-BF ")) // the real advertisement, trailing space
        assertTrue(Pairing.isKnownDisplay("ekd01"))
        assertFalse(Pairing.isKnownDisplay("Galaxy Buds"))
        assertFalse(Pairing.isKnownDisplay(null))
        assertEquals("EKD01", Pairing.modelName("EKD01-BF "))
        assertEquals("DP C18", Pairing.modelName(" DP C18 "))
        assertNull(Pairing.modelName("  "))
        // the companion chooser pattern must match the advertised name as well
        assertTrue(Regex(Pairing.KNOWN_NAME_PATTERN).matches("EKD01-BF "))
    }

    @Test fun setupNeededOnlyWithoutAnyDisplay() {
        assertTrue(Pairing.needsSetup(emptyList()))
        assertTrue(Pairing.needsSetup(listOf(fresh))) // fresh install: the migration's bike has no display
        assertFalse(Pairing.needsSetup(listOf(city)))  // existing installation with its paired display
        assertFalse(Pairing.needsSetup(listOf(fresh, mtb)))
    }

    @Test fun setupTargetReusesBikeWithoutDisplayOnFirstRun() {
        assertEquals(Target.Existing("c"), Pairing.setupTarget(listOf(fresh)))
        assertEquals(Target.NewBike, Pairing.setupTarget(emptyList()))
        assertEquals(Target.NewBike, Pairing.setupTarget(listOf(city, fresh))) // from the settings: add a bike
    }

    @Test fun reconcileGivesOrphanAssociationsToBikesWithoutDisplay() {
        val mac = "aa:bb:cc:dd:ee:ff"
        assertEquals(listOf("c" to "AA:BB:CC:DD:EE:FF"), Pairing.reconcile(listOf(city, fresh), listOf("00:11:22:33:44:aa", mac)))
        assertEquals(emptyList<Pair<String, String>>(), Pairing.reconcile(listOf(city), listOf(mac)))      // nobody to give it to
        assertEquals(emptyList<Pair<String, String>>(), Pairing.reconcile(listOf(fresh), listOf()))
    }

    @Test fun assignNewDisplay() {
        assertEquals(Assign.Store("c", "AA:BB:CC:DD:EE:FF"), Pairing.assign(listOf(city, fresh), Target.Existing("c"), "aa:bb:cc:dd:ee:ff"))
        assertEquals(Assign.Store(null, "AA:BB:CC:DD:EE:FF"), Pairing.assign(listOf(city), Target.NewBike, "aa:bb:cc:dd:ee:ff"))
        // a display that already belongs to another bike is not stolen: that bike gets selected
        assertEquals(Assign.AlreadyOwned("a"), Pairing.assign(listOf(city, fresh), Target.Existing("c"), city.displayMac!!.lowercase()))
        assertEquals(Assign.AlreadyOwned("a"), Pairing.assign(listOf(city), Target.NewBike, city.displayMac!!))
        // re-pairing a bike with its own display is fine
        assertEquals(Assign.Store("a", city.displayMac), Pairing.assign(listOf(city), Target.Existing("a"), city.displayMac!!))
    }

    @Test fun appearingDisplaySelectsItsBike() {
        val bikes = listOf(city, mtb)
        assertEquals(Appeared.Connect("b"), Pairing.onAppeared(bikes, mtb.displayMac!!.lowercase(), true, null, emptySet()))
        assertEquals(Appeared.Connect("a"), Pairing.onAppeared(bikes, city.displayMac, true, "a", emptySet()))
        assertTrue(Pairing.onAppeared(bikes, "00:00:00:00:00:00", true, null, emptySet()) is Appeared.Ignore)
        assertTrue(Pairing.onAppeared(bikes, mtb.displayMac, false, null, emptySet()) is Appeared.Ignore)
        // E5 per bike: a Disconnect of the city bike does not block the MTB
        assertTrue(Pairing.onAppeared(bikes, city.displayMac, true, null, setOf("a")) is Appeared.Ignore)
        assertEquals(Appeared.Connect("b"), Pairing.onAppeared(bikes, mtb.displayMac, true, null, setOf("a")))
        // a running session with another bike is never interrupted
        assertTrue(Pairing.onAppeared(bikes, mtb.displayMac, true, "a", emptySet()) is Appeared.Ignore)
    }

    @Test fun disappearingDisplayEndsOnlyItsPause() {
        val bikes = listOf(city, mtb)
        assertEquals("a", Pairing.onDisappeared(bikes, city.displayMac, setOf("a", "b")))
        assertNull(Pairing.onDisappeared(bikes, city.displayMac, setOf("b")))
        assertNull(Pairing.onDisappeared(bikes, "00:00:00:00:00:00", setOf("a")))
    }

    @Test fun scanSkipsOtherBikesDisplays() {
        val bikes = listOf(city, fresh)
        assertFalse(Pairing.isScanCandidate("EKD01-BF ", city.displayMac!!, bikes, "c"))
        assertTrue(Pairing.isScanCandidate("EKD01-BF ", city.displayMac!!, bikes, "a"))
        assertTrue(Pairing.isScanCandidate("EKD01-BF ", "AA:BB:CC:DD:EE:FF", bikes, "c"))
        assertFalse(Pairing.isScanCandidate("Phone", "AA:BB:CC:DD:EE:FF", bikes, "c"))
    }

    @Test fun pickStrongestFreeDisplay() {
        val seen = listOf(
            SeenDevice(city.displayMac!!, "EKD01-BF ", -40), // the city bike's own display: taken
            SeenDevice("AA:AA:AA:AA:AA:AA", "EKD01-BF ", -80),
            SeenDevice("BB:BB:BB:BB:BB:BB", "EKD01-BF ", -60),
            SeenDevice("CC:CC:CC:CC:CC:CC", "Watch", -30),
        )
        assertEquals("BB:BB:BB:BB:BB:BB", pickDisplay(seen, listOf(city), null)?.address)
        assertNull(pickDisplay(seen.take(1), listOf(city), null))
    }

    @Test fun signalBuckets() {
        assertEquals(Signal.GOOD, signal(-50))
        assertEquals(Signal.OK, signal(-75))
        assertEquals(Signal.WEAK, signal(-90))
    }

    @Test fun defaultNames() {
        val numbered = { n: Int -> "Rad $n" }
        assertEquals("Mein Rad", Pairing.defaultName(emptyList(), "Mein Rad", numbered))
        assertEquals("Rad 2", Pairing.defaultName(listOf(city), "Mein Rad", numbered))
        assertEquals("Rad 3", Pairing.defaultName(listOf(city.copy(name = "Rad 2")), "Mein Rad", numbered))
    }

    /** The background scan filters must match what the display advertises (docs/PROTOCOL.md): name with trailing space. */
    @Test fun scanFilterNamesComeFromPairing() {
        assertTrue(Pairing.EKD01_ADVERTISED_NAME in Pairing.SCAN_FILTER_NAMES)
        assertTrue(Pairing.EKD01_ADVERTISED_NAME.endsWith(" "))
        for (n in Pairing.SCAN_FILTER_NAMES) assertTrue(n, Pairing.isKnownDisplay(n))
        assertEquals("00001812-0000-1000-8000-00805f9b34fb", Pairing.ADVERTISED_SERVICE.toString())
    }
}
