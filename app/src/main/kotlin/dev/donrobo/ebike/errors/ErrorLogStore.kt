package dev.donrobo.ebike.errors

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.unhex
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

/** The error register as read at the last connection: when, and its first 16 bytes (hex). */
data class RegisterSnapshot(val atMs: Long, val hex: String) {
    companion object {
        val setting = Setting.mapped<RegisterSnapshot?>(
            "errors.register", null, Scope.PER_BIKE,
            decode = { s -> s.split(';').takeIf { it.size == 2 }?.let { (a, h) -> a.toLongOrNull()?.let { RegisterSnapshot(it, h) } } },
            encode = { it?.let { v -> "${v.atMs};${v.hex}" } },
        )
    }
}

/** Per-bike error log in `filesDir/bikes/<id>/errors.csv` (append-only). Written only by [ErrorLogWriter]. */
object ErrorLogStore {
    private val version = MutableStateFlow(0L)

    /** All errors of [bikeId], newest first (live and register entries in one list); emits after every change. */
    fun entries(ctx: Context, bikeId: String): Flow<List<ErrorEntry>> {
        val app = ctx.applicationContext
        return version.map { read(app, bikeId).asReversed() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    /** Oldest first. Blocking. */
    fun read(ctx: Context, bikeId: String): List<ErrorEntry> {
        val f = file(ctx, bikeId)
        return if (f.exists()) ErrorLogic.parse(f.readText()) else emptyList()
    }

    @Synchronized
    internal fun append(ctx: Context, bikeId: String, entries: List<ErrorEntry>) {
        if (entries.isEmpty()) return
        val f = file(ctx, bikeId)
        runCatching {
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText(ErrorLogic.HEADER + "\n")
            f.appendText(entries.joinToString("") { ErrorLogic.line(it) + "\n" })
        }.onFailure { Repo.log("error log write failed: $it") }
        version.update { it + 1 }
    }

    private fun file(ctx: Context, bikeId: String) = File(ctx.bikes.dir(bikeId), "errors.csv")
}

/**
 * Writes the bikes' error logs: one LIVE entry (and one log line) per episode of [DriveErrorWatcher], and the
 * entries of the error register read at connect.
 */
class ErrorLogWriter(ctx: Context) : DriveErrorListener {
    private val ctx = ctx.applicationContext

    /** A new episode: logged with time, odometer and a fresh position if known. */
    override fun onEvent(e: DriveErrorEvent, nowMs: Long) {
        if (e !is DriveErrorEvent.Appeared) return
        val pos = Locations.fix.value?.takeIf { it.ageMs(nowMs) < POSITION_MAX_AGE_MS }?.point
        val raw = byteArrayOf(e.error.raw.toByte()).hex("")
        Repo.log("drive error: raw 0x$raw (code ${e.error.code ?: "?"})")
        ErrorLogStore.append(ctx, e.bikeId, listOf(ErrorEntry(ErrorEntry.Source.LIVE, nowMs, e.odoKm, pos, raw)))
    }

    /** The error register `0xA5:120` of [bikeId], read once per connection: logs entries that appeared while disconnected. */
    fun onRegister(bikeId: String, reg: ByteArray, nowMs: Long) {
        val s = ctx.settings
        val prev = s.get(RegisterSnapshot.setting, bikeId)
        val liveSince = prev?.let { p ->
            ErrorLogStore.read(ctx, bikeId).filter { it.source == ErrorEntry.Source.LIVE && it.seenAtMs >= p.atMs }.map { it.rawHex }
        }.orEmpty()
        val fresh = ErrorLogic.newHistoryEntries(prev?.hex?.unhex(), liveSince, reg)
        if (fresh.isNotEmpty()) Repo.log("error register: ${fresh.size} new entr${if (fresh.size == 1) "y" else "ies"} (${fresh.joinToString()})")
        ErrorLogStore.append(ctx, bikeId, fresh.map { ErrorEntry(ErrorEntry.Source.HISTORY, nowMs, null, null, it) })
        s.set(RegisterSnapshot.setting, bikeId, RegisterSnapshot(nowMs, reg.copyOf(minOf(reg.size, 16)).hex("")))
    }

    private companion object {
        const val POSITION_MAX_AGE_MS = 120_000L
    }
}
