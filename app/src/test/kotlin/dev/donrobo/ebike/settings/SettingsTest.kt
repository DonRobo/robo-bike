package dev.donrobo.ebike.settings

import dev.donrobo.ebike.units.UnitSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private val perBike = Setting.int("x", 7, Scope.PER_BIKE)
    private val units = Setting.enum("units", UnitSystem.METRIC)

    @Test fun perBikeKeysAreSeparate() {
        val s = Settings(MemoryStore())
        s.set(perBike, "a", 1)
        assertEquals(1, s.get(perBike, "a"))
        assertEquals(7, s.get(perBike, "b"))
        assertEquals("bike.a.x", s.storageKey(perBike, "a"))
    }

    @Test(expected = IllegalArgumentException::class) fun perBikeNeedsId() { Settings(MemoryStore())[perBike] }

    @Test fun enumFallsBackOnUnknownName() {
        val s = Settings(MemoryStore(mapOf("units" to "FURLONGS")))
        assertEquals(UnitSystem.METRIC, s[units])
        s[units] = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, s[units])
    }

    @Test fun flowStartsWithCurrentValue() = runBlocking {
        val s = Settings(MemoryStore())
        s[units] = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, s.flow(units).first())
    }
}
