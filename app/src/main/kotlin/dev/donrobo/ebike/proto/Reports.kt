package dev.donrobo.ebike.proto

/** Live push report 1 (op 0x06 from 0x10), about every 0.5 s. Trip/ODO are in 10 m units. */
data class BikeReport(
    val lightOn: Boolean,
    val gear: Int,
    val gears: Int,
    val batteryPct: Int,
    val speedKmh: Double,
    val tripKm: Double,
    val odoKm: Double,
    /** Byte 0: current drive error as sent by the display, 0 = none. Encoding: see [ErrorCodes]. */
    val errorRaw: Int = 0,
    /** Byte 3 == 1. BikeGo only sends the lamp switch while unlocked; whether the display enforces that is untested. */
    val unlocked: Boolean = true,
) {
    companion object {
        fun decode(d: ByteArray): BikeReport {
            require(d.size >= 19) { "report 1 needs >= 19 bytes, got ${d.size}" }
            return BikeReport(
                lightOn = d[4].toInt() != 0,
                gear = d[5].toInt() and 0xFF,
                gears = d[6].toInt() and 0xFF,
                batteryPct = d[7].toInt() and 0xFF,
                // 0.01 km/h units on the EKD01, like trip/ODO in 0.01 km. BikeGo documents "/10 x coefficient"
                // (the feature block's coefficient selectors); measured on 7 real rides, the integral of this
                // value matches the odometer distance only with /100 (ratio 1.01-1.02).
                speedKmh = u16(d, 9) / 100.0,
                tripKm = u32(d, 11) / 100.0,
                odoKm = u32(d, 15) / 100.0,
                errorRaw = d[0].toInt() and 0xFF,
                unlocked = d[3].toInt() == 1,
            )
        }
    }
}

fun cString(b: ByteArray): String = String(b, Charsets.US_ASCII).substringBefore('\u0000').trim()
