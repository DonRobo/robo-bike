package dev.donrobo.ebike.ui.components.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ui.theme.AppColors
import dev.donrobo.ebike.ui.theme.AppTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap as NativeMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** OpenFreeMap "Positron" (light, low-detail) style; recoloured to the artboard palette in [recolor]. */
internal const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/** The real map: a MapLibre [MapView] in Compose, with the overlays as GeoJSON sources + layers. */
@Composable
internal fun MapLibreMap(content: MapContent, mode: MapMode, onPick: ((GeoPoint) -> Unit)?, modifier: Modifier) {
    val ctx = LocalContext.current
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val view = remember { createView(ctx) }
    val holder = remember { Overlays(colors, density, mode) }
    val pick by rememberUpdatedState(onPick)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            view.onPause(); view.onStop(); view.onDestroy()
        }
    }

    LaunchedEffect(view) {
        view.getMapAsync { map ->
            map.uiSettings.apply {
                setAllGesturesEnabled(mode == MapMode.INTERACTIVE)
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isAttributionEnabled = false // BikeMap shows its own, always visible
                isLogoEnabled = false
                isCompassEnabled = false
            }
            map.addOnMapClickListener { ll -> pick?.let { it(GeoPoint(ll.latitude, ll.longitude)); true } ?: false }
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                runCatching { recolor(style, colors) }.onFailure { Repo.log("map: recolour failed: $it") }
                holder.attach(view, map, style)
            }
        }
    }
    LaunchedEffect(content) { holder.show(content) }

    AndroidView(factory = { view }, modifier = modifier)
}

private fun createView(ctx: Context): MapView {
    MapLibre.getInstance(ctx)
    // Texture mode: the map sits in scrolling Compose layouts, where a SurfaceView would not clip/scroll correctly.
    val opts = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true).attributionEnabled(false).logoEnabled(false)
    return MapView(ctx, opts).apply { onCreate(null) }
}

/** Owns the overlay sources/layers of one map and keeps them in sync with the latest [MapContent]. */
private class Overlays(private val c: AppColors, private val density: Density, private val mode: MapMode) {
    private var view: MapView? = null
    private var map: NativeMap? = null
    private var style: Style? = null
    private var content: MapContent? = null
    private var fittedBounds: GeoBounds? = null

