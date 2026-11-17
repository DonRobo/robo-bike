package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.proto.Icon as Maneuver

/** A line icon on a 24×24 grid, drawn with round caps/joins like the artboards' inline SVGs. */
@Immutable
class LineIcon(vararg val paths: String, val mirrored: Boolean = false) {
    fun mirror() = LineIcon(*paths, mirrored = !mirrored)
}

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

/** Every icon used in the artboards. Add new ones here so all screens share one set. */
object AppIcons {
    val ChevronRight = LineIcon("M9 6l6 6-6 6")
    val ArrowBack = LineIcon("M20 12H4M10 6l-6 6 6 6")                 // top app bar navigation icon (Material back arrow)
    val ChevronDown = LineIcon("M6 9l6 6 6-6")
    val Check = LineIcon("M5 12l5 5 9-10")
    val Close = LineIcon("M6 6l12 12M18 6L6 18")
    val Info = LineIcon(circle(12f, 12f, 9f), "M12 11v5M12 8v.5")       // ⓘ: opens an explanation sheet (S4)
    val Plus = LineIcon("M12 5v14M5 12h14")
    val Minus = LineIcon("M6 12h12")                                   // setup: skipped permission
    val Bluetooth = LineIcon("M7 7l10 10-5 5V2l5 5L7 17")              // setup: Bluetooth needed
    val Share = LineIcon("M12 15V4M8 8l4-4 4 4M5 13v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5")
    val Warning = LineIcon("M12 4l9 15H3z", "M12 10v4M12 16.5v.5")
    val HandlebarMode = LineIcon("M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4")
    val Expand = LineIcon("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val Tiles = LineIcon(rect(4f, 4f, 7f, 7f, 1.5f), rect(13f, 4f, 7f, 7f, 1.5f), rect(4f, 13f, 7f, 7f, 1.5f), rect(13f, 13f, 7f, 7f, 1.5f))
    val Battery = LineIcon(rect(3f, 8f, 16f, 9f, 2f), "M22 11v3")
    val Bands = LineIcon("M5 6h11M5 10h14M5 14h12", "M5 18h1M9 18h1M13 18h1") // battery bands (R8): solid bars, a dotted one
    val Bulb = LineIcon("M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z")
    val Rain = LineIcon("M7 15a4 4 0 0 1 .5-8 5 5 0 0 1 9.6 1.5A3.5 3.5 0 0 1 17 15z", "M9 18l-1 2M13 18l-1 2M17 18l-1 2")
    val HomeCheck = LineIcon("M4 11l8-7 8 7v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M9 14l2 2 4-4")
    val Wrench = LineIcon("M14.5 6.5a4 4 0 0 0-5.2 5.2L4 17l3 3 5.3-5.3a4 4 0 0 0 5.2-5.2l-2.7 2.7-2.4-.6-.6-2.4z")
    // bottom navigation
    val TabStart = LineIcon("M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z")
    val TabRides = LineIcon(circle(6f, 18f, 2f), circle(18f, 6f, 2f), "M8 18h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6")
    val TabBike = LineIcon(circle(6f, 16f, 3.5f), circle(18f, 16f, 3.5f), "M6 16l4-8h5l3 8M10 8l4.5 8M9 5.5h3")
    val TabSettings = LineIcon("M4 7h8M18 7h2M4 17h2M12 17h8", circle(15f, 7f, 2.5f), circle(9f, 17f, 2.5f))
    // maneuvers (only the left turn is in the artboards; the others follow its style)
    val TurnLeft = LineIcon("M9 6L4 11l5 5M4 11h9a5 5 0 0 1 5 5v3")
    val Straight = LineIcon("M12 20V5M7 10l5-5 5 5")
    val SlightLeft = LineIcon("M8 5H4v4M4 5l7 7v8")
    val SharpLeft = LineIcon("M5 9v6h6M5 15l9-9 3 3v11")
    val UTurn = LineIcon("M8 20V9a4 4 0 0 1 8 0v4M12 9l4 4 4-4")
    val Arrived = LineIcon("M6 21V4M6 4h11l-2 4 2 4H6")
    val Euro = LineIcon("M17 6.5A6 6 0 1 0 17 17.5", "M5 10h8M5 14h8")
    val Bell = LineIcon("M6 16V11a6 6 0 0 1 12 0v5l2 2H4z", "M10 20.5a2 2 0 0 0 4 0")
    val Clock = LineIcon(circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Globe = LineIcon(circle(12f, 12f, 9f), "M3 12h18", "M12 3a13 13 0 0 1 0 18a13 13 0 0 1 0-18z")
    val Palette = LineIcon("M12 3a9 9 0 0 0 0 18c1.1 0 1.6-.8 1.3-1.7l-.4-1A1.7 1.7 0 0 1 14.5 16H17a4 4 0 0 0 4-4c0-5-4-9-9-9z", circle(7.5f, 11f, 1.2f), circle(10f, 7f, 1.2f), circle(15f, 7.5f, 1.2f))
    val Ruler = LineIcon(rect(3f, 8f, 18f, 8f, 1.5f), "M7 8v3M11 8v4M15 8v3M19 8v3")
    val Shield = LineIcon("M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6z", "M9 12l2 2 4-4")
    val Backup = LineIcon("M12 4v11M8 11l4 4 4-4", "M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3")

    fun maneuver(icon: Maneuver): LineIcon = when (icon) {
        Maneuver.STRAIGHT, Maneuver.UNKNOWN -> Straight
        Maneuver.LEFT -> TurnLeft
        Maneuver.RIGHT -> TurnLeft.mirror()
        Maneuver.SLIGHT_LEFT -> SlightLeft
        Maneuver.SLIGHT_RIGHT -> SlightLeft.mirror()
        Maneuver.SHARP_LEFT -> SharpLeft
        Maneuver.SHARP_RIGHT -> SharpLeft.mirror()
        Maneuver.UTURN -> UTurn
        Maneuver.ARRIVED -> Arrived
    }
}

/**
 * Draws a [LineIcon]. [strokeWidth] is in grid units like the SVG `stroke-width` (the artboards use
 * 1.8 for tab/badge icons, 2 for chevrons and most others, 2.2–2.6 for maneuver arrows).
 */
@Composable
fun AppIcon(
    icon: LineIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Float = 2f,
    tint: Color = LocalContentColor.current,
) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.then(sem).size(size)) {
        val s = this.size.width / 24f
        withTransform({
            if (icon.mirrored) scale(-1f, 1f)
        }) {
            scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                paths.forEach { drawPath(it, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        }
    }
}
