package dev.donrobo.ebike.ui.components.map

import dev.donrobo.ebike.location.GeoPoint
import java.security.MessageDigest

/** Camera of a static map snapshot: geographic centre and MapLibre zoom. */
data class SnapshotCamera(val center: GeoPoint, val zoom: Double)

/**
 * Everything that decides how the static map behind a ride thumbnail looks: the ride, its outline [points],
 * the tile size in dp, the screen [density], the fit [paddingDp], the base map [palette] (theme) and the
 * [styleUrl]. [fileName] is the cache key, so a snapshot is never reused when any of these change.
 *
 * Map and overlay share one projection: [overlayProjection] places the route in the tile's pixels, and
 * [camera] is derived from the same [MapGeometry.Projection] (in dp, i.e. MapLibre's logical pixels), so the
 * snapshot shows the streets exactly under the route.
 */
internal data class MapThumbnailSpec(
    val rideId: String,
    val points: List<GeoPoint>,
    val sizeDp: Int,
    val density: Float,
    val paddingDp: Float,
    val palette: MapPalette,
    val styleUrl: String = STYLE_URL,
) {
    val bounds: GeoBounds? = GeoBounds.of(points)

    /** Size of the snapshot bitmap in px (MapLibre renders [sizeDp] logical px at [density]). */
    val sizePx: Float get() = sizeDp * density

    /** The route's projection into a tile of [widthPx] x [heightPx] (the overlay drawn on top of the snapshot). */
    fun overlayProjection(widthPx: Float = sizePx, heightPx: Float = sizePx): MapGeometry.Projection? =
        bounds?.let { MapGeometry.Projection(it, widthPx, heightPx, paddingDp * density) }

    /**
     * Snapshot camera: the overlay's projection computed in dp (1 dp = 1 MapLibre logical px), turned into
     * centre + zoom. Null without points or when the zoom would be outside what the style can show.
     */
    val camera: SnapshotCamera? by lazy {
        val b = bounds ?: return@lazy null
        val p = MapGeometry.Projection(b, sizeDp.toFloat(), sizeDp.toFloat(), paddingDp)
        SnapshotCamera(p.center, p.zoom()).takeIf { it.zoom in 0.0..MAX_ZOOM }
    }

    /** Cache file name: `<ride id>_<hash of everything else>.webp`. */
    val fileName: String by lazy {
        val text = buildString {
            append(KEY_VERSION).append('|').append(styleUrl).append('|').append(palette.key).append('|')
            append(sizeDp).append('|').append(density).append('|').append(paddingDp).append('|')
            points.joinTo(this, ";") { "${it.lat},${it.lon}" }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        filePrefix(rideId) + hash + ".webp"
    }

    companion object {
        /** Bump when the rendering changes in a way the other key parts do not capture. */
        private const val KEY_VERSION = 1
        /** Deepest zoom we ask the snapshotter for (a tiny ride is shown ~400 m wide, far below this). */
        const val MAX_ZOOM = 20.0

        /** Start of every cache file name of [rideId] (file-name safe), used to delete a ride's thumbnails. */
        fun filePrefix(rideId: String): String = rideId.replace(Regex("[^A-Za-z0-9-]"), "-") + "_"
    }
}

/** What a thumbnail does next ([MapThumbnailPolicy.step]). */
enum class ThumbStep {
    /** A snapshot file exists: decode and show it. */
    SHOW_FILE,
    /** No file and no recent failure: render one (meanwhile the plain ground is shown). */
    RENDER,
    /** Rendering failed a moment ago: show the plain ground and do not try again yet. */
    FALLBACK,
}

/**
 * When to render a thumbnail. A failure (offline, style or tiles unreachable, timeout) is remembered for
 * [RETRY_AFTER_MS], so rows that keep recomposing or scrolling in and out never retry in a loop; a later
 * composition after that, or the next app start, tries again.
 */
object MapThumbnailPolicy {
    const val RETRY_AFTER_MS = 2 * 60_000L

    fun step(fileExists: Boolean, lastFailureMs: Long?, nowMs: Long): ThumbStep = when {
        fileExists -> ThumbStep.SHOW_FILE
        lastFailureMs != null && nowMs - lastFailureMs in 0 until RETRY_AFTER_MS -> ThumbStep.FALLBACK
        else -> ThumbStep.RENDER
    }
}
