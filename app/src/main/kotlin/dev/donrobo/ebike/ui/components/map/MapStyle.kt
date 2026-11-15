package dev.donrobo.ebike.ui.components.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.donrobo.ebike.ui.theme.AppColors

/**
 * Overlay look, taken from the map artboards (Reichweite, Fahrt, Statistik, Rad; 1 SVG unit = 1 dp).
 * Used by both the MapLibre view and the stand-in, so they cannot drift apart.
 */
internal object MapStyle {
    const val TRACK_CASING_DP = 10f
    const val TRACK_LINE_DP = 5.5f
    const val HEAT_MIN_DP = 4f
    const val HEAT_MAX_DP = 8f
    const val HEAT_MIN_ALPHA = 0.35f
    const val HEAT_MAX_ALPHA = 0.85f
    const val RING_FILL_ALPHA = 0.12f
    const val RING_LINE_DP = 2.5f
    const val RING_DASH_DP = 7f
    const val RING_GAP_DP = 6f
    /** Padding around the content when the camera fits it. */
    const val FIT_PADDING_DP = 28f

    fun heatWidth(w: Float) = HEAT_MIN_DP + (HEAT_MAX_DP - HEAT_MIN_DP) * w
    fun heatAlpha(w: Float) = HEAT_MIN_ALPHA + (HEAT_MAX_ALPHA - HEAT_MIN_ALPHA) * w

    /**
     * Heat line colour: the artboard's primary at [heatAlpha], pre-blended onto the land colour and drawn
     * opaque, so overlapping segments of one way do not darken at their joints.
     */
    fun heatColor(w: Float, c: AppColors): Color = lerp(c.mapLand, c.primary, heatAlpha(w))

    /** Size of a marker's image in dp and where its anchor lies inside it (fraction of width/height). */
    data class MarkerBox(val widthDp: Float, val heightDp: Float, val anchorX: Float, val anchorY: Float)

    fun box(kind: MarkerKind): MarkerBox = when (kind) {
        MarkerKind.CURRENT -> MarkerBox(22f, 22f, 0.5f, 0.5f)
        MarkerKind.HOME -> MarkerBox(30f, 30f, 0.5f, 0.5f)
        MarkerKind.PARKED -> MarkerBox(32f, 46f, 0.5f, 44.5f / 46f)
        MarkerKind.TRACK_START -> MarkerBox(22f, 22f, 0.5f, 0.5f)
        MarkerKind.TRACK_END -> MarkerBox(22f, 22f, 0.5f, 0.5f)
    }

    /** Draws [kind] with its anchor at [at] (px). */
    fun DrawScope.drawMarker(kind: MarkerKind, at: Offset, c: AppColors) {
        val dp = density
        translate(at.x, at.y) {
            when (kind) {
                MarkerKind.CURRENT, MarkerKind.TRACK_END -> {
                    drawCircle(c.primary, 9 * dp, Offset.Zero)
                    drawCircle(Color.White, 9 * dp, Offset.Zero, style = Stroke(3 * dp))
                }
                MarkerKind.TRACK_START -> {
                    drawCircle(Color.White, 8 * dp, Offset.Zero)
                    drawCircle(c.primary, 8 * dp, Offset.Zero, style = Stroke(4 * dp))
                }
                MarkerKind.HOME -> {
                    drawCircle(Color.White, 13 * dp, Offset.Zero)
                    drawCircle(c.text, 13 * dp, Offset.Zero, style = Stroke(2 * dp))
                    val house = Path().apply {
                        moveTo(-6 * dp, 0f); lineTo(0f, -5 * dp); lineTo(6 * dp, 0f); lineTo(6 * dp, 5 * dp); lineTo(-6 * dp, 5 * dp); close()
                    }
                    drawPath(house, c.text, style = Stroke(1.8f * dp, join = StrokeJoin.Round))
                }
                MarkerKind.PARKED -> {
                    // artboard pin: tip at (0, 22) = the anchor
                    translate(0f, -22 * dp) {
                        val pin = Path().apply {
                            moveTo(0f, 22 * dp)
                            cubicTo(-16 * dp, 6 * dp, -16 * dp, -20 * dp, 0f, -20 * dp)
                            cubicTo(16 * dp, -20 * dp, 16 * dp, 6 * dp, 0f, 22 * dp)
                            close()
                        }
                        drawPath(pin, c.primary)
                        drawPath(pin, Color.White, style = Stroke(2.5f * dp, join = StrokeJoin.Round, cap = StrokeCap.Round))
                        drawCircle(Color.White, 5 * dp, Offset(0f, -5 * dp))
                    }
                }
            }
        }
    }

    /** The marker as a bitmap (for MapLibre symbol images), anchor per [box]. */
    fun markerImage(kind: MarkerKind, c: AppColors, density: Density): ImageBitmap {
        val b = box(kind)
        val w = (b.widthDp * density.density).toInt(); val h = (b.heightDp * density.density).toInt()
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawMarker(kind, Offset(w * b.anchorX, h * b.anchorY), c)
        }
        return img
    }
}
