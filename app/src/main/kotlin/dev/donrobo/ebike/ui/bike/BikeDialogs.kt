package dev.donrobo.ebike.ui.bike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.bike.BatteryCapacity
import dev.donrobo.ebike.bike.ChargeSettings
import dev.donrobo.ebike.maintenance.Maintenance
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppDialog
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppTextField
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.CancelButton
import dev.donrobo.ebike.ui.components.ConfirmDialog
import dev.donrobo.ebike.ui.components.DialogButton
import dev.donrobo.ebike.ui.components.DialogButtonRow
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.ListRow
import dev.donrobo.ebike.ui.components.RadioRow
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.Stepper
import dev.donrobo.ebike.ui.components.TextInputDialog
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.components.currentLocale

/** Shows [dialog] as a Material dialog or, for the bike list, a modal bottom sheet. */
@Composable
fun BikeDialogHost(dialog: BikeDialog, state: BikeUiState, actions: BikeActions, onDialog: (BikeDialog?) -> Unit) {
    val onDismiss = { onDialog(null) }
    when (dialog) {
        BikeDialog.Bikes -> BikesSheet(state, actions, onDialog)
        BikeDialog.Rename -> TextInputDialog(
            stringResource(R.string.bike_rename_title), state.bike?.name.orEmpty(),
            onSave = { actions.renameBike(it.trim().take(40)); onDismiss() }, onDismiss = onDismiss,
            label = stringResource(R.string.bike_name_label), valid = { it.isNotBlank() },
        )
        BikeDialog.Remove -> RemoveDialog(state, actions, onDismiss)
        is BikeDialog.EditItem -> ItemDialog(state, dialog.itemId, actions, onDismiss)
        BikeDialog.AddItem -> ItemDialog(state, null, actions, onDismiss)
        BikeDialog.Threshold -> ThresholdDialog(state, actions, onDismiss)
        BikeDialog.Capacity -> CapacityDialog(state, actions, onDismiss)
        BikeDialog.Forget -> ConfirmDialog(
            stringResource(R.string.bike_forget_title), stringResource(R.string.bike_forget_body, state.bike?.name.orEmpty()),
            stringResource(R.string.bike_forget_confirm), onConfirm = { onDismiss(); actions.forgetDisplay() }, onDismiss = onDismiss,
        )
    }
}

@Composable
private fun Body(text: String) = Text(text, style = AppTheme.type.body, color = AppTheme.colors.textMuted)

// --- bike management (A2) --------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikesSheet(state: BikeUiState, actions: BikeActions, onDialog: (BikeDialog?) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onDialog(null) }) { BikesSheetContent(state, actions, onDialog) }
}

/** "Rad wechseln": pick the bike the app shows (and connects to), rename it, add another, remove it. */
@Composable
private fun BikesSheetContent(state: BikeUiState, actions: BikeActions, onDialog: (BikeDialog?) -> Unit) {
    val active = state.bike
    // Sheet content is full-bleed Material list items; only the title and the remove button are inset.
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.bike_sheet_title), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = AppTheme.type.barTitle)
        Column(Modifier.selectableGroup()) {
            state.bikes.forEach { b -> RadioRow(b.name, b.id == active?.id, { actions.selectBike(b.id) }) }
        }
        Hairline(Modifier.padding(vertical = 8.dp))
        if (active != null) ListRow(stringResource(R.string.bike_rename), { onDialog(BikeDialog.Rename) }, value = active.name)
        ListRow(stringResource(R.string.bike_add), { onDialog(null); actions.addBike() }, subtitle = stringResource(R.string.bike_add_sub))
        if (active != null) AppButton(
            stringResource(R.string.bike_remove), { onDialog(BikeDialog.Remove) }, Modifier.padding(horizontal = 8.dp),
            kind = ButtonKind.DANGER_TEXT, size = ButtonSize.MEDIUM, fillWidth = false,
        )
    }
}

