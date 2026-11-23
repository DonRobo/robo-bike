package dev.donrobo.ebike.ui.components.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The static base map behind a ride thumbnail, or null while there is none (not rendered yet, offline,
 * failed, previews and Robolectric): then the caller shows its plain ground. Loads or renders only while
 * the caller is composed; leaving composition cancels the work.
 */
@Composable
internal fun rememberMapThumbnail(rideId: String, points: List<GeoPoint>, size: Dp, padding: Dp): ImageBitmap? {
    if (LocalInspectionMode.current || isRobolectric) return null
    val ctx = LocalContext.current.applicationContext
    val density = LocalDensity.current.density
    val palette = MapPalette.of(AppTheme.colors)
    val spec = remember(rideId, points, size, padding, density, palette) {
        MapThumbnailSpec(rideId, points, size.value.roundToInt(), density, padding.value, palette)
    }
    var image by remember(spec) { mutableStateOf(MapThumbnailCache.cached(spec)) }
    LaunchedEffect(spec) { if (image == null) image = MapThumbnailCache.load(ctx, spec) }
    return image
}

/**
 * Renders ride thumbnails' base maps once with MapLibre's [MapSnapshotter] (same OpenFreeMap style and
 * colours as the live map, no route: the caller draws it on top) and keeps them as WebP files in
 * `cacheDir/ride-thumbs/` (not part of backups; the system may clear it). See [MapThumbnailSpec] for the
 * key and the camera, [MapThumbnailPolicy] for when a render is tried.
 *
 * Threading: files are checked, read and written on [Dispatchers.IO]; the snapshotter is created, started
 * and cancelled on the main thread (MapLibre requires it) and renders on its own thread. At most
 * [PARALLEL] snapshots run at a time.
 */
internal object MapThumbnailCache {
    private const val DIR = "ride-thumbs"
    private const val PARALLEL = 2
    private const val TIMEOUT_MS = 30_000L

    private val permits = Semaphore(PARALLEL)
    private val failures = ConcurrentHashMap<String, Long>()
    /** Decoded thumbnails of this process (about 40 rows, ~200 KB each at 2.6x). */
    private val memory = LruCache<String, ImageBitmap>(40)
    private val styleLock = Mutex()
    @Volatile private var style: StyleDoc? = null
    private val alignmentLogged = AtomicBoolean(false)

    /** The style JSON (fetched once per process from the same URL the live map loads) and its layer ids. */
    private class StyleDoc(val json: String, val layerIds: List<String>)

    fun cached(spec: MapThumbnailSpec): ImageBitmap? = memory[spec.fileName]

    /** Shows the cached file, or renders one if [MapThumbnailPolicy] allows; null means "show the fallback". */
    suspend fun load(ctx: Context, spec: MapThumbnailSpec): ImageBitmap? {
        val key = spec.fileName
        memory[key]?.let { return it }
        val file = File(File(ctx.cacheDir, DIR), key)
        val image = when (MapThumbnailPolicy.step(withContext(Dispatchers.IO) { file.isFile }, failures[key], System.currentTimeMillis())) {
            ThumbStep.SHOW_FILE -> decode(file, key)
            ThumbStep.FALLBACK -> null
            ThumbStep.RENDER -> permits.withPermit {
                // another row may have rendered it (or failed) while this one waited for a permit
                memory[key] ?: when (MapThumbnailPolicy.step(withContext(Dispatchers.IO) { file.isFile }, failures[key], System.currentTimeMillis())) {
                    ThumbStep.SHOW_FILE -> decode(file, key)
                    ThumbStep.FALLBACK -> null
                    ThumbStep.RENDER -> render(ctx, spec, file)
                }
            }
        }
        return image?.also { memory.put(key, it) }
    }

    /** Deletes every cached thumbnail of [rideId] (all sizes and themes), e.g. when the ride is deleted. */
    suspend fun delete(ctx: Context, rideId: String) = withContext(Dispatchers.IO) {
        val prefix = MapThumbnailSpec.filePrefix(rideId)
        File(ctx.cacheDir, DIR).listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        memory.snapshot().keys.filter { it.startsWith(prefix) }.forEach { memory.remove(it) }
    }

