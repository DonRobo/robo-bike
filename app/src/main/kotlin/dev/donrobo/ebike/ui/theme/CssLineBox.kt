package dev.donrobo.ebike.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnitType
import kotlin.math.roundToInt

/**
 * Single-line text with a CSS line box: Compose never makes a line shorter than the font's
 * ascent + descent, but the artboards use `line-height` < 1 on big numbers (e.g. 80px / 0.95). This
 * sets the height to [style]'s line height and centres the glyphs (negative leading split evenly, as in
 * CSS); baselines move along, so baseline alignment still works. No-op without a smaller line height.
 */
fun Modifier.cssLineBox(style: TextStyle): Modifier {
    val lh = style.lineHeight
    val fs = style.fontSize
    if (lh.type == TextUnitType.Unspecified || fs.type != TextUnitType.Sp) return this
    val factor = if (lh.type == TextUnitType.Em) lh.value else lh.value / fs.value
    return layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val box = (fs.toPx() * factor).roundToInt()
        if (box >= p.height) return@layout layout(p.width, p.height) { p.place(0, 0) }
        layout(p.width, box) { p.place(0, (box - p.height) / 2) }
    }
}
