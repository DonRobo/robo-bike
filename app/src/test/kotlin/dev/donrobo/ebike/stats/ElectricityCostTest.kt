package dev.donrobo.ebike.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElectricityCostTest {
    @Test fun electricityCost() {
        // artboard: 1.8 charges, 151 km; with 500 Wh, 85 % charging efficiency and 30 ct/kWh
        val c = ElectricityCost.of(180.0, 151.0, 500, 30.0)!!
        assertEquals(180.0 / 100 * 500 / 1000 / 0.85, c.kWh, 1e-9)
        assertEquals(c.kWh * 0.30, c.total, 1e-9)
        assertEquals(c.total * 100 / 151.0, c.perKmCent!!, 1e-9)
        assertNull(ElectricityCost.of(180.0, 151.0, 0, 30.0)) // capacity not set: no number
        assertNull(ElectricityCost.of(0.0, 0.0, 500, 30.0)!!.perKmCent)
        assertEquals(0.0, ElectricityCost.of(0.0, 0.0, 500, 30.0)!!.total, 0.0)
    }
}
