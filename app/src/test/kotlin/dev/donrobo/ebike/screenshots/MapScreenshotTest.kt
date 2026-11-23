package dev.donrobo.ebike.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.HeatLines
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.map.MapMarker
import dev.donrobo.ebike.ui.components.map.MapArea
import dev.donrobo.ebike.ui.components.map.MapGeometry
import dev.donrobo.ebike.ui.components.map.MapTrack
import dev.donrobo.ebike.ui.components.map.MarkerKind
import dev.donrobo.ebike.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map component's Robolectric stand-in with each overlay type (track, heatmap, area + guide, markers). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val home = GeoPoint(48.2085, 16.3721)

    /** Polyline through offsets in metres (east, north) from [home]. */
    private fun path(vararg m: Pair<Int, Int>): List<GeoPoint> {
        val pts = m.map { (e, n) -> GeoPoint(home.lat + n / 111_320.0, home.lon + e / (111_320.0 * Math.cos(Math.toRadians(home.lat)))) }
        // densify like 1 Hz samples, so the heatmap grid sees a continuous track
        return pts.zipWithNext().flatMap { (a, b) -> (0 until 20).map { GeoPoint(a.lat + (b.lat - a.lat) * it / 20, a.lon + (b.lon - a.lon) * it / 20) } } + pts.last()
    }

    // the Fahrt artboard's route shape
    private val ride = path(0 to 0, 960 to -40, 880 to 1100, 2140 to 1180, 2240 to 2000, 2840 to 2040)

    @Composable
    private fun Card(title: String, content: MapContent) {
        Column {
            Text(title, style = AppTheme.type.label, color = AppTheme.colors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            BikeMap(content, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp)))
        }
    }

    @Test @Config(qualifiers = "de-w390dp-h1000dp-port-xhdpi")
    fun map_overlays() = compose.screenshot("map_overlays") {
        val heat = HeatLines.build(
            listOf(ride, ride, ride, ride.take(60), path(960 to -40, 1500 to -60, 1600 to 600), path(880 to 1100, 300 to 1150, 250 to 1700)),
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card("Track (F3)", MapContent(tracks = listOf(MapTrack(ride))))
            Card("Heatmap (F8)", MapContent(heat = heat))
            val bike = GeoPoint(home.lat + 0.008, home.lon + 0.007)
            Card(
                "Area + guide + current + home (R4, R5)",
                MapContent(
                    area = MapArea(MapGeometry.ellipse(bike, home, 1_800.0)!!),
                    guide = MapGeometry.ellipseGuide(bike, home, 1_800.0)!!,
                    markers = listOf(MapMarker(bike, MarkerKind.CURRENT), MapMarker(home, MarkerKind.HOME)),
                ),
            )
            Card("Parked bike (W3)", MapContent(markers = listOf(MapMarker(home, MarkerKind.PARKED))))
        }
    }
}