    fun attach(v: MapView, m: NativeMap, s: Style) {
        view = v; map = m; style = s
        MarkerKind.entries.forEach { s.addImage(it.name, MapStyle.markerImage(it, c, density).asAndroidBitmap()) }
        s.addSource(GeoJsonSource(HEAT)); s.addSource(GeoJsonSource(AREA)); s.addSource(GeoJsonSource(GUIDE)); s.addSource(GeoJsonSource(TRACKS)); s.addSource(GeoJsonSource(MARKERS))
        val w = Expression.get("w")
        s.addLayer(LineLayer("$HEAT-line", HEAT).withProperties(
            PropertyFactory.lineColor(Expression.interpolate(
                Expression.linear(), w,
                Expression.stop(0f, Expression.color(MapStyle.heatColor(0f, c).toArgb())), Expression.stop(1f, Expression.color(MapStyle.heatColor(1f, c).toArgb())),
            )),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), w, Expression.stop(0f, MapStyle.HEAT_MIN_DP), Expression.stop(1f, MapStyle.HEAT_MAX_DP))),
        ))
        s.addLayer(FillLayer("$AREA-fill", AREA).withProperties(
            PropertyFactory.fillColor(c.primary.toArgb()), PropertyFactory.fillOpacity(MapStyle.AREA_FILL_ALPHA),
        ))
        s.addLayer(LineLayer("$AREA-line", AREA).withProperties(
            PropertyFactory.lineColor(c.primary.toArgb()), PropertyFactory.lineWidth(MapStyle.AREA_LINE_DP),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(LineLayer("$GUIDE-line", GUIDE).withProperties(
            PropertyFactory.lineColor(c.text.toArgb()), PropertyFactory.lineWidth(MapStyle.GUIDE_DOT_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            // dash lengths are in line widths; a zero-length dash with round caps is a dot
            PropertyFactory.lineDasharray(arrayOf(0f, MapStyle.GUIDE_GAP_DP / MapStyle.GUIDE_DOT_DP)),
        ))
        s.addLayer(LineLayer("$TRACKS-casing", TRACKS).withProperties(
            PropertyFactory.lineColor(Color.White.toArgb()), PropertyFactory.lineWidth(MapStyle.TRACK_CASING_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(LineLayer("$TRACKS-line", TRACKS).withProperties(
            PropertyFactory.lineColor(c.primary.toArgb()), PropertyFactory.lineWidth(MapStyle.TRACK_LINE_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        val isPin = Expression.eq(Expression.get("kind"), Expression.literal(MarkerKind.PARKED.name))
        fun markerLayer(id: String, anchor: String, filter: Expression) = SymbolLayer(id, MARKERS).withProperties(
            PropertyFactory.iconImage(Expression.get("kind")), PropertyFactory.iconAnchor(anchor),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.get("z")),
        ).withFilter(filter)
        s.addLayer(markerLayer("$MARKERS-dot", Property.ICON_ANCHOR_CENTER, Expression.not(isPin)))
        s.addLayer(markerLayer("$MARKERS-pin", Property.ICON_ANCHOR_BOTTOM, isPin))
        content?.let(::show)
    }

    fun show(next: MapContent) {
        content = next
        val s = style ?: return
        fun pts(l: List<GeoPoint>) = l.map { Point.fromLngLat(it.lon, it.lat) }
        s.getSourceAs<GeoJsonSource>(HEAT)?.setGeoJson(FeatureCollection.fromFeatures(
            next.heat?.lines.orEmpty().sortedBy { it.weight }.filter { it.points.size >= 2 }.map {
                Feature.fromGeometry(LineString.fromLngLats(pts(it.points))).apply { addNumberProperty("w", it.weight) }
            },
        ))
        s.getSourceAs<GeoJsonSource>(AREA)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(next.area?.let { Feature.fromGeometry(Polygon.fromLngLats(listOf(pts(it.points)))) }),
        ))
        s.getSourceAs<GeoJsonSource>(GUIDE)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(next.guide.takeIf { it.size >= 2 }?.let { Feature.fromGeometry(LineString.fromLngLats(pts(it))) }),
        ))
        s.getSourceAs<GeoJsonSource>(TRACKS)?.setGeoJson(FeatureCollection.fromFeatures(
            next.tracks.filter { it.points.size >= 2 }.map { Feature.fromGeometry(LineString.fromLngLats(pts(it.points))) },
        ))
        s.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(FeatureCollection.fromFeatures(
            allMarkers(next).mapIndexed { i, m ->
                Feature.fromGeometry(Point.fromLngLat(m.position.lon, m.position.lat)).apply {
                    addStringProperty("kind", m.kind.name); addNumberProperty("z", i)
                }
            },
        ))
        fit(next)
    }

    /** CARD: always frame the content; INTERACTIVE: only the first time, so the user's panning stays. */
    private fun fit(next: MapContent) {
        val m = map ?: return
        val b = MapGeometry.bounds(next) ?: return
        if (b == fittedBounds || (mode == MapMode.INTERACTIVE && fittedBounds != null)) return
        val v = view ?: return
        val apply = {
            val pad = (MapStyle.FIT_PADDING_DP * density.density).toInt()
            val update = if (b.isPoint) CameraUpdateFactory.newLatLngZoom(LatLng(b.center.lat, b.center.lon), POINT_ZOOM)
            else CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), pad)
            runCatching { m.moveCamera(update) }.onFailure { Repo.log("map: camera: $it") }
            Unit
        }
        fittedBounds = b
        if (v.width > 0 && v.height > 0) apply() else v.post(apply)
    }

    companion object {
        const val HEAT = "bm-heat"
        const val AREA = "bm-area"
        const val GUIDE = "bm-guide"
        const val TRACKS = "bm-tracks"
        const val MARKERS = "bm-markers"
        const val POINT_ZOOM = 15.0
    }
}

/** Brings the Positron base style to the artboard map colours (land, parks, water, white roads). */
private fun recolor(style: Style, c: AppColors) {
    val land = c.mapLand.toArgb(); val park = c.mapPark.toArgb(); val water = c.mapWater.toArgb()
    val road = Color.White.toArgb(); val casing = c.border.toArgb(); val building = c.track.toArgb()
    for (l in style.layers) {
        val id = l.id
        when (l) {
            is BackgroundLayer -> l.setProperties(PropertyFactory.backgroundColor(land))
            is FillLayer -> when {
                id == "water" -> l.setProperties(PropertyFactory.fillColor(water))
                id == "park" || id.startsWith("landcover_wood") || id.contains("grass") -> l.setProperties(PropertyFactory.fillColor(park))
                id == "landuse_residential" || id.startsWith("road_area") -> l.setProperties(PropertyFactory.fillColor(land))
                id == "building" -> l.setProperties(PropertyFactory.fillColor(building), PropertyFactory.fillOutlineColor(casing))
            }
            is LineLayer -> when {
                id.startsWith("waterway") -> l.setProperties(PropertyFactory.lineColor(water))
                id.startsWith("highway") || id.startsWith("tunnel") -> l.setProperties(
                    PropertyFactory.lineColor(if (id.contains("casing")) casing else road),
                )
            }
        }
    }
}
