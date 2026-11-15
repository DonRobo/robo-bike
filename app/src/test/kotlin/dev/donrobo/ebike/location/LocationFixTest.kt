package dev.donrobo.ebike.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFixTest {
    @Test fun fixCodecReadsOldFormat() {
        val f = LocationFix(GeoPoint(48.208501, 16.372102), 1_762_803_180_000, 4.5f, 312.3)
        assertEquals(f, LocationFix.decode(LocationFix.encode(f)))
        assertEquals(LocationFix(GeoPoint(48.2016, 16.3663), 0), LocationFix.decode("48.2016,16.3663")) // LightSettings.lastPosition
        assertEquals(Long.MAX_VALUE, LocationFix.decode("48.2016,16.3663")!!.ageMs(5))
        val p = ParkedPosition(1_000, null)
        assertEquals(p, ParkedPosition.decode(ParkedPosition.encode(p)))
        val q = ParkedPosition(2_000, f.copy(altitudeM = null, accuracyM = null))
        assertEquals(q, ParkedPosition.decode(ParkedPosition.encode(q)))
    }
}
