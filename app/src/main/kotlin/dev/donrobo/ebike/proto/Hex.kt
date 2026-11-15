package dev.donrobo.ebike.proto

/**
 * The app's one hex format: two lower-case digits per byte, [separator] between them. Log lines and dumps use the
 * default (`"55 aa 01"`), stored values use `""` (`"55aa01"`) and read back with [unhex].
 */
fun ByteArray.hex(separator: String = " "): String = joinToString(separator) { hexByte(it.toInt()) }

/** One byte (the low 8 bits of [b]) as two lower-case hex digits. */
fun hexByte(b: Int): String = "%02x".format(b and 0xFF)

/** Inverse of `hex("")`; throws [IllegalArgumentException] on an odd length or a non-hex digit. */
fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "odd hex length: $this" }
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** [unhex], or null if this is not hex. */
fun String.unhexOrNull(): ByteArray? = runCatching { unhex() }.getOrNull()
