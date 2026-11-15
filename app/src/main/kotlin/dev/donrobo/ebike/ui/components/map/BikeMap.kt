package dev.donrobo.ebike.ui.components.map

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ui.components.map.MapStyle.drawMarker
import dev.donrobo.ebike.ui.theme.AppColors
import dev.donrobo.ebike.ui.theme.AppTheme

enum class MapMode {
    /** Embedded in a card: no gestures; the whole map is one tap target ([BikeMap] onClick). */
    CARD,
    /** Full screen: pan/zoom, and tap-to-pick when onPick is set. */
    INTERACTIVE,
}

/**
 * The app's map (MapLibre Native + OpenFreeMap vector tiles, no key). Draws [content] on top of the base
 * map in the artboards' look and fits the camera to it (in [MapMode.INTERACTIVE] only once, so picking
 * or panning is not undone). Attribution is always visible.
 *
 * Under Robolectric and in previews it renders [MapStandIn] (same overlays on a neutral ground), so
 * screenshot tests of screens with maps need nothing special.
 *
 * @param onClick CARD mode: tapping the map (e.g. open the full-screen map).
 * @param onPick INTERACTIVE mode: tapping picks that point (e.g. set the home location).
 */
@Composable
fun BikeMap(
    content: MapContent,
    modifier: Modifier = Modifier,
    mode: MapMode = MapMode.CARD,
    onClick: (() -> Unit)? = null,
    onPick: ((GeoPoint) -> Unit)? = null,
) {
    val standIn = LocalInspectionMode.current || isRobolectric
    Box(modifier.clipToBounds().background(AppTheme.colors.mapLand)) {
        if (standIn) MapStandIn(content, Modifier.matchParentSize())
        else MapLibreMap(content, mode, onPick, Modifier.matchParentSize())
        if (mode == MapMode.CARD && onClick != null) Box(Modifier.matchParentSize().clickable(onClick = onClick))
        MapAttribution(Modifier.align(Alignment.BottomEnd), linked = mode == MapMode.INTERACTIVE)
    }
}

private val isRobolectric: Boolean = Build.FINGERPRINT == "robolectric"

/** OpenStreetMap/OpenMapTiles credit; in interactive mode it opens the OSM copyright page. */
@Composable
private fun MapAttribution(modifier: Modifier, linked: Boolean) {
    val ctx = LocalContext.current
    // inset, so rounded card corners do not clip it
    var m = modifier.padding(end = 10.dp, bottom = 6.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
    if (linked) m = m.clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Text(
        stringResource(R.string.map_attribution), m.padding(horizontal = 6.dp, vertical = 2.dp),
        color = AppTheme.colors.textMuted, style = AppTheme.type.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
    )
}

private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"

/**
 * Deterministic stand-in for the native map (Robolectric cannot render MapLibre): the land colour as
 * neutral ground and every overlay drawn exactly as on the real map, with the same camera fit.
 */
@Composable
fun MapStandIn(content: MapContent, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Canvas(modifier) {
        drawRect(c.mapLand)
        val b = MapGeometry.bounds(content) ?: return@Canvas
        val proj = MapGeometry.Projection(b, size.width, size.height, MapStyle.FIT_PADDING_DP * density)
        drawOverlays(content, proj, c)
    }
}

private fun DrawScope.drawOverlays(content: MapContent, proj: MapGeometry.Projection, c: AppColors) {
    fun path(points: List<GeoPoint>) = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(proj.x(p), proj.y(p)) else lineTo(proj.x(p), proj.y(p)) }
    }
    fun line(width: Float) = Stroke(width * density, cap = StrokeCap.Round, join = StrokeJoin.Round)

    content.heat?.lines?.sortedBy { it.weight }?.forEach { l ->
        drawPath(path(l.points), MapStyle.heatColor(l.weight, c), style = line(MapStyle.heatWidth(l.weight)))
    }
    content.ring?.let { r ->
        val o = Offset(proj.x(r.center), proj.y(r.center))
        val rad = proj.px(r.radiusM, r.center.lat)
        drawCircle(c.primary.copy(alpha = MapStyle.RING_FILL_ALPHA), rad, o)
        drawCircle(
            c.primary, rad, o,
            style = Stroke(
                MapStyle.RING_LINE_DP * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(MapStyle.RING_DASH_DP * density, MapStyle.RING_GAP_DP * density)),
            ),
        )
    }
    for (t in content.tracks) {
        if (t.points.size < 2) continue
        val p = path(t.points)
        drawPath(p, Color.White, style = line(MapStyle.TRACK_CASING_DP))
        drawPath(p, c.primary, style = line(MapStyle.TRACK_LINE_DP))
    }
    for (m in allMarkers(content)) drawMarker(m.kind, Offset(proj.x(m.position), proj.y(m.position)), c)
}

/** Explicit markers plus the start/end dots of tracks, in drawing order (later = on top). */
internal fun allMarkers(content: MapContent): List<MapMarker> {
    val ends = content.tracks.filter { it.showEnds && it.points.size >= 2 }.flatMap {
        listOf(MapMarker(it.points.first(), MarkerKind.TRACK_START), MapMarker(it.points.last(), MarkerKind.TRACK_END))
    }
    val order = listOf(MarkerKind.HOME, MarkerKind.PARKED, MarkerKind.CURRENT)
    return ends + content.markers.sortedBy { order.indexOf(it.kind) }
}