@Composable
private fun RemoveDialog(state: BikeUiState, actions: BikeActions, onDismiss: () -> Unit) {
    AppDialog(
        stringResource(R.string.bike_remove_title, state.bike?.name.orEmpty()), onDismiss,
        confirm = { DialogButton(stringResource(R.string.bike_remove_confirm), { onDismiss(); actions.removeBike() }, danger = true) },
        dismiss = { CancelButton(onDismiss) },
    ) {
        Body(stringResource(R.string.bike_remove_body))
        if (state.bikes.size <= 1) Body(stringResource(R.string.bike_remove_last))
    }
}

// --- maintenance (W1) ------------------------------------------------------------------------------------------------

/** Edit an item ([itemId]) or add an own one (null). */
@Composable
private fun ItemDialog(state: BikeUiState, itemId: String?, actions: BikeActions, onDismiss: () -> Unit) {
    val fmt = rememberUnitFormatter()
    val status = itemId?.let { id -> state.maintenance.firstOrNull { it.status.item.id == id }?.status }
    if (itemId != null && status == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }; return } // item removed meanwhile
    val item = status?.item
    val originalName = item?.let { maintenanceName(it) }.orEmpty()
    var name by rememberSaveable { mutableStateOf(originalName) }
    var interval by rememberSaveable { mutableStateOf(item?.let { Maintenance.intervalInUnits(it.intervalKm, fmt.system).toString() }.orEmpty()) }
    val loc = currentLocale()
    val intervalKm = Maintenance.parseInterval(interval, fmt.system, loc)
    val intervalError = interval.isNotBlank() && intervalKm == null
    val valid = intervalKm != null && name.isNotBlank()
    val unit = unitLabel(fmt.distanceUnit)
    AppDialog(
        stringResource(if (item == null) R.string.bike_maint_add_title else R.string.bike_maint_edit_title), onDismiss,
        confirm = {
            DialogButton(
                stringResource(R.string.common_save),
                { actions.saveItem(itemId, name.trim().takeIf { it != originalName }, intervalKm!!); onDismiss() },
                enabled = valid,
            )
        },
        dismiss = { CancelButton(onDismiss) },
    ) {
        AppTextField(
            name, { name = it.take(Maintenance.MAX_NAME_LENGTH) },
            label = stringResource(R.string.bike_maint_name), placeholder = stringResource(R.string.bike_maint_name_hint),
        )
        AppTextField(
            interval, { interval = it.take(8) }, label = stringResource(R.string.bike_maint_interval), suffix = unit, number = true,
            error = intervalError,
            message = if (intervalError) stringResource(
                R.string.bike_maint_interval_invalid,
                fmt.distance(Maintenance.MIN_INTERVAL_KM, 0).text(), fmt.distance(Maintenance.MAX_INTERVAL_KM, 0).text(),
            ) else null,
        )
        val used = status?.usedKm
        if (status != null && used != null) {
            Body(stringResource(R.string.bike_maint_progress, fmt.distance(used, 0).text(), fmt.distance(status.item.intervalKm, 0).text()))
        }
        // Secondary actions of an existing item sit under the fields, not in the dialog's button row.
        val odo = state.odoKm
        if (item != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (odo != null) {
                AppButton(
                    stringResource(R.string.bike_maint_mark_done), { actions.markDone(item.id); onDismiss() },
                    kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM, icon = AppIcons.Check,
                )
                Text(stringResource(R.string.bike_maint_mark_done_sub, fmt.distance(odo, 0).text()), style = AppTheme.type.caption, color = AppTheme.colors.textMuted)
            }
            AppButton(
                stringResource(R.string.bike_maint_remove), { actions.removeItem(item.id); onDismiss() },
                kind = ButtonKind.DANGER_TEXT, size = ButtonSize.MEDIUM, fillWidth = false,
            )
        }
    }
}

// --- W4 + capacity ---------------------------------------------------------------------------------------------------

