package dev.donrobo.ebike.bike

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class BatteryCapacityTest {
    @Test fun capacityConversion() {
        val de = Locale.GERMANY
        assertEquals(BatteryCapacity.Result.Ok(504), BatteryCapacity.parseVoltAh("36", "14", de))
        assertEquals(BatteryCapacity.Result.Ok(522), BatteryCapacity.parseVoltAh("36", "14,5", de))
        assertEquals(BatteryCapacity.Result.Ok(625), BatteryCapacity.parseWh("625", de))
        assertEquals(BatteryCapacity.Result.Ok(1250), BatteryCapacity.parseWh("1.250", de))
        assertEquals(BatteryCapacity.Result.Invalid, BatteryCapacity.parseWh("viel", de))
        assertEquals(BatteryCapacity.Result.Invalid, BatteryCapacity.parseVoltAh("36", "", de))
        assertEquals(BatteryCapacity.Result.OutOfRange, BatteryCapacity.parseWh("20", de))
        assertEquals(BatteryCapacity.Result.OutOfRange, BatteryCapacity.parseVoltAh("360", "14", de)) // typo: 360 V
        assertEquals(BatteryCapacity.Result.Ok(504), BatteryCapacity.parseVoltAh("36", "14.0", Locale.US))
    }
}
