package dev.donrobo.ebike.ui.components.map

import androidx.compose.ui.graphics.Color
import dev.donrobo.ebike.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class MapThumbnailTest {
    private val palette = MapPalette(Color(0xFFE9EEE6), Color(0xFFD5E6CE), Color(0xFFCDE1EC), Color.White, Color.Gray, Color.LightGray)

    // synthetic rides around Vienna
    private val wide = (0 until 32).map { GeoPoint(48.2082 + 0.002 * kotlin.math.sin(it / 5.0), 16.3200 + 0.0025 * it) }
    private val tall = (0 until 32).map { GeoPoint(48.1800 + 0.0020 * it, 16.3700 + 0.001 * kotlin.math.cos(it / 4.0)) }
    private val tiny = listOf(GeoPoint(48.2082, 16.3738), GeoPoint(48.20822, 16.37383))

    private fun spec(points: List<GeoPoint>, density: Float = 2.625f, id: String = "2026-11-11_215300") =
        MapThumbnailSpec(id, points, 84, density, 14f, palette)

    /**
     * Independent model of MapLibre's camera: the world is 512 * 2^zoom logical px wide (Web Mercator),
     * the camera centre sits in the middle of the image, and the bitmap has `pixelRatio` px per logical px.
     */
    private fun mapLibrePixel(p: GeoPoint, cam: SnapshotCamera, sizeDp: Int, pixelRatio: Float): Pair<Double, Double> {
        fun mx(lon: Double) = (lon + 180) / 360
        fun my(lat: Double) = Math.toRadians(lat).let { (1 - ln(tan(it) + 1 / kotlin.math.cos(it)) / PI) / 2 }
        val world = 512 * 2.0.pow(cam.zoom) * pixelRatio
        val half = sizeDp * pixelRatio / 2.0
        return half + (mx(p.lon) - mx(cam.center.lon)) * world to half + (my(p.lat) - my(cam.center.lat)) * world
    }

    @Test fun overlayLiesExactlyWhereTheSnapshotCameraPutsIt() {
        for (points in listOf(wide, tall, tiny)) for (density in listOf(1f, 2f, 2.625f, 3.5f)) {
            val s = spec(points, density)
            val cam = s.camera!!
            val overlay = s.overlayProjection()!!
            for (p in points) {
                val (x, y) = mapLibrePixel(p, cam, s.sizeDp, density)
                assertEquals(x, overlay.x(p).toDouble(), 0.01)
                assertEquals(y, overlay.y(p).toDouble(), 0.01)
            }
        }
    }

    @Test fun cameraFitsTheBoundsInsideThePadding() {
        val s = spec(wide, 2f)
        val proj = s.overlayProjection()!!
        val pad = 14f * 2f
        val xs = wide.map { proj.x(it) }; val ys = wide.map { proj.y(it) }
        // the wide shape fills the width up to the padding and stays inside vertically
        assertEquals(pad, xs.min(), 0.01f)
        assertEquals(s.sizePx - pad, xs.max(), 0.01f)
        assertTrue(ys.min() >= pad - 0.01f && ys.max() <= s.sizePx - pad + 0.01f)
        // ~5.8 km into 56 dp: 512 * 2^z = 56 / (0.0775 / 360)  ->  z = 9.0
        assertEquals(9.0, s.camera!!.zoom, 0.05)
        // a tiny ride is shown ~400 m wide (z = 12.8 here), not zoomed in endlessly
        assertEquals(12.8, spec(tiny).camera!!.zoom, 0.1)
    }

    @Test fun inverseMercator() {
        for (lat in listOf(-60.0, 0.0, 48.2082, 70.0)) assertEquals(lat, MapGeometry.latOfMercY(MapGeometry.mercY(lat)), 1e-9)
        assertEquals(16.3738, MapGeometry.lonOfMercX(MapGeometry.mercX(16.3738)), 1e-9)
    }

    @Test fun noCameraWithoutPoints() {
        assertNull(spec(emptyList()).camera)
        assertNull(spec(emptyList()).overlayProjection())
    }

    @Test fun cacheKeyCoversEveryInput() {
        val base = spec(wide)
        assertEquals(base.fileName, spec(wide).fileName)
        assertTrue(base.fileName.startsWith("2026-11-11-215300_") && base.fileName.endsWith(".webp"))
        val variants = listOf(
            base.copy(rideId = "2026-11-12_080000"),
            base.copy(points = wide.dropLast(1) + GeoPoint(48.2100, 16.4000)),
            base.copy(sizeDp = 72),
            base.copy(density = 3f),
            base.copy(paddingDp = 10f),
            base.copy(palette = palette.copy(casing = Color.DarkGray)), // e.g. dark theme or wallpaper colours
            base.copy(styleUrl = "https://tiles.openfreemap.org/styles/bright"),
        )
        variants.forEach { assertNotEquals(base.fileName, it.fileName) }
        assertEquals(variants.size, variants.map { it.fileName }.toSet().size)
    }

    @Test fun deletePrefixMatchesOnlyThatRide() {
        val name = spec(wide, id = "2026-11-11_215300").fileName
        assertTrue(name.startsWith(MapThumbnailSpec.filePrefix("2026-11-11_215300")))
        assertFalse(name.startsWith(MapThumbnailSpec.filePrefix("2026-11-11_2153")))
        assertFalse(name.startsWith(MapThumbnailSpec.filePrefix("2026-11-11")))
    }

    @Test fun policy() {
        val now = 1_000_000_000L
        // file present -> image (even right after a failure, e.g. another row rendered it)
        assertEquals(ThumbStep.SHOW_FILE, MapThumbnailPolicy.step(true, null, now))
        assertEquals(ThumbStep.SHOW_FILE, MapThumbnailPolicy.step(true, now - 1, now))
        // missing -> fallback + render request
        assertEquals(ThumbStep.RENDER, MapThumbnailPolicy.step(false, null, now))
        // just failed -> fallback, no hot retry on the next composition
        assertEquals(ThumbStep.FALLBACK, MapThumbnailPolicy.step(false, now - 1, now))
        assertEquals(ThumbStep.FALLBACK, MapThumbnailPolicy.step(false, now - MapThumbnailPolicy.RETRY_AFTER_MS + 1, now))
        // later -> try again
        assertEquals(ThumbStep.RENDER, MapThumbnailPolicy.step(false, now - MapThumbnailPolicy.RETRY_AFTER_MS, now))
        // a clock set back does not block retries forever
        assertEquals(ThumbStep.RENDER, MapThumbnailPolicy.step(false, now + 60_000, now))
    }
}