    private suspend fun decode(file: File, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.path)?.asImageBitmap() ?: run {
            // unreadable (e.g. cut short): drop it and treat it like a failed render
            file.delete(); failures[key] = System.currentTimeMillis(); null
        }
    }

    private suspend fun render(ctx: Context, spec: MapThumbnailSpec, file: File): ImageBitmap? = try {
        val bitmap = snapshot(ctx, spec, styleDoc())
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)) throw IOException("encoding failed") }
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        }
        failures.remove(spec.fileName)
        bitmap.asImageBitmap()
    } catch (e: CancellationException) {
        throw e // the row left the screen: not a failure
    } catch (e: Exception) {
        failures[spec.fileName] = System.currentTimeMillis()
        Repo.log("ride thumbnail ${spec.rideId}: $e")
        null
    }

    private suspend fun styleDoc(): StyleDoc = style ?: styleLock.withLock {
        style ?: withContext(Dispatchers.IO) {
            val conn = URL(STYLE_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("style: HTTP ${conn.responseCode}")
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val layers = JSONObject(json).getJSONArray("layers")
                StyleDoc(json, List(layers.length()) { layers.getJSONObject(it).getString("id") })
            } finally {
                conn.disconnect()
            }
        }.also { style = it }
    }

    /** One snapshot on the main thread; cancelled (also on the main thread) when the caller is. */
    private suspend fun snapshot(ctx: Context, spec: MapThumbnailSpec, style: StyleDoc): Bitmap {
        val cam = spec.camera ?: throw IOException("no camera for this shape")
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    MapLibre.getInstance(ctx)
                    val options = MapSnapshotter.Options(spec.sizeDp, spec.sizeDp)
                        .withPixelRatio(spec.density)
                        .withStyleJson(style.json)
                        .withCameraPosition(CameraPosition.Builder().target(LatLng(cam.center.lat, cam.center.lon)).zoom(cam.zoom).build())
                        .withLogo(false)
                        .withAttribution(false)
                    val snapshotter = MapSnapshotter(ctx, options)
                    snapshotter.setObserver(object : MapSnapshotter.Observer {
                        override fun onDidFinishLoadingStyle() {
                            for (id in style.layerIds) snapshotter.getLayer(id)?.let { recolorLayer(it, spec.palette) }
                        }
                        override fun onStyleImageMissing(imageName: String) {}
                    })
                    snapshotter.start(
                        { snap ->
                            logAlignment(spec, snap.pixelForLatLng(LatLng(spec.points.last().lat, spec.points.last().lon)))
                            if (cont.isActive) cont.resume(snap.bitmap)
                        },
                        { error -> if (cont.isActive) cont.resumeWithException(IOException("snapshot: $error")) },
                    )
                    cont.invokeOnCancellation {
                        val main = Looper.getMainLooper()
                        if (Looper.myLooper() == main) snapshotter.cancel() else Handler(main).post { snapshotter.cancel() }
                    }
                }
            }
        } ?: throw IOException("snapshot timed out")
    }

    /**
     * Once per process: where the snapshot put the ride's end point versus where the overlay draws it, so the
     * alignment can be checked on a device (`adb logcat -s ebike`). Logged as-is and divided by the density,
     * whichever unit MapLibre reports in.
     */
    private fun logAlignment(spec: MapThumbnailSpec, actual: android.graphics.PointF) {
        if (!alignmentLogged.compareAndSet(false, true)) return
        val proj = spec.overlayProjection() ?: return
        val end = spec.points.last()
        val ex = proj.x(end); val ey = proj.y(end)
        val okPx = abs(actual.x - ex) < 1.5f && abs(actual.y - ey) < 1.5f
        val okDp = abs(actual.x * spec.density - ex) < 1.5f && abs(actual.y * spec.density - ey) < 1.5f
        Repo.log(
            "ride thumbnail alignment: overlay end at %.1f,%.1f px, snapshot says %.1f,%.1f (%s)".format(
                ex, ey, actual.x, actual.y, if (okPx) "px, aligned" else if (okDp) "logical px, aligned" else "MISALIGNED",
            ),
        )
    }
}
