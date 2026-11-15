package dev.donrobo.ebike.backup

import dev.donrobo.ebike.settings.KeyValueStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of all app data as one zip file (F10), for safekeeping and for moving to a new phone.
 *
 * Contents: `manifest.txt` (format, version, settings schema, creation time, app version, language),
 * `settings.tsv` (every stored setting with its type: bikes, per-bike settings, feature settings, ...)
 * and `files/...` (the app's files: ride CSVs in either format, error logs and whatever features keep in
 * `filesDir`). Left out are caches that are rebuilt from the files (`*.idx`), half-written files
 * (`*.tmp`), the restore working directory and this phone's debug log (`log.txt`).
 *
 * Pure JVM code (no Context) so it is unit-tested; [Restore] installs a staged backup.
 */
object Backup {
    const val MAGIC = "robo-bike-backup"
    const val VERSION = 1
    const val MANIFEST = "manifest.txt"
    const val SETTINGS = "settings.tsv"
    const val FILES = "files/"
    const val MIME = "application/zip"

    /** Limits against a hostile or broken file filling the phone. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 200_000

    /** Top-level names in filesDir that are not app data. */
    private val EXCLUDED_TOP = setOf(Restore.DIR, "log.txt")

    /** Whether the file at [rel] (path relative to filesDir, '/'-separated) belongs into a backup. */
    fun included(rel: String): Boolean {
        val top = rel.substringBefore('/')
        val name = rel.substringAfterLast('/')
        return top !in EXCLUDED_TOP && !name.endsWith(".idx") && !name.endsWith(".tmp")
    }

    /** Top-level entries of [filesDir] a restore replaces. */
    internal fun replaceableTop(filesDir: File): List<File> =
        filesDir.listFiles().orEmpty().filter { it.name !in EXCLUDED_TOP }.sortedBy { it.name }

    data class Meta(val createdMs: Long, val schema: Int, val appVersion: String, val language: String?)

