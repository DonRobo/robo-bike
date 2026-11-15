package dev.donrobo.ebike.display

import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.settings.MemoryStore
import dev.donrobo.ebike.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCapabilitiesTest {
    private fun block(vararg set: Pair<Int, Int>) = ByteArray(26).also { b -> set.forEach { (i, v) -> b[i] = v.toByte() } }

    @Test fun unknownSupportsEverything() {
        DisplayFeature.entries.forEach { assertTrue(DisplayCapabilities.UNKNOWN.supports(it)) }
        assertFalse(DisplayCapabilities.UNKNOWN.known)
    }

    @Test fun storedPerBike() = runBlocking {
        val s = Settings(MemoryStore())
        assertTrue(DisplayCapabilitiesStore.store(s, "a", block(3 to 1)))
        assertFalse(DisplayCapabilitiesStore.store(s, "a", ByteArray(3))) // garbage keeps the good read
        val a = DisplayCapabilitiesStore.get(s, "a")
        assertTrue(a.known)
        assertTrue(a.supports(DisplayFeature.LAMP_SWITCH))
        assertFalse(a.supports(DisplayFeature.ERROR_CODE))
        assertFalse(DisplayCapabilitiesStore.get(s, "b").known) // the other bike is untouched
        assertEquals(a, DisplayCapabilitiesStore.flow(s, "a").first())
        DisplayCapabilitiesStore.clear(s, "a")
        assertFalse(DisplayCapabilitiesStore.get(s, "a").known)
    }
}
