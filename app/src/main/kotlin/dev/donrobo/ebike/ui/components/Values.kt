package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.cssLineBox

/** Big number with a smaller unit on the same baseline ("64 km", "23 km/h"). */
@Composable
fun NumberWithUnit(
    value: String,
    unit: String?,
    numberStyle: TextStyle,
    unitStyle: TextStyle,
    modifier: Modifier = Modifier,
    gap: Dp = 4.dp,
    color: Color = AppTheme.colors.text,
    unitColor: Color = AppTheme.colors.textMuted,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        Text(value, Modifier.alignBy(LastBaseline).cssLineBox(numberStyle), style = numberStyle, color = color, maxLines = 1, softWrap = false)
        if (unit != null) Text(unit, Modifier.alignBy(LastBaseline), style = unitStyle, color = unitColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Sizes of [ValueTile] in the artboards. */
enum class ValueTileStyle { START, RIDE, HANDLEBAR }

/**
 * Label + number + unit tile. START: white bordered, padding 14/16, label 13/600, number 30.
 * RIDE: padding 12/16, number 28. HANDLEBAR: grey fill without border, radius 22, label 16, number 48.
 */
@Composable
fun ValueTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier, style: ValueTileStyle = ValueTileStyle.START, muted: Boolean = false) {
    val color = if (muted) AppTheme.colors.textMuted else AppTheme.colors.text
    val t = AppTheme.type
    when (style) {
        ValueTileStyle.START, ValueTileStyle.RIDE -> AppCard(
            modifier,
            contentPadding = if (style == ValueTileStyle.START) PaddingValues(horizontal = 16.dp, vertical = 14.dp) else PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            gap = if (style == ValueTileStyle.START) 4.dp else 2.dp,
        ) {
            Text(label, style = t.captionStrong, color = AppTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            NumberWithUnit(value, unit, if (style == ValueTileStyle.START) t.numberTile else t.numberMedium, t.unit, color = color)
        }
        ValueTileStyle.HANDLEBAR -> AppCard(
            modifier, shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), gap = 2.dp,
            color = AppTheme.colors.background,
        ) {
            Text(label, style = t.itemTitle, color = AppTheme.colors.textMuted)
            NumberWithUnit(value, unit, t.numberTileLarge, t.unitTileLarge, color = color)
        }
    }
}

/** Number over a caption, no unit row (week summary, statistics: "26,6 / Kilometer"). */
@Composable
fun StatColumn(value: String, caption: String, modifier: Modifier = Modifier, numberStyle: TextStyle = AppTheme.type.numberMedium, color: Color = AppTheme.colors.text, captionColor: Color = AppTheme.colors.textMuted) {
    Column(modifier) {
        Text(value, Modifier.cssLineBox(numberStyle), style = numberStyle, color = color, maxLines = 1)
        Text(caption, style = AppTheme.type.caption, color = captionColor)
    }
}

/**
 * Small status chip: battery pill on Start (LARGE: padding 8/12, radius 16, 16/700, 20 dp icon),
 * "Alles in Ordnung" (MEDIUM: 4/10, radius 10, 13/700), ride tag "Arbeitsweg" (SMALL: 2/8, radius 8, 12/700).
 */
enum class ChipSize { SMALL, MEDIUM, LARGE }

@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.POSITIVE, icon: LineIcon? = null, size: ChipSize = ChipSize.MEDIUM) {
    val tc = tone.colors()
    val t = AppTheme.type
    val (pad, radius, style) = when (size) {
        ChipSize.SMALL -> Triple(PaddingValues(horizontal = 8.dp, vertical = 2.dp), 8.dp, t.tab.copy(fontWeight = t.labelStrong.fontWeight))
        ChipSize.MEDIUM -> Triple(PaddingValues(horizontal = 10.dp, vertical = 4.dp), 10.dp, t.sectionLabel)
        ChipSize.LARGE -> Triple(PaddingValues(horizontal = 12.dp, vertical = 8.dp), 16.dp, t.itemTitleStrong)
    }
    Row(
        modifier.clip(RoundedCornerShape(radius)).background(tc.container).padding(pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides tc.content) {
            if (icon != null) AppIcon(icon, null, size = if (size == ChipSize.LARGE) 20.dp else 16.dp)
            Text(text, style = style, color = tc.content, maxLines = 1)
        }
    }
}

/** Coloured 8 dp dot + text: connection status, "Läuft". */
@Composable
fun StatusDot(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.POSITIVE, style: TextStyle = AppTheme.type.label, gap: Dp = 8.dp) {
    val tc = tone.colors()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tc.dot))
        Text(text, style = style, color = if (tone == Tone.NEUTRAL) AppTheme.colors.textMuted else tc.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
