package dev.donrobo.ebike.proto

/** Things a display may or may not support, from its feature block (0xF1 reg 1, see [MeterConfig]). */
enum class DisplayFeature {
    LAMP_SWITCH,       // headlight on/off from the app (0xA5:166)
    PUSH_NOTIFY,       // phone notifications on the display (0xA5:225)
    DATE_TIME,         // display clock (0x10:62/66/70)
    BLE_UNLOCK,        // Bluetooth proximity unlock
    UPGRADE_CONTROLS,  // firmware update controls
    TIRE_PRESSURE,     // TPMS (0xA8)
    UNIT,              // speed unit km/h / mph (0xA5:224)
    GEAR,              // assist level from the app (0xA5:164)
    BRIGHTNESS,        // backlight level (0xA5:167)
    BLE_UNLOCK_LEVEL,  // proximity unlock level (0xA5:226)
    ERROR_CODE,        // error register (0xA5:120)
}

/**
 * The display's 26-byte feature/config block, `0xF1` REG 1, as BikeGo's `MeterConfig.loadConfig` reads it
 * (docs/PROTOCOL.md, "0xF1 REG 1 — feature config"). Bit layout:
 *
 * | byte | bits | field |
 * |---|---|---|
 * | 2 | 0–1 / 2–3 / 4–5 / 6–7 | coefficient selectors: max+speed / average speed / trip / ODO |
 * | 3 | 0 / 1 / 2 / 7 | lamp switch / push notify / date-time / BLE unlock |
 * | 4 | 0 | upgrade controls |
 * | 5 | 0 | tyre pressure |
 * | 6 | 3 | unit |
 * | 7 | 7 / 0–2 | gear / min gear |
 * | 8 | 7 / 1–3 / 0 | brightness / max / min brightness |
 * | 9 | 7 / 1–3 / 0 | BLE unlock level / max / min level |
 * | 11 | 7 | error code |
 */
data class MeterConfig(
    val features: Set<DisplayFeature>,
    val minGear: Int,
    val minBrightness: Int,
    val maxBrightness: Int,
    val minUnlockLevel: Int,
    val maxUnlockLevel: Int,
    /** Raw 2-bit coefficient selectors for (max/current speed, average speed, trip, ODO). Meaning unknown. */
    val coefficients: List<Int>,
) {
    fun has(f: DisplayFeature) = f in features

    companion object {
        const val SIZE = 26

        /** Null if the block is shorter than BikeGo accepts (`>= 0x1a`). */
        fun decode(b: ByteArray): MeterConfig? {
            if (b.size < SIZE) return null
            fun u(i: Int) = b[i].toInt() and 0xFF
            fun bit(i: Int, n: Int) = (u(i) shr n) and 1 == 1
            val features = buildSet {
                if (bit(3, 0)) add(DisplayFeature.LAMP_SWITCH)
                if (bit(3, 1)) add(DisplayFeature.PUSH_NOTIFY)
                if (bit(3, 2)) add(DisplayFeature.DATE_TIME)
                if (bit(3, 7)) add(DisplayFeature.BLE_UNLOCK)
                if (bit(4, 0)) add(DisplayFeature.UPGRADE_CONTROLS)
                if (bit(5, 0)) add(DisplayFeature.TIRE_PRESSURE)
                if (bit(6, 3)) add(DisplayFeature.UNIT)
                if (bit(7, 7)) add(DisplayFeature.GEAR)
                if (bit(8, 7)) add(DisplayFeature.BRIGHTNESS)
                if (bit(9, 7)) add(DisplayFeature.BLE_UNLOCK_LEVEL)
                if (bit(11, 7)) add(DisplayFeature.ERROR_CODE)
            }
            return MeterConfig(
                features = features,
                minGear = u(7) and 0x07,
                minBrightness = u(8) and 0x01,
                maxBrightness = (u(8) shr 1) and 0x07,
                minUnlockLevel = u(9) and 0x01,
                maxUnlockLevel = (u(9) shr 1) and 0x07,
                coefficients = List(4) { (u(2) shr (2 * it)) and 0x03 },
            )
        }
    }
}
