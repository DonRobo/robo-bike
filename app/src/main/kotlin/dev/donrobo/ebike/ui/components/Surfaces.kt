package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.theme.AppDimens
import dev.donrobo.ebike.ui.theme.AppTheme

/** Semantic colour sets for banners, chips, badges and status dots. */
enum class Tone { POSITIVE, WARNING, NEUTRAL, DANGER }

data class ToneColors(val container: Color, val content: Color, val dot: Color)

@Composable
fun Tone.colors(): ToneColors {
    val m = MaterialTheme.colorScheme
    return when (this) {
        Tone.POSITIVE -> ToneColors(m.primaryContainer, m.onPrimaryContainer, m.primary)
        Tone.WARNING -> AppTheme.colors.let { c -> ToneColors(c.accentTint, c.onAccentTint, c.accent) } // fixed amber, not the scheme
        Tone.NEUTRAL -> ToneColors(m.surfaceVariant, m.onSurfaceVariant, m.outline)
        Tone.DANGER -> ToneColors(m.errorContainer, m.onErrorContainer, m.error)
    }
}

/**
 * Material [Card] in the tonal surface container (Pixel settings groups): no border, radius 24 (`shapes.large`).
 * [contentPadding]/[gap] lay out the content column. With [onClick] the whole card is one Material click target
 * (ripple, button semantics).
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(AppDimens.cardPadding),
    gap: Dp = 0.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = color, contentColor = MaterialTheme.colorScheme.onSurface)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
    }
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = colors, content = body)
    else Card(modifier.fillMaxWidth(), shape = shape, colors = colors, content = body)
}

/** Hero card (radius 28 = `shapes.extraLarge`, padding 20): Start range card, Reichweite summary. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, gap: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    AppCard(modifier, MaterialTheme.shapes.extraLarge, PaddingValues(20.dp), gap, onClick = onClick, content = content)

/**
 * Card holding Material list items separated by inset dividers (settings groups, radio lists). Put each row in
 * [ListGroupScope.item]; rows are [AppListItem]-based ([ListRow], [SwitchRow], [RadioRow], [ValueRow]) and bring
 * their own 16 dp padding, so the card adds none horizontally and the ripple spans the full card width.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
    title: String? = null,
    content: ListGroupScope.() -> Unit,
) {
    val items = ListGroupScope().apply(content).items
    AppCard(modifier, contentPadding = contentPadding) {
        if (title != null) Text(title, style = AppTheme.type.cardTitle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
        items.forEachIndexed { i, item ->
            if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
            item()
        }
    }
}

class ListGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { items += content }
}

/** Material [HorizontalDivider] (1 dp, `outlineVariant`). */
@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier)

/** Small heading above a group: settings sections (14/500 in the primary colour, margin 10 4 0) and date headings in lists. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text,
        modifier.padding(start = if (large) 0.dp else 4.dp, top = if (large) 8.dp else 10.dp),
        style = if (large) AppTheme.type.labelStrong else AppTheme.type.sectionLabel,
        color = AppTheme.colors.primary,
    )
}

/**
 * Tinted info line with an icon, e.g. "Du kommst heim: 4,2 km bis nach Hause" (padding 10/12,
 * radius 14, 14/600). [action] adds a trailing text button ("Ändern").
 */
@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.POSITIVE,
    icon: LineIcon? = null,
    large: Boolean = false,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val tc = tone.colors()
    Surface(
        modifier.fillMaxWidth(),
        shape = if (large) MaterialTheme.shapes.large else MaterialTheme.shapes.small,
        color = tc.container, contentColor = tc.content,
    ) {
        Row(
            Modifier.padding(
                if (large) PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                else PaddingValues(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 10.dp),
        ) {
            if (icon != null) AppIcon(icon, null, size = 20.dp)
            Text(text, Modifier.weight(1f), style = AppTheme.type.label)
            action?.invoke(this)
        }
    }
}

/** Rounded square with a centred icon (light card 44/r14, nav card 52/r16 filled, setup 44). */
@Composable
fun IconBadge(
    icon: LineIcon,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    radius: Dp = 14.dp,
    iconSize: Dp = 24.dp,
    strokeWidth: Float = 1.8f,
    container: Color = AppTheme.colors.accentTint,
    content: Color = AppTheme.colors.onAccentTint,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(container),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icon, null, size = iconSize, strokeWidth = strokeWidth, tint = content) }
}

/**
 * Material determinate [LinearProgressIndicator] (battery, maintenance): rounded ends, the track in
 * `secondaryContainer`, no stop dot.
 */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.secondaryContainer,
) = LinearProgressIndicator(
    progress = { fraction.coerceIn(0f, 1f) },
    modifier = modifier.fillMaxWidth().height(height),
    color = color, trackColor = track, strokeCap = StrokeCap.Round,
    gapSize = if (height > 8.dp) 4.dp else 2.dp,
    drawStopIndicator = {},
)

/** Multi-segment bar (a small chart, not a control): segments of (fraction, colour) laid out from the left. */
@Composable
fun SegmentBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    track: Color = AppTheme.colors.track,
    roundSegments: Boolean = false,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(modifier.fillMaxWidth().height(height).clip(shape).background(track)) {
        var used = 0f
        segments.forEach { (f, c) ->
            val w = f.coerceIn(0f, 1f - used)
            used += w
            if (w > 0f) Box(
                Modifier.weight(w).fillMaxHeight()
                    .then(if (roundSegments) Modifier.clip(shape) else Modifier).background(c),
            )
        }
        if (used < 1f) Box(Modifier.weight(1f - used))
    }
}

/** Grey box with monospace lines (Maps debug, log excerpt). */
@Composable
fun MonoBlock(lines: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { lines.forEach { Text(it, style = AppTheme.type.mono, color = AppTheme.colors.text) } }
}

/** Grey inner panel (radius 14, padding 12) used inside cards: error entry, route comparison cells. */
@Composable
fun InsetPanel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(12.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.background).padding(padding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