@Composable
private fun ThresholdDialog(state: BikeUiState, actions: BikeActions, onDismiss: () -> Unit) {
    var pct by rememberSaveable { mutableIntStateOf(state.chargeThresholdPct) }
    AppDialog(
        stringResource(R.string.bike_charge_threshold_title), onDismiss,
        confirm = { DialogButton(stringResource(R.string.common_save), { actions.setChargeThreshold(pct); onDismiss() }) },
        dismiss = { CancelButton(onDismiss) },
    ) {
        Body(stringResource(R.string.bike_charge_threshold_body))
        Stepper(
            stringResource(R.string.percent_value, pct),
            onDecrease = { pct = (pct - ChargeSettings.STEP_PCT).coerceAtLeast(ChargeSettings.MIN_PCT) },
            onIncrease = { pct = (pct + ChargeSettings.STEP_PCT).coerceAtMost(ChargeSettings.MAX_PCT) },
            decreaseDescription = stringResource(R.string.bike_less),
            increaseDescription = stringResource(R.string.bike_more),
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
        )
        Text(stringResource(R.string.bike_charge_sub, pct), style = AppTheme.type.caption, color = AppTheme.colors.textMuted)
    }
}

@Composable
private fun CapacityDialog(state: BikeUiState, actions: BikeActions, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = Wh, 1 = V x Ah
    var wh by rememberSaveable { mutableStateOf(state.batteryWh.takeIf { it > 0 }?.toString().orEmpty()) }
    var volt by rememberSaveable { mutableStateOf("") }
    var ah by rememberSaveable { mutableStateOf("") }
    val loc = currentLocale()
    val result = if (mode == 0) BatteryCapacity.parseWh(wh, loc) else BatteryCapacity.parseVoltAh(volt, ah, loc)
    val touched = if (mode == 0) wh.isNotBlank() else volt.isNotBlank() && ah.isNotBlank()
    val message = when {
        !touched -> null
        result is BatteryCapacity.Result.Ok -> if (mode == 1) stringResource(R.string.bike_capacity_result, result.wh) else null
        result is BatteryCapacity.Result.OutOfRange -> stringResource(R.string.bike_capacity_range)
        else -> stringResource(R.string.bike_capacity_invalid)
    }
    val error = touched && result !is BatteryCapacity.Result.Ok
    AppDialog(
        stringResource(R.string.bike_capacity), onDismiss,
        confirm = {
            DialogButton(
                stringResource(R.string.common_save),
                { (result as? BatteryCapacity.Result.Ok)?.let { actions.setBatteryWh(it.wh) }; onDismiss() },
                enabled = result is BatteryCapacity.Result.Ok,
            )
        },
        dismiss = { CancelButton(onDismiss) },
    ) {
        Body(stringResource(R.string.bike_capacity_intro))
        SegmentedControl(listOf(stringResource(R.string.bike_capacity_mode_wh), stringResource(R.string.bike_capacity_mode_vah)), mode, { mode = it })
        if (mode == 0) {
            AppTextField(
                wh, { wh = it.take(6) }, label = stringResource(R.string.bike_capacity_wh), suffix = "Wh", number = true,
                error = error, placeholder = "500", message = message,
            )
        } else {
            DialogButtonRow {
                AppTextField(volt, { volt = it.take(5) }, Modifier.weight(1f), label = stringResource(R.string.bike_capacity_volt), suffix = "V", number = true, error = error, placeholder = "36")
                AppTextField(ah, { ah = it.take(5) }, Modifier.weight(1f), label = stringResource(R.string.bike_capacity_ah), suffix = "Ah", number = true, error = error, placeholder = "14")
            }
            if (message != null) Text(message, style = AppTheme.type.label, color = if (error) AppTheme.colors.danger else AppTheme.colors.primaryStrong)
        }
        if (state.batteryWh > 0) AppButton(
            stringResource(R.string.bike_capacity_clear), { actions.setBatteryWh(0); onDismiss() },
            kind = ButtonKind.DANGER_TEXT, size = ButtonSize.MEDIUM, fillWidth = false,
        )
    }
}
