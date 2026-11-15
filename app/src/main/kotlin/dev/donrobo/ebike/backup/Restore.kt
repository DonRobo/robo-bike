package dev.donrobo.ebike.backup

import dev.donrobo.ebike.settings.KeyValueStore
import java.io.File
import java.io.InputStream

/**
 * Installs a backup so that the app is never left half-restored (F10).
 *
 * 1. [stage]: the whole file is read and checked into `filesDir/restore/new` ([Backup.read]); the phone's
 *    data is not touched. A bad file fails here. The journal says `STAGED`.
 * 2. [commit] (after the rider confirmed): journal `COMMIT`; every app-data entry of filesDir is moved
 *    into `restore/old` (renames, same file system); journal `INSTALL`; the staged entries are moved into
 *    filesDir; the settings are replaced in one synchronous write; `restore/` is deleted.
 * 3. [recover] runs at every process start: a staged but unconfirmed restore is discarded; an interrupted
 *    commit (process killed, phone off) is carried to its end. Every step is idempotent, so the result
 *    is always either the old data (before `COMMIT`) or the complete backup (after).
 *
 * All data already on the phone is replaced: bikes, rides, settings. Nothing is merged.
 */
class Restore(private val filesDir: File, private val store: KeyValueStore) {
    private val dir = File(filesDir, DIR)
    private val staged = File(dir, "new")
    private val old = File(dir, "old")
    private val journal = File(dir, "state")
    private val settingsFile = File(dir, Backup.SETTINGS)

    /** Reads and checks [input]; on success the backup waits for [commit] or [discard]. Throws [Backup.Invalid]. */
    fun stage(input: InputStream, maxSchema: Int): Backup.Contents {
        dir.deleteRecursively()
        dir.mkdirs()
        val c = Backup.read(input, staged, maxSchema)
        settingsFile.writeText(Backup.encodeSettings(c.values))
        write(STAGED)
        return c
    }

    fun discard() { if (state() == STAGED || state() == null) dir.deleteRecursively() }

    /** Replaces all app data with the staged backup. Call only after a successful [stage]. */
    fun commit() {
        check(state() == STAGED) { "nothing staged" }
        write(COMMIT)
        finish()
    }

    /** At process start, before anything reads settings or files. True if a restore was completed now. */
    fun recover(): Boolean = when (state()) {
        COMMIT, INSTALL -> { finish(); true }
        else -> { if (dir.exists()) dir.deleteRecursively(); false }
    }

    private fun finish() {
        if (state() == COMMIT) {
            old.mkdirs()
            for (f in Backup.replaceableTop(filesDir)) moveAside(f)
            write(INSTALL)
        }
        val root = File(staged, "files")
        for (f in root.listFiles().orEmpty()) {
            val dest = File(filesDir, f.name)
            // something may have recreated a directory meanwhile (e.g. an empty rides dir): set it aside too
            if (dest.exists()) moveAside(dest)
            if (!f.renameTo(dest)) throw java.io.IOException("cannot install ${f.name}")
        }
        val values = Backup.decodeSettings(settingsFile.readText())
        store.edit(sync = true) {
            for (k in store.keys()) if (k !in values) remove(k)
            for ((k, v) in values) when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is Double -> putDouble(k, v)
                is String -> putString(k, v)
            }
        }
        dir.deleteRecursively()
    }

    private fun moveAside(f: File) {
        var target = File(old, f.name)
        var n = 1
        while (target.exists()) target = File(old, "${f.name}.${n++}")
        if (!f.renameTo(target)) throw java.io.IOException("cannot move ${f.name} aside")
    }

    private fun state(): String? = journal.takeIf { it.exists() }?.readText()?.trim()

    private fun write(s: String) {
        val tmp = File(dir, "state.tmp")
        tmp.writeText(s)
        if (!tmp.renameTo(journal)) throw java.io.IOException("journal")
    }

    companion object {
        const val DIR = "restore"
        private const val STAGED = "STAGED"
        private const val COMMIT = "COMMIT"
        private const val INSTALL = "INSTALL"
    }
}
