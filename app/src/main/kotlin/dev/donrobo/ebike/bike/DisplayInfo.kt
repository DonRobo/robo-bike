package dev.donrobo.ebike.bike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Device info of a bike's display (W5) as read at its last connection: the registers the session reads at connect
 * ("hardware", "software", "serial", "controller"). Blank = the display delivered nothing for it.
 */
data class DisplayInfo(val values: Map<String, String>) {
    val hardware: String? get() = values["hardware"].orNull()
    val software: String? get() = values["software"].orNull()
    val serial: String? get() = values["serial"].orNull()
    /** Empty on many displays (also on the owner's bike); then the row is hidden. */
    val controller: String? get() = values["controller"].orNull()

    /** "EKD01" from "EKD01_CAN_BF_APO_N04". */
    val model: String? get() = software?.substringBefore('_')?.orNull()

    /** Only the end of the serial number is shown ("endet auf 4321"). */
    val serialEnd: String? get() = serial?.takeLast(SERIAL_SHOWN)

    val isEmpty: Boolean get() = listOf(hardware, software, serial, controller).all { it == null }

    companion object {
        const val SERIAL_SHOWN = 4
        private fun String?.orNull() = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Per-bike store of [DisplayInfo], the one place the display's info is kept. Written by the connection session after
 * reading the info registers; cleared on "Display vergessen".
 */
object DisplayInfoStore {
    val setting = Setting.mapped<Map<String, String>?>("display.info", null, Scope.PER_BIKE, ::decode, { it?.let(::encode) })

    fun get(settings: Settings, bikeId: String): DisplayInfo? = settings.get(setting, bikeId)?.let(::DisplayInfo)

    fun flow(settings: Settings, bikeId: String): Flow<DisplayInfo?> = settings.flow(setting, bikeId).map { it?.let(::DisplayInfo) }

    /**
     * Stores what one connection read. Keys that could not be read this time keep their earlier value; a key read
     * as empty is stored empty (the display really has nothing there).
     */
    fun store(settings: Settings, bikeId: String, read: Map<String, String>) {
        if (read.isEmpty()) return
        settings.set(setting, bikeId, merge(settings.get(setting, bikeId), read))
    }

    fun clear(settings: Settings, bikeId: String) = settings.reset(setting, bikeId)

    fun merge(old: Map<String, String>?, read: Map<String, String>): Map<String, String> = old.orEmpty() + read

    fun encode(m: Map<String, String>): String =
        m.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    fun decode(s: String): Map<String, String>? = runCatching {
        s.split('&').filter { it.isNotEmpty() }.associate { e ->
            val (k, v) = e.split('=', limit = 2).also { require(it.size == 2) }
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }.getOrNull()
}

/** [bikeId]'s stored [DisplayInfo], updating when a connection reads it again; null = never read / no bike. */
@Composable
fun rememberDisplayInfo(bikeId: String?): DisplayInfo? {
    val settings = LocalContext.current.settings
    val info by remember(bikeId) { bikeId?.let { DisplayInfoStore.flow(settings, it) } ?: flowOf(null) }
        .collectAsStateWithLifecycle(bikeId?.let { DisplayInfoStore.get(settings, it) })
    return info
}
