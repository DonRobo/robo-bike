package dev.donrobo.ebike.proto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** AES-128-ECB over the 4-byte challenge padded with zeros; static key from the BikeGo app. */
private val AES_KEY = "2CTDU40qNyCgTjb1".toByteArray(Charsets.US_ASCII)

fun authResponse(challenge: ByteArray): ByteArray {
    require(challenge.size == 4) { "challenge must be 4 bytes" }
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
    return c.doFinal(challenge + ByteArray(12))
}
