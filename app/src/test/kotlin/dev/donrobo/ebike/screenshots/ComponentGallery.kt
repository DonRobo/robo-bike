package dev.donrobo.ebike.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.PreviewNavigator
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppTextField
import dev.donrobo.ebike.ui.components.HeaderTabs
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppSwitch
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.ChipSize
import dev.donrobo.ebike.ui.components.KeyValueRow
import dev.donrobo.ebike.ui.components.ListGroup
import dev.donrobo.ebike.ui.components.ListRow
import dev.donrobo.ebike.ui.components.MonoBlock
import dev.donrobo.ebike.ui.components.PillChipGroup
import dev.donrobo.ebike.ui.components.ProgressBar
import dev.donrobo.ebike.ui.components.RadioRow
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.components.SegmentBar
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.StatColumn
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.StatusChip
import dev.donrobo.ebike.ui.components.StatusDot
import dev.donrobo.ebike.ui.components.StepIndicator
import dev.donrobo.ebike.ui.components.Stepper
import dev.donrobo.ebike.ui.components.SwitchCard
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.ValueRow
import dev.donrobo.ebike.ui.components.ValueTile
import dev.donrobo.ebike.ui.components.ValueTileStyle
import dev.donrobo.ebike.ui.theme.AppTheme

/** Every shared component once, for the components screenshot (a catalogue for feature agents). */
@Composable
fun ComponentGallery() {
    Column {
        BackHeader("BackHeader", PreviewNavigator::back)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(gap = 12.dp) {
                CardTitle("CardTitle", description = "with description") { StatusDot("StatusDot") }
                SegmentedControl(listOf("10 Fahrten", "20 Fahrten", "50 Fahrten"), 1, {})
                PillChipGroup(listOf("Woche", "Monat", "Jahr"), 1, {})
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("75 %", icon = AppIcons.Battery, size = ChipSize.LARGE)
                    StatusChip("Alles in Ordnung", icon = AppIcons.Check)
                    StatusChip("Arbeitsweg", size = ChipSize.SMALL)
                }
                StatusBanner("StatusBanner positive", icon = AppIcons.HomeCheck)
                StatusBanner("StatusBanner warning", tone = Tone.WARNING, icon = AppIcons.Warning)
                ProgressBar(0.75f)
                SegmentBar(listOf(0.75f to AppTheme.colors.primary, 0.13f to AppTheme.colors.accent))
                Row(Modifier.fillMaxWidth()) {
                    Text("Stepper", Modifier.weight(1f).padding(top = 12.dp), style = AppTheme.type.bodyStrong)
                    Stepper("10 km", {}, {}, "−", "+")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ValueTile("START", "23", "km/h", Modifier.weight(1f))
                ValueTile("RIDE", "10,6", "km", Modifier.weight(1f), ValueTileStyle.RIDE)
                ValueTile("HANDLEBAR", "75", "%", Modifier.weight(1f), ValueTileStyle.HANDLEBAR)
            }
            SwitchCard("SwitchCard", true, {}, subtitle = "subtitle")
            SectionLabel("SectionLabel")
            ListGroup {
                item { ListRow("ListRow", {}, subtitle = "with subtitle") }
                item { ListRow("ListRow", {}, value = "Deutsch") }
                item { SwitchRow("SwitchRow", false, {}, subtitle = "off") }
                item { RadioRow("RadioRow", true, {}) }
                item { ValueRow("ValueRow", "Festgelegt", {}, divider = false) }
            }
            AppCard(gap = 12.dp) {
                HeaderTabs(listOf("Liste", "Statistik"), 0, {})
                KeyValueRow("KeyValueRow", "B02NC_1.01")
                AppTextField("", {}, label = "AppTextField", suffix = "km")
                AppTextField("12", {}, label = "error", error = true, message = "supporting text")
            }
            AppCard(contentPadding = PaddingValues(16.dp), gap = 10.dp) {
                StepIndicator("Schritt 1 von 3", 1, 3)
                AppButton("PRIMARY", {})
                AppButton("PRIMARY HERO", {}, size = ButtonSize.HERO)
                AppButton("TONAL", {}, kind = ButtonKind.TONAL, size = ButtonSize.MEDIUM, icon = AppIcons.Share)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("OUTLINED", {}, Modifier.weight(1f), kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM)
                    AppButton("DANGER", {}, Modifier.weight(1f), kind = ButtonKind.DANGER_OUTLINED, size = ButtonSize.MEDIUM)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppButton("TEXT", {}, kind = ButtonKind.TEXT, size = ButtonSize.SMALL, fillWidth = false)
                    AppButton("DANGER_TEXT", {}, kind = ButtonKind.DANGER_TEXT, size = ButtonSize.SMALL, fillWidth = false)
                    AppSwitch(true, {})
                    AppSwitch(false, {})
                }
                MonoBlock(listOf("21:45:56 Maps nav started: LEFT, 50 m", "classifier (arrow shape): STRAIGHT"))
                StatColumn("26,6", "Kilometer")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    AppIcons.ChevronRight, AppIcons.ChevronDown, AppIcons.Check, AppIcons.Close, AppIcons.Plus, AppIcons.Share,
                    AppIcons.Warning, AppIcons.Battery, AppIcons.Bulb, AppIcons.Rain, AppIcons.Wrench, AppIcons.Tiles,
                ).forEach { AppIcon(it, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    AppIcons.TurnLeft, AppIcons.TurnLeft.mirror(), AppIcons.Straight, AppIcons.SlightLeft, AppIcons.SharpLeft,
                    AppIcons.UTurn, AppIcons.Arrived, AppIcons.HomeCheck, AppIcons.HandlebarMode, AppIcons.Expand,
                ).forEach { AppIcon(it, null, strokeWidth = 2.2f) }
            }
        }
    }
}