    /** Writes a backup of [filesDir] and [store] to [out] (not closed). */
    fun write(out: OutputStream, filesDir: File, store: KeyValueStore, meta: Meta) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(encodeManifest(meta).toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(SETTINGS))
        zip.write(encodeSettings(store.all()).toByteArray())
        zip.closeEntry()
        filesDir.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(filesDir).invariantSeparatorsPath }
            .filter { (_, rel) -> included(rel) }.sortedBy { it.second }
            .forEach { (f, rel) ->
                zip.putNextEntry(ZipEntry(FILES + rel).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        zip.finish()
        out.flush()
    }

    /** Why a file cannot be restored; nothing on the phone was changed in any of these cases. */
    enum class Problem {
        /** Not a backup of this app (other zip, other file type, empty). */
        FOREIGN,
        /** Damaged or incomplete (truncated download, checksum error, unreadable settings). */
        CORRUPT,
        /** Made by a newer app version whose data this version cannot read. */
        NEWER,
        /** Larger than [MAX_BYTES] or with more than [MAX_ENTRIES] files. */
        TOO_LARGE,
    }

    class Invalid(val problem: Problem, message: String, cause: Throwable? = null) : IOException(message, cause)

    /** A completely read and checked backup. [values] are the settings; files are below [filesRoot]. */
    data class Contents(val meta: Meta, val values: Map<String, Any>, val filesRoot: File)

    /**
     * Reads the whole backup from [input] into [target] (must be empty or absent), checking everything:
     * zip structure and checksums (ZipInputStream verifies each entry's CRC), safe paths, manifest, format
     * version, settings schema ([maxSchema]), the settings file and the size limits. Throws [Invalid] and
     * removes [target] on any problem.
     */
    fun read(input: InputStream, target: File, maxSchema: Int): Contents {
        target.deleteRecursively()
        val filesRoot = File(target, "files").apply { mkdirs() }
        try {
            var manifest: String? = null
            var settings: String? = null
            var total = 0L
            var count = 0
            val zip = ZipInputStream(input)
            while (true) {
                val e = try { zip.nextEntry } catch (ex: Exception) { throw Invalid(Problem.CORRUPT, "zip: $ex", ex) } ?: break
                if (++count > MAX_ENTRIES) throw Invalid(Problem.TOO_LARGE, "too many entries")
                val name = e.name
                if (e.isDirectory) continue
                val budget = MAX_BYTES - total
                when {
                    name == MANIFEST -> manifest = String(readLimited(zip, minOf(budget, 64 * 1024L)))
                    name == SETTINGS -> settings = String(readLimited(zip, minOf(budget, 16 * 1024 * 1024L)))
                    name.startsWith(FILES) -> {
                        val rel = safeRelative(name.removePrefix(FILES)) ?: throw Invalid(Problem.CORRUPT, "unsafe path $name")
                        val f = File(filesRoot, rel)
                        f.parentFile?.mkdirs()
                        total += copyLimited(zip, f, budget)
                        if (e.time > 0) f.setLastModified(e.time)
                    }
                    else -> throw Invalid(if (manifest == null) Problem.FOREIGN else Problem.CORRUPT, "unexpected entry $name")
                }
            }
            val m = manifest ?: throw Invalid(Problem.FOREIGN, "no manifest")
            val meta = decodeManifest(m)
            if (meta.schema > maxSchema) throw Invalid(Problem.NEWER, "schema ${meta.schema} > $maxSchema")
            val values = decodeSettings(settings ?: throw Invalid(Problem.CORRUPT, "no settings"))
            return Contents(meta, values, filesRoot)
        } catch (e: Invalid) {
            target.deleteRecursively(); throw e
        } catch (e: Exception) {
            target.deleteRecursively()
            throw Invalid(Problem.CORRUPT, e.toString(), e)
        }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            if (buf.size() + n > limit) throw Invalid(Problem.TOO_LARGE, "entry too large")
            buf.write(b, 0, n)
        }
        return buf.toByteArray()
    }

    private fun copyLimited(input: InputStream, f: File, limit: Long): Long {
        var written = 0L
        f.outputStream().use { out ->
            val b = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                written += n
                if (written > limit) throw Invalid(Problem.TOO_LARGE, "backup too large")
                out.write(b, 0, n)
            }
        }
        return written
    }

    /** '/'-separated relative path without `..`, empty or absolute parts and backslashes; null if unsafe. */
    internal fun safeRelative(p: String): String? {
        if (p.isEmpty() || p.startsWith("/") || '\\' in p || '\u0000' in p) return null
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return p
    }

    // --- manifest ---

    internal fun encodeManifest(m: Meta) = buildString {
        append("format=$MAGIC\n")
        append("version=$VERSION\n")
        append("schema=${m.schema}\n")
        append("created=${m.createdMs}\n")
        append("app=${m.appVersion.replace('\n', ' ')}\n")
        m.language?.let { append("language=$it\n") }
    }

    internal fun decodeManifest(s: String): Meta {
        val kv = s.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (kv["format"] != MAGIC) throw Invalid(Problem.FOREIGN, "not a $MAGIC")
        val version = kv["version"]?.toIntOrNull() ?: throw Invalid(Problem.CORRUPT, "no version")
        if (version > VERSION) throw Invalid(Problem.NEWER, "format $version > $VERSION")
        return Meta(
            createdMs = kv["created"]?.toLongOrNull() ?: throw Invalid(Problem.CORRUPT, "no time"),
            schema = kv["schema"]?.toIntOrNull() ?: throw Invalid(Problem.CORRUPT, "no schema"),
            appVersion = kv["app"].orEmpty(),
            language = kv["language"]?.takeIf { it.isNotEmpty() },
        )
    }

    // --- settings: one line per value, "type \t key \t value", with \\ \t \n escaped ---

    internal fun encodeSettings(values: Map<String, Any>) = buildString {
        for ((k, v) in values.toSortedMap()) {
            val type = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Double -> "d"; is String -> "s"
                else -> continue
            }
            append(type).append('\t').append(esc(k)).append('\t').append(esc(v.toString())).append('\n')
        }
    }

    internal fun decodeSettings(s: String): Map<String, Any> = s.lines().filter { it.isNotEmpty() }.associate { line ->
        val f = line.split('\t')
        if (f.size != 3) throw Invalid(Problem.CORRUPT, "settings line")
        val k = unesc(f[1]); val raw = unesc(f[2])
        val v: Any = when (f[0]) {
            "b" -> raw.toBooleanStrictOrNull()
            "i" -> raw.toIntOrNull()
            "l" -> raw.toLongOrNull()
            "d" -> raw.toDoubleOrNull()
            "s" -> raw
            else -> null
        } ?: throw Invalid(Problem.CORRUPT, "settings value $k")
        k to v
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else { append(c); i++ }
        }
    }
}
