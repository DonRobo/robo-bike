package dev.donrobo.ebike.proto

import dev.donrobo.ebike.proto.ErrorCodes.DriveError
import dev.donrobo.ebike.proto.ErrorCodes.Encoding
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ErrorCodesTest {
    private fun hx(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reg(vararg b: Int) = ByteArray(16).also { for ((i, v) in b.withIndex()) it[i] = v.toByte() }

    /** Real answer to `0xA5:120` from a logged session (`ble-session/before.log`): 32 bytes, 16 zero + the BLE name. */
    private val realHistory = hx("00000000000000000000000000000000454b4430312d42462000000000000000")

    @Test fun realRegisterMeansNoError() {
        assertEquals(emptyList<DriveError>(), ErrorCodes.decodeHistory(realHistory))
        for (e in Encoding.values()) assertTrue(ErrorCodes.decodeHistory(realHistory, e).isEmpty())
    }

    @Test fun realReportMeansNoError() {
        assertNull(ErrorCodes.currentFromReport(hx("000000010001054800000046000000460000000000")))
        assertNull(ErrorCodes.currentFromReport(ByteArray(0)))
    }

    @Test fun bcdHistoryKeepsOrderAndSkipsEmptyBytes() {
        val got = ErrorCodes.decodeHistory(reg(0x21, 0x00, 0x30, 0xFF, 0x04), Encoding.BCD)
        assertEquals(listOf(21, 30, 4), got.map { it.code })
        assertEquals(listOf(0x21, 0x30, 0x04), got.map { it.raw })
        assertTrue(got.all { it.known })
    }

    @Test fun onlyFirst16BytesAreTheErrorBlock() {
        val r = realHistory.copyOf().also { it[0] = 0x08 }
        assertEquals(listOf(8), ErrorCodes.decodeHistory(r).map { it.code })
    }

    @Test fun invalidBcdHasNoCode() {
        val e = ErrorCodes.decodeHistory(reg(0x1A), Encoding.BCD).single()
        assertEquals(0x1A, e.raw)
        assertNull(e.code)
        assertFalse(e.known)
    }

    @Test fun binaryEncoding() {
        assertEquals(listOf(21), ErrorCodes.decodeHistory(reg(0x15), Encoding.BINARY).map { it.code })
        assertEquals(21, ErrorCodes.currentFromReport(byteArrayOf(0x15, 0, 0, 1), Encoding.BINARY)?.code)
    }

    @Test fun asciiPairsLikeBafangCan() {
        val r = reg('2'.code, '1'.code, '1'.code, '4'.code)
        assertEquals(listOf(21, 14), ErrorCodes.decodeHistory(r).map { it.code })          // AUTO picks ASCII
        assertEquals(listOf(21, 14), ErrorCodes.decodeHistory(r, Encoding.ASCII).map { it.code })
        assertEquals(listOf(32, 31, 31, 34), ErrorCodes.decodeHistory(r, Encoding.BCD).map { it.code })
    }

    @Test fun autoFallsBackToBcd() {
        // odd number of digit bytes -> cannot be ASCII pairs
        assertEquals(listOf(30), ErrorCodes.decodeHistory(reg(0x30)).map { it.code })
        assertEquals(listOf(21, 45), ErrorCodes.decodeHistory(reg(0x21, 0x45)).map { it.code })
    }

    @Test fun currentErrorFromReportByte0() {
        val rep = hx("21000001000105480000000000000000000000000000".take(42))
        assertEquals(DriveError(0x21, 21), ErrorCodes.currentFromReport(rep))
        assertEquals("error_21", ErrorCodes.resourceKey(21))
        assertEquals("error_04", ErrorCodes.resourceKey(4))
    }

    @Test fun everyKnownCodeHasTextsInBothLanguages() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res").takeIf { d -> d.isDirectory } ?: File(it, "src/main/res") }
            .first { it.isDirectory }
        for (dir in listOf("values", "values-de")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings_errors.xml"))
            val nodes = doc.getElementsByTagName("string")
            val names = (0 until nodes.length).associate {
                nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent.trim()
            }
            for (c in ErrorCodes.KNOWN) for (suffix in listOf("title", "advice")) {
                val key = "${ErrorCodes.resourceKey(c)}_$suffix"
                assertTrue("$dir: missing $key", !names[key].isNullOrBlank())
            }
            assertTrue("$dir: generic unknown-code text", names.containsKey("error_unknown_title"))
        }
    }
}
