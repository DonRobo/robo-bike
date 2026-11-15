package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Material 3 [Switch]. With [onCheckedChange] = null it is display-only: the surrounding row is the toggle
 * target (see [SwitchRow]), as Material recommends.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Switch(checked, onCheckedChange, modifier.then(sem), enabled = enabled)
}

/** Kept for source compatibility: [SegmentedStyle.HEADER] renders [HeaderTabs] (Liste/Statistik). */
enum class SegmentedStyle { CARD, HEADER }

/**
 * One-of-n choice. [SegmentedStyle.CARD]: Material segmented buttons (single choice); [SegmentedStyle.HEADER]:
 * primary tabs under the top bar, Material's control for switching between views of one section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentedStyle = SegmentedStyle.CARD,
    enabled: Boolean = true,
) {
    if (style == SegmentedStyle.HEADER) return HeaderTabs(options, selectedIndex, onSelect, modifier)
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                enabled = enabled,
                // No check mark: the labels are short numbers/words and must fit three or four to a row.
                icon = {},
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
            )
        }
    }
}

/** Material primary tabs (Liste | Statistik under a page's top bar). */
@Composable
fun HeaderTabs(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    PrimaryTabRow(selectedIndex, modifier.fillMaxWidth(), containerColor = Color.Transparent) {
        options.forEachIndexed { i, label ->
            Tab(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                text = { Text(label, style = AppTheme.type.bodyStrong, maxLines = 1) },
                unselectedContentColor = AppTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Single-select Material [FilterChip] (period, maneuver). [unselectedTextColor] for the unselected label. No leading
 * check mark: selection is the tonal fill only, so a chip keeps its width and a wrapping group never re-flows.
 */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unselectedTextColor: Color = AppTheme.colors.textMuted,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(labelColor = unselectedTextColor),
    )
}

/** Wrapping group of single-select [PillChip]s, gap 8 (Statistik period, Diagnose maneuvers). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PillChipGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unselectedTextColor: Color = AppTheme.colors.textMuted,
) {
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> PillChip(o, i == selectedIndex, { onSelect(i) }, unselectedTextColor = unselectedTextColor) }
    }
}

/** Material outlined icon button for − / + (steppers on Reichweite, Licht, Rad). */
@Composable
fun StepperButton(icon: LineIcon, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedIconButton(onClick, modifier, enabled = enabled, border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)) {
        AppIcon(icon, contentDescription, size = 20.dp, strokeWidth = 2f)
    }
}

/** "− value +" with Material outlined icon buttons. */
@Composable
fun Stepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseDescription: String,
    increaseDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepperButton(AppIcons.Minus, decreaseDescription, onDecrease)
        Text(value, Modifier.width(60.dp), style = AppTheme.type.bodyStrong, textAlign = TextAlign.Center)
        StepperButton(AppIcons.Plus, increaseDescription, onIncrease)
    }
}

/**
 * Material button kinds: PRIMARY = filled [Button], TONAL = [FilledTonalButton], OUTLINED = [OutlinedButton],
 * TEXT = [TextButton]; the DANGER_ variants are the same buttons in the error colour (destructive actions).
 */
enum class ButtonKind { PRIMARY, TONAL, OUTLINED, DANGER_OUTLINED, TEXT, DANGER_TEXT }

/** Container heights: Material's 40 dp default, 48 for actions in cards, 56 for the one main action of a page. */
enum class ButtonSize(val minHeight: Dp) { SMALL(40.dp), MEDIUM(48.dp), LARGE(52.dp), HERO(56.dp) }

/**
 * A Material 3 button of [kind]. [fillWidth] = full width (stacked page actions); [icon] leads the label.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    size: ButtonSize = ButtonSize.LARGE,
    enabled: Boolean = true,
    icon: LineIcon? = null,
    fillWidth: Boolean = true,
) {
    val m = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = size.minHeight)
    val style = when (size) {
        ButtonSize.SMALL -> AppTheme.type.label
        ButtonSize.MEDIUM -> AppTheme.type.bodyStrong
        ButtonSize.LARGE, ButtonSize.HERO -> AppTheme.type.buttonLarge
    }
    val padding = if (kind == ButtonKind.TEXT || kind == ButtonKind.DANGER_TEXT) ButtonDefaults.TextButtonContentPadding
    else PaddingValues(horizontal = if (size == ButtonSize.SMALL) 16.dp else 24.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            AppIcon(icon, null, size = ButtonDefaults.IconSize, strokeWidth = 2f)
            androidx.compose.foundation.layout.Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, style = style, textAlign = TextAlign.Center)
    }
    val error = MaterialTheme.colorScheme.error
    when (kind) {
        ButtonKind.PRIMARY -> Button(onClick, m, enabled, contentPadding = padding, content = content)
        ButtonKind.TONAL -> FilledTonalButton(onClick, m, enabled, contentPadding = padding, content = content)
        ButtonKind.OUTLINED -> OutlinedButton(onClick, m, enabled, contentPadding = padding, content = content)
        ButtonKind.DANGER_OUTLINED -> OutlinedButton(
            onClick, m, enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
            border = BorderStroke(1.dp, if (enabled) AppTheme.colors.dangerBorder else MaterialTheme.colorScheme.outlineVariant),
            contentPadding = padding, content = content,
        )
        ButtonKind.TEXT -> TextButton(onClick, m, enabled, contentPadding = padding, content = content)
        ButtonKind.DANGER_TEXT -> TextButton(onClick, m, enabled, colors = ButtonDefaults.textButtonColors(contentColor = error), contentPadding = padding, content = content)
    }
}

/** Radio option as a Material list item: the whole row selects, the [RadioButton] shows the state. */
@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    AppListItem(
        label,
        modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        subtitle,
        leading = { RadioButton(selected, onClick = null) },
    )
}

/** Setup progress: "Schritt 1 von 3" over a Material determinate [LinearProgressIndicator]. */
@Composable
fun StepIndicator(label: String, step: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = AppTheme.type.label, color = AppTheme.colors.textMuted)
        LinearProgressIndicator(
            progress = { step / total.toFloat().coerceAtLeast(1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}
