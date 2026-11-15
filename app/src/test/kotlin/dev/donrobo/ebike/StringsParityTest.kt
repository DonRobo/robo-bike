package dev.donrobo.ebike

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Q5: every UI text exists in English (`values/`, the default) and German (`values-de/`). Fails when a string,
 * plural or string array is declared in only one of them, per source set. `translatable="false"` entries (names
 * such as "Deutsch"/"English") are exempt.
 */
class StringsParityTest {
    private val sourceSets = listOf("main", "debug")

    private fun resDir(set: String): File =
        // Gradle runs unit tests in the module directory; the IDE may use the project root.
        listOf(File("src/$set/res"), File("app/src/$set/res")).firstOrNull { it.isDirectory } ?: File("src/$set/res")

    private fun keys(dir: File): Set<String> {
        val files = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
        val out = mutableSetOf<String>()
        val factory = DocumentBuilderFactory.newInstance()
        for (f in files) {
            val root = factory.newDocumentBuilder().parse(f).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i) as? org.w3c.dom.Element ?: continue
                if (n.tagName !in setOf("string", "plurals", "string-array")) continue
                if (n.getAttribute("translatable") == "false") continue
                out += "${n.tagName}/${n.getAttribute("name")}"
            }
        }
        return out
    }

    @Test fun everyStringExistsInEnglishAndGerman() {
        val problems = mutableListOf<String>()
        for (set in sourceSets) {
            val res = resDir(set)
            val en = keys(File(res, "values"))
            val de = keys(File(res, "values-de"))
            if (set == "main") assertTrue("no strings found in ${res.absolutePath}", en.isNotEmpty())
            (en - de).sorted().forEach { problems += "$set: $it has no German text (values-de)" }
            (de - en).sorted().forEach { problems += "$set: $it has no English text (values)" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
