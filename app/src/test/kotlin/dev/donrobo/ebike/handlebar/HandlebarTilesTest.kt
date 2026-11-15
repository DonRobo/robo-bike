package dev.donrobo.ebike.handlebar

import dev.donrobo.ebike.handlebar.HandlebarTile.ASSIST
import dev.donrobo.ebike.handlebar.HandlebarTile.BATTERY
import dev.donrobo.ebike.handlebar.HandlebarTile.CLOCK
import dev.donrobo.ebike.handlebar.HandlebarTile.HOME
import dev.donrobo.ebike.handlebar.HandlebarTile.LIGHT
import dev.donrobo.ebike.handlebar.HandlebarTile.ODO
import dev.donrobo.ebike.handlebar.HandlebarTile.RANGE
import dev.donrobo.ebike.handlebar.HandlebarTile.SPEED
import dev.donrobo.ebike.handlebar.HandlebarTile.TRIP
import dev.donrobo.ebike.handlebar.HandlebarTile.RIDE
import dev.donrobo.ebike.handlebar.HandlebarTile.SINCE_CHARGE
import dev.donrobo.ebike.handlebar.HandlebarTile.SINCE_HOME
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HandlebarTilesTest {

    // --- model ---------------------------------------------------------------

    @Test fun defaultIsTheArtboardsFourTiles() = assertEquals(listOf(BATTERY, RANGE, ASSIST, RIDE), HandlebarTiles.DEFAULT)

    /** Riders who stored "trip" keep the display's trip counter (now named "Trip am Display"). */
    @Test fun storedTripStaysTheDisplayTrip() {
        assertEquals(listOf(BATTERY, TRIP), HandlebarTiles.decode("battery,trip"))
        assertEquals(dev.donrobo.ebike.counters.DistanceCounter.DISPLAY_TRIP, TRIP.counter)
        assertEquals("ride,sinceCharge,sinceHome", HandlebarTiles.encode(listOf(RIDE, SINCE_CHARGE, SINCE_HOME)))
    }

    @Test fun idsAreStableAndUnique() {
        assertEquals(HandlebarTile.entries.size, HandlebarTile.entries.map { it.id }.toSet().size)
        assertEquals("battery,range,assist,ride", HandlebarTiles.encode(HandlebarTiles.DEFAULT))
    }

    @Test fun encodeDecodeRoundTripKeepsOrder() {
        val l = listOf(CLOCK, TRIP, BATTERY)
        assertEquals(l, HandlebarTiles.decode(HandlebarTiles.encode(l)))
    }

    @Test fun emptyIsAValidChoice() = assertEquals(emptyList<HandlebarTile>(), HandlebarTiles.decode(""))

    @Test fun unknownIdsAreDroppedKnownOnesKeepTheirOrder() =
        assertEquals(listOf(TRIP, BATTERY), HandlebarTiles.decode("trip,voltage,battery"))

    @Test fun onlyUnknownIdsFallBackToDefault() = assertNull(HandlebarTiles.decode("voltage,cadence"))

    @Test fun duplicatesAndOverflowAreCleanedUp() {
        assertEquals(listOf(TRIP, BATTERY), HandlebarTiles.decode("trip,battery,trip"))
        assertEquals(HandlebarTiles.MAX, HandlebarTiles.decode(HandlebarTile.entries.joinToString(",") { it.id })!!.size)
    }

    @Test fun addAppendsOnceAndRespectsMax() {
        assertEquals(listOf(BATTERY, CLOCK), HandlebarTiles.add(listOf(BATTERY), CLOCK))
        assertEquals(listOf(BATTERY), HandlebarTiles.add(listOf(BATTERY), BATTERY))
        val full = HandlebarTile.entries.take(HandlebarTiles.MAX)
        val extra = HandlebarTile.entries[HandlebarTiles.MAX]
        assertFalse(HandlebarTiles.canAdd(full, extra))
        assertEquals(full, HandlebarTiles.add(full, extra))
    }

    @Test fun removeAndAvailable() {
        val l = HandlebarTiles.remove(HandlebarTiles.DEFAULT, RANGE)
        assertEquals(listOf(BATTERY, ASSIST, RIDE), l)
        assertEquals(listOf(RANGE, TRIP, SINCE_CHARGE, SINCE_HOME, ODO, SPEED, CLOCK, LIGHT, HOME), HandlebarTiles.available(l))
    }

    @Test fun moveIsClamped() {
        val l = HandlebarTiles.DEFAULT
        assertEquals(listOf(BATTERY, ASSIST, RANGE, RIDE), HandlebarTiles.move(l, RANGE, 1))
        assertEquals(listOf(RANGE, BATTERY, ASSIST, RIDE), HandlebarTiles.move(l, RANGE, -1))
        assertEquals(l, HandlebarTiles.move(l, BATTERY, -1))
        assertEquals(l, HandlebarTiles.move(l, RIDE, 1))
        assertEquals(l, HandlebarTiles.move(l, CLOCK, 1))
    }

    // --- persistence -----------------------------------------------------------

    @Test fun layoutIsStoredPerBike() {
        val s = Settings(MemoryStore())
        assertEquals(HandlebarTiles.DEFAULT, s.get(HandlebarSettings.tiles, "a"))
        s.set(HandlebarSettings.tiles, "a", listOf(CLOCK, SPEED))
        assertEquals(listOf(CLOCK, SPEED), s.get(HandlebarSettings.tiles, "a"))
        assertEquals(HandlebarTiles.DEFAULT, s.get(HandlebarSettings.tiles, "b"))
        assertEquals("clock,speed", s.store.getString("bike.a.handlebar.tiles"))
    }

    @Test fun storedLayoutWithRemovedTileIsRead() {
        val s = Settings(MemoryStore(mapOf("bike.a.handlebar.tiles" to "odo,removed_tile")))
        assertEquals(listOf(ODO), s.get(HandlebarSettings.tiles, "a"))
        val t = Settings(MemoryStore(mapOf("bike.a.handlebar.tiles" to "removed_tile")))
        assertEquals(HandlebarTiles.DEFAULT, t.get(HandlebarSettings.tiles, "a"))
    }

    @Test fun storedEmptyLayoutStaysEmpty() {
        val s = Settings(MemoryStore())
        s.set(HandlebarSettings.tiles, "a", emptyList())
        assertEquals(emptyList<HandlebarTile>(), s.get(HandlebarSettings.tiles, "a"))
    }

    @Test fun autoStartIsOffByDefault() = assertFalse(Settings(MemoryStore())[HandlebarSettings.autoStart])
}
