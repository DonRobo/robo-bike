package dev.donrobo.ebike.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.proto.MeterConfig
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.proto.unhexOrNull
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the bike's display supports (Q4), as one typed state. Features consult [supports] and hide what the
 * display cannot do instead of failing.
 *
 * Until the feature block has been read once ([config] null: never connected, or the display did not answer),
 * everything counts as supported: hiding a working control on missing information would be worse than a command
 * the display rejects, which the callers already report.
 */
data class DisplayCapabilities(val config: MeterConfig?) {
    val known: Boolean get() = config != null

    fun supports(f: DisplayFeature): Boolean = config?.has(f) ?: true

    companion object {
        val UNKNOWN = DisplayCapabilities(null)

        /** Per bike: the raw feature block (hex) as last read from the bike's display. Written by [DisplayCapabilitiesStore]. */
        val block = Setting.string("display.features", Scope.PER_BIKE)

        fun fromHex(hex: String?): DisplayCapabilities = DisplayCapabilities(hex?.unhexOrNull()?.let(MeterConfig::decode))
    }
}

/** Reading and writing the stored feature block of a bike. The block is the one fact; the typed state is derived. */
object DisplayCapabilitiesStore {
    fun get(settings: Settings, bikeId: String): DisplayCapabilities =
        DisplayCapabilities.fromHex(settings.get(DisplayCapabilities.block, bikeId))

    fun flow(settings: Settings, bikeId: String): Flow<DisplayCapabilities> =
        settings.flow(DisplayCapabilities.block, bikeId).map(DisplayCapabilities::fromHex)

    /** Stores a block read at connect. Short/garbled blocks are ignored so a good earlier read survives. */
    fun store(settings: Settings, bikeId: String, raw: ByteArray): Boolean {
        if (MeterConfig.decode(raw) == null) return false
        settings.set(DisplayCapabilities.block, bikeId, raw.copyOf(MeterConfig.SIZE).hex(""))
        return true
    }

    /** A different (or no) display: what the old one supported no longer applies. */
    fun clear(settings: Settings, bikeId: String) = settings.reset(DisplayCapabilities.block, bikeId)
}

/** The active bike's display capabilities, updating when the bike or the stored block changes. */
@Composable
fun rememberDisplayCapabilities(bikeId: String?): DisplayCapabilities {
    val settings = LocalContext.current.settings
    if (bikeId == null) return DisplayCapabilities.UNKNOWN
    val caps by remember(bikeId) { DisplayCapabilitiesStore.flow(settings, bikeId) }
        .collectAsStateWithLifecycle(DisplayCapabilitiesStore.get(settings, bikeId))
    return caps
}
