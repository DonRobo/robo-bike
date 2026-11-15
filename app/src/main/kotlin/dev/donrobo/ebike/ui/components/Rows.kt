package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.theme.AppDimens
import dev.donrobo.ebike.ui.theme.AppTheme

/** Muted chevron (20 dp) at the end of navigating rows. */
@Composable
fun Chevron(size: Dp = 20.dp) = AppIcon(AppIcons.ChevronRight, null, size = size, tint = AppTheme.colors.textMuted)

/**
 * Colour of a settings row's icon, by what the row is about: [AMBER] light and money, [DANGER] faults, [PRIMARY]
 * the bike, its range and rides, [TERTIARY] connections and outside services, [SECONDARY] display and phone
 * options, [NEUTRAL] technical pages. Scheme roles, so they follow the Robo Bike or wallpaper scheme; amber is the
 * fixed harmonized one.
 */
enum class RowTint { PRIMARY, SECONDARY, TERTIARY, AMBER, DANGER, NEUTRAL }

/** Icon of a settings row: a tonal rounded square with the line icon (Pixel settings), coloured by [tint]. */
@Composable
fun RowIcon(icon: LineIcon, tint: RowTint = RowTint.PRIMARY) {
    val m = MaterialTheme.colorScheme
    val c = AppTheme.colors
    val (container, content) = when (tint) {
        RowTint.PRIMARY -> m.primaryContainer to m.onPrimaryContainer
        RowTint.SECONDARY -> m.secondaryContainer to m.onSecondaryContainer
        RowTint.TERTIARY -> m.tertiaryContainer to m.onTertiaryContainer
        RowTint.AMBER -> c.accentTint to c.onAccentTint
        RowTint.DANGER -> m.errorContainer to m.onErrorContainer
        RowTint.NEUTRAL -> m.surfaceContainerHighest to m.onSurfaceVariant
    }
    IconBadge(icon, size = 40.dp, radius = 12.dp, iconSize = 22.dp, container = container, content = content)
}

/** The [RowIcon] as a list item's leading slot, or none. */
private fun rowLeading(icon: LineIcon?, tint: RowTint): (@Composable () -> Unit)? {
    if (icon == null) return null
    return { RowIcon(icon, tint) }
}

/**
 * Material 3 [ListItem] with the app's text styles and a transparent container (it sits on cards and sheets).
 * Interaction goes on [modifier] (`clickable`, `toggleable`, `selectable`), so the whole row is the target and
 * gets the ripple; [leading]/[trailing] are visual (icon, radio, switch, value + chevron).
 */
@Composable
fun AppListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = AppTheme.type.itemTitle,
    subtitleStyle: TextStyle = AppTheme.type.caption,
    subtitleColor: Color = AppTheme.colors.textMuted,
) {
    // Subtitle inside the headline slot: ListItem then sizes to its content (min 56 dp) and centres leading/trailing
    // vertically, like the system settings, instead of switching to its fixed 88 dp top-aligned three-line layout
    // whenever a subtitle wraps.
    ListItem(
        headlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = titleStyle)
                if (subtitle != null) Text(subtitle, style = subtitleStyle, color = subtitleColor)
            }
        },
        modifier = modifier.fillMaxWidth(),
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Navigating list item: title, optional subtitle and [icon] badge, chevron. [value] shows a muted value before the
 * chevron ("Sprache … Deutsch ›"). Use inside [ListGroup].
 */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: LineIcon? = null,
    tint: RowTint = RowTint.PRIMARY,
) {
    AppListItem(
        title, modifier.clickable(role = Role.Button, onClick = onClick), subtitle,
        leading = rowLeading(icon, tint),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value != null) Text(value, style = AppTheme.type.body, color = AppTheme.colors.textMuted)
                Chevron()
            }
        },
    )
}

/**
 * List item with a Material [Switch]; the whole row toggles (role Switch). Inside a [ListGroup], a sheet, or via
 * [SwitchCard] as a card of its own. [icon] or [leading]: an icon badge at the start.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle = AppTheme.type.itemTitle,
    subtitleStyle: TextStyle = AppTheme.type.caption,
    minHeight: Dp = AppDimens.listRowHeight,
    leading: (@Composable () -> Unit)? = null,
    icon: LineIcon? = null,
    tint: RowTint = RowTint.PRIMARY,
) {
    AppListItem(
        title,
        modifier.heightIn(min = minHeight).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        subtitle, leading = leading ?: rowLeading(icon, tint),
        trailing = { Switch(checked, onCheckedChange = null, enabled = enabled) },
        titleStyle = titleStyle, subtitleStyle = subtitleStyle,
    )
}

/** A card holding one [SwitchRow] (Navigation, Fahrten GPS, Lenkermodus auto-start). */
@Composable
fun SwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle = AppTheme.type.cardTitle,
    subtitleStyle: TextStyle = AppTheme.type.bodySmall,
    verticalPadding: Dp = 4.dp,
) {
    AppCard(modifier, contentPadding = PaddingValues(vertical = verticalPadding)) {
        SwitchRow(title, checked, onCheckedChange, subtitle = subtitle, enabled = enabled, titleStyle = titleStyle, subtitleStyle = subtitleStyle)
    }
}

/**
 * "Heimatort … Festgelegt ›" list item (15 sp label, muted value, chevron) with an optional divider above. Like all
 * list items it brings its own 16 dp side padding: put it in a card with no horizontal padding.
 */
@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        if (divider) Hairline(Modifier.padding(horizontal = 16.dp))
        AppListItem(
            label, Modifier.clickable(role = Role.Button, onClick = onClick), titleStyle = AppTheme.type.bodyStrong,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = AppTheme.type.body, color = AppTheme.colors.textMuted)
                    Chevron(18.dp)
                }
            },
        )
    }
}

/**
 * Widens the element by [horizontal] beyond both sides of its parent's padding, so a full-width list item
 * ([ValueRow], [SwitchRow]) inside a padded card reaches the card edges and its own 16 dp padding lines up with the
 * card's content.
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val px = horizontal.roundToPx()
    val c = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.maxWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px) else constraints
    val p = measurable.measure(c)
    layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}

/** Label/value line of the device info list (padding 10/0, 15 sp, value semibold right-aligned). */
@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = AppTheme.type.body, color = AppTheme.colors.textMuted)
        Text(value, Modifier.weight(1f), style = AppTheme.type.bodyStrong, textAlign = TextAlign.End)
    }
}

/** Card section heading with optional muted description (17/700 + 14 muted, gap 2). */
@Composable
fun CardTitle(title: String, modifier: Modifier = Modifier, description: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTheme.type.cardTitle)
            if (description != null) Text(description, style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
        }
        trailing?.invoke(this)
    }
}
