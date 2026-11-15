package dev.donrobo.ebike.ui.bike

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.errors.ErrorEntry
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.maintenance.DueLevel
import dev.donrobo.ebike.maintenance.MaintenanceDue
import dev.donrobo.ebike.maintenance.MaintenanceItem
import dev.donrobo.ebike.maintenance.MaintenanceKind
import dev.donrobo.ebike.maintenance.DueWording
import dev.donrobo.ebike.maintenance.dueDistance
import dev.donrobo.ebike.maintenance.dueWording
import dev.donrobo.ebike.maintenance.nameRes
import dev.donrobo.ebike.maintenance.res
import dev.donrobo.ebike.notify.driveErrorText
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.SwitchRow
import androidx.compose.material3.MaterialTheme
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppSwitch
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.KeyValueRow
import dev.donrobo.ebike.ui.components.PageHeader
import dev.donrobo.ebike.ui.components.ProgressBar
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.StatusChip
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.ValueRow
import dev.donrobo.ebike.ui.components.map.BikeMap
import dev.donrobo.ebike.ui.components.map.MapContent
import dev.donrobo.ebike.ui.components.map.MapMarker
import dev.donrobo.ebike.ui.components.map.MarkerKind
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import dev.donrobo.ebike.ui.components.currentLocale

/** User actions on the Rad tab. */
interface BikeActions {
    fun selectBike(id: String)
    fun renameBike(name: String)
    fun addBike()
    fun removeBike()
    fun navigateTo(point: GeoPoint)
    fun fixLocation()
    fun markDone(itemId: String)
    /** [itemId] null = add a new own item. [name] null = keep the name. */
    fun saveItem(itemId: String?, name: String?, intervalKm: Double)
    fun removeItem(itemId: String)
    fun setChargeRemind(on: Boolean)
    fun setChargeThreshold(pct: Int)
    fun setBatteryWh(wh: Int)
    fun forgetDisplay()
    fun pairDisplay()
}

/** Dialogs and sheets of the Rad tab; hoisted so screenshot tests can show each one. */
sealed interface BikeDialog {
    data object Bikes : BikeDialog
    data object Rename : BikeDialog
    data object Remove : BikeDialog
    data class EditItem(val itemId: String) : BikeDialog
    data object AddItem : BikeDialog
    data object Threshold : BikeDialog
    data object Capacity : BikeDialog
    data object Forget : BikeDialog
}

/** Name of a maintenance item in the current language. */
@Composable
fun maintenanceName(kind: MaintenanceKind, customName: String): String = kind.nameRes()?.let { stringResource(it) } ?: customName

/** "Fällig in 46 km" / "Jetzt fällig" / "Überfällig seit 12 km" ([dueWording]). */
@Composable
fun maintenanceDueText(remainingKm: Double, fmt: UnitFormatter): String {
    val w = dueWording(remainingKm, fmt)
    return if (w == DueWording.DUE_NOW) stringResource(w.res()) else stringResource(w.res(), dueDistance(remainingKm, fmt).text())
}

@Composable fun maintenanceName(item: MaintenanceItem) = maintenanceName(item.kind, item.name)
@Composable fun maintenanceName(due: MaintenanceDue) = maintenanceName(due.kind, due.name)

/** Stateless Rad tab (artboard Rad). [dialog] is the open dialog, if any. */
@Composable
fun BikeContent(state: BikeUiState, actions: BikeActions, dialog: BikeDialog?, onDialog: (BikeDialog?) -> Unit, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    Screen(
        modifier = modifier,
        header = { Header(state, fmt) { onDialog(BikeDialog.Bikes) } },
    ) {
        ParkedCard(state.parked, actions)
        MaintenanceCard(state, fmt, actions, onDialog)
        state.errors?.let { ErrorsCard(it) }
        BatteryCard(state, onDialog, actions)
        DisplayCard(state)
        DisplayAction(state, actions, onDialog)
    }
    if (dialog != null) BikeDialogHost(dialog, state, actions, onDialog = onDialog)
}

@Composable
private fun Header(state: BikeUiState, fmt: UnitFormatter, onSwitch: () -> Unit) {
    PageHeader(
        state.bike?.name ?: stringResource(R.string.bike_title),
        subtitle = state.odoKm?.let { stringResource(R.string.bike_total, fmt.distance(it, 0).text()) } ?: stringResource(R.string.bike_total_unknown),
        trailing = {
            AppButton(
                stringResource(R.string.bike_switch), onSwitch, Modifier.padding(end = 8.dp),
                kind = ButtonKind.OUTLINED, size = ButtonSize.SMALL, fillWidth = false,
            )
        },
    )
}

// --- W3 ------------------------------------------------------------------------------------------------------------

@Composable
private fun ParkedCard(parked: ParkedUi, actions: BikeActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    AppCard(contentPadding = PaddingValues(0.dp)) {
        if (parked is ParkedUi.Known) {
            val desc = stringResource(R.string.bike_parked_map)
            BikeMap(
                MapContent(markers = listOf(MapMarker(parked.point, MarkerKind.PARKED))),
                Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = desc },
                onClick = { actions.navigateTo(parked.point) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (title, lines) = when (parked) {
                    ParkedUi.ConnectedNow -> stringResource(R.string.bike_parked_connected_title) to listOf(stringResource(R.string.bike_parked_connected_sub))
                    ParkedUi.Never -> stringResource(R.string.bike_parked_never_title) to listOf(stringResource(R.string.bike_parked_never_sub))
                    is ParkedUi.NoPosition -> stringResource(R.string.bike_parked_nopos_title) to listOf(
                        switchedOffText(parked.atMs),
                        stringResource(
                            when (parked.reason) {
                                NoPositionReason.NO_PERMISSION -> R.string.bike_parked_reason_permission
                                NoPositionReason.NEEDS_BACKGROUND -> R.string.bike_parked_reason_background
                                NoPositionReason.UNAVAILABLE -> R.string.bike_parked_reason_unavailable
                            },
                        ),
                    )
                    is ParkedUi.Known -> stringResource(R.string.bike_parked_title) to listOfNotNull(
                        switchedOffText(parked.atMs),
                        parked.staleFixAtMs?.let { stringResource(R.string.bike_parked_stale, if (it == 0L) "?" else shortDateTime(it)) },
                        parked.accuracyM?.let { stringResource(R.string.bike_parked_coarse, it) },
                    )
                }
                Text(title, style = t.cardTitle)
                lines.forEachIndexed { i, l ->
                    val warn = parked is ParkedUi.Known && i > 0
                    Text(l, style = t.bodySmall, color = if (warn) c.onAccentTint else c.textMuted)
                }
            }
            when {
                parked is ParkedUi.Known ->
                    AppButton(stringResource(R.string.bike_parked_navigate), { actions.navigateTo(parked.point) }, kind = ButtonKind.OUTLINED, size = ButtonSize.SMALL, fillWidth = false)
                parked is ParkedUi.NoPosition && parked.reason != NoPositionReason.UNAVAILABLE ->
                    AppButton(stringResource(R.string.common_allow), actions::fixLocation, kind = ButtonKind.OUTLINED, size = ButtonSize.SMALL, fillWidth = false)
            }
        }
    }
}

/** "Ausgeschaltet gestern um 22:22". */
@Composable
private fun switchedOffText(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(currentLocale()).format(at)
    return when (relativeDay(at.toLocalDate(), LocalDate.now(zone))) {
        RelativeDay.TODAY -> stringResource(R.string.bike_parked_today, time)
        RelativeDay.YESTERDAY -> stringResource(R.string.bike_parked_yesterday, time)
        RelativeDay.EARLIER -> stringResource(R.string.bike_parked_date, dateText(atMs), time)
    }
}

/** "3. Oktober" (with the year when it is not this year). */
@Composable
private fun dateText(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val pattern = stringResource(if (d.year == LocalDate.now(zone).year) R.string.bike_date_pattern else R.string.bike_date_pattern_year)
    return DateTimeFormatter.ofPattern(pattern, currentLocale()).format(d)
}

@Composable
private fun shortDateTime(atMs: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(currentLocale()).format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

// --- W1 ------------------------------------------------------------------------------------------------------------

@Composable
private fun MaintenanceCard(state: BikeUiState, fmt: UnitFormatter, actions: BikeActions, onDialog: (BikeDialog?) -> Unit) {
    // No side padding on the card: each item is a full-width click target (ripple to the card edges).
    AppCard(contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp), gap = 4.dp) {
        val inset = Modifier.padding(horizontal = 16.dp)
        Text(stringResource(R.string.bike_maint_title), inset.padding(bottom = 4.dp), style = AppTheme.type.cardTitle)
        if (state.odoKm == null && state.maintenance.isNotEmpty()) {
            Text(stringResource(R.string.bike_maint_no_odo), inset, style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
        }
        if (state.maintenance.isEmpty()) Text(stringResource(R.string.bike_maint_empty), inset, style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
        state.maintenance.forEach { row ->
            MaintenanceItemRow(row, fmt, onDone = { actions.markDone(row.status.item.id) }) { onDialog(BikeDialog.EditItem(row.status.item.id)) }
        }
        Hairline(inset.padding(top = 4.dp))
        AppButton(
            stringResource(R.string.bike_maint_add), { onDialog(BikeDialog.AddItem) }, Modifier.padding(horizontal = 4.dp),
            kind = ButtonKind.TEXT, size = ButtonSize.MEDIUM, icon = AppIcons.Plus, fillWidth = false,
        )
    }
}

@Composable
private fun MaintenanceItemRow(row: MaintenanceRow, fmt: UnitFormatter, onDone: () -> Unit, onEdit: () -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val s = row.status
    val name = maintenanceName(s.item)
    val remaining = s.remainingKm
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onEdit).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row.showDone && remaining != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = t.itemTitleStrong)
                    val overdue = s.level == DueLevel.OVERDUE
                    Text(
                        maintenanceDueText(remaining, fmt),
                        style = t.label, color = if (overdue) c.danger else c.onAccentTint,
                    )
                }
                AppButton(stringResource(R.string.bike_maint_done), onDone, size = ButtonSize.SMALL, fillWidth = false)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, Modifier.weight(1f).alignBy(FirstBaseline), style = t.itemTitleStrong)
                Text(
                    if (remaining != null) stringResource(R.string.bike_maint_in, fmt.distance(remaining, 0).text())
                    else stringResource(R.string.bike_maint_every, fmt.distance(s.item.intervalKm, 0).text()),
                    Modifier.alignBy(FirstBaseline), style = t.bodySmall, color = c.textMuted,
                )
            }
        }
        s.fraction?.let { f ->
            val color = when (s.level) {
                DueLevel.OK -> c.primary
                DueLevel.DUE_SOON -> c.accent
                DueLevel.OVERDUE -> c.danger
            }
            ProgressBar(f, height = 8.dp, color = color, track = c.track)
        }
    }
}

// --- W2 ------------------------------------------------------------------------------------------------------------

private const val ERRORS_COLLAPSED = 3

@Composable
private fun ErrorsCard(e: ErrorSection) {
    val c = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppCard(gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.bike_errors_title), Modifier.weight(1f), style = AppTheme.type.cardTitle)
            when (e.health) {
                ErrorHealth.OK -> StatusChip(stringResource(R.string.bike_errors_ok), icon = AppIcons.Check)
                ErrorHealth.ERROR -> StatusChip(stringResource(R.string.bike_errors_fault), tone = Tone.DANGER, icon = AppIcons.Warning)
                ErrorHealth.UNKNOWN -> {}
            }
        }
        e.current?.let { cur ->
            val code = codeText(cur)
            val line = when {
                !e.currentIsLive -> stringResource(R.string.bike_errors_last_connection, code)
                cur.atMs != null -> stringResource(R.string.bike_errors_now_since, shortDateTime(cur.atMs), code)
                else -> stringResource(R.string.bike_errors_now, code)
            }
            ErrorPanel(cur, line, highlighted = true)
        }
        val shown = if (expanded) e.history else e.history.take(ERRORS_COLLAPSED)
        shown.forEach { r ->
            val line = if (r.source == ErrorEntry.Source.LIVE && r.atMs != null) stringResource(R.string.bike_errors_live_line, dateText(r.atMs), codeText(r))
            else stringResource(R.string.bike_errors_history_line, codeText(r))
            ErrorPanel(r, line, highlighted = false)
        }
        if (e.current == null && e.history.isEmpty()) {
            Text(
                stringResource(if (e.health == ErrorHealth.UNKNOWN) R.string.bike_errors_unknown else R.string.bike_errors_none),
                style = AppTheme.type.bodySmall, color = c.textMuted,
            )
        }
        if (e.history.size > ERRORS_COLLAPSED) {
            AppButton(
                if (expanded) stringResource(R.string.bike_errors_less) else stringResource(R.string.bike_errors_more, e.history.size),
                { expanded = !expanded }, kind = ButtonKind.TEXT, size = ButtonSize.SMALL,
            )
        }
    }
}

/** "Code 21", or the raw byte when it does not decode. The code is always shown: the byte encoding is unverified. */
@Composable
private fun codeText(r: ErrorRow): String =
    r.code?.let { stringResource(R.string.bike_errors_code, it) } ?: stringResource(R.string.bike_errors_raw, r.rawHex)

@Composable
private fun errorTexts(r: ErrorRow): Pair<String, String> =
    driveErrorText(LocalContext.current, r.code).let { it.title to it.advice }

@Composable
private fun ErrorPanel(r: ErrorRow, line: String, highlighted: Boolean) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val (title, advice) = errorTexts(r)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (highlighted) c.accentTint else c.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(line, style = t.captionStrong, color = if (highlighted) c.danger else c.textMuted)
        Text(title, style = t.body.copy(fontWeight = t.cardTitle.fontWeight))
        Text(advice, style = t.bodySmall, color = c.textMuted)
        if (r.source == ErrorEntry.Source.HISTORY && r.atMs != null) {
            Text(stringResource(R.string.bike_errors_history_note, dateText(r.atMs)), style = t.caption, color = c.textMuted)
        }
    }
}

// --- W4 + capacity ---------------------------------------------------------------------------------------------------

@Composable
private fun BatteryCard(state: BikeUiState, onDialog: (BikeDialog?) -> Unit, actions: BikeActions) {
    val t = AppTheme.type
    AppCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        SwitchRow(
            stringResource(R.string.bike_charge_title), state.chargeRemind, actions::setChargeRemind,
            subtitle = stringResource(R.string.bike_charge_sub, state.chargeThresholdPct),
            titleStyle = t.itemTitleStrong, subtitleStyle = t.bodySmall,
        )
        if (state.chargeRemind) {
            ValueRow(stringResource(R.string.bike_charge_threshold), stringResource(R.string.percent_value, state.chargeThresholdPct), { onDialog(BikeDialog.Threshold) })
        }
        ValueRow(
            stringResource(R.string.bike_capacity),
            if (state.batteryWh > 0) stringResource(R.string.bike_capacity_value, state.batteryWh) else stringResource(R.string.bike_capacity_unset),
            { onDialog(BikeDialog.Capacity) },
        )
    }
}

// --- W5 + E6 ---------------------------------------------------------------------------------------------------------

@Composable
private fun DisplayCard(state: BikeUiState) {
    val d = state.display
    AppCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), gap = 4.dp) {
        Text(stringResource(R.string.bike_display_title), Modifier.padding(bottom = 4.dp), style = AppTheme.type.cardTitle)
        if (d == null) {
            Text(stringResource(R.string.bike_display_unknown), Modifier.padding(bottom = 8.dp), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
            return@AppCard
        }
        val rows = listOfNotNull(
            d.model?.let { R.string.bike_display_model to it },
            d.hardware?.let { R.string.bike_display_hardware to it },
            d.software?.let { R.string.bike_display_software to it },
            d.serialEnd?.let { R.string.bike_display_serial to stringResource(R.string.bike_display_serial_end, it) },
            d.controller?.let { R.string.bike_display_controller to it },
        )
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) Hairline()
            KeyValueRow(stringResource(label), value)
        }
    }
}

@Composable
private fun DisplayAction(state: BikeUiState, actions: BikeActions, onDialog: (BikeDialog?) -> Unit) {
    val muted = AppTheme.colors.textMuted
    if (state.bike == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.hasDisplay) {
            AppButton(stringResource(R.string.common_forget_display), { onDialog(BikeDialog.Forget) }, kind = ButtonKind.DANGER_OUTLINED, size = ButtonSize.MEDIUM)
            Text(stringResource(R.string.common_forget_display_hint), Modifier.fillMaxWidth(), style = AppTheme.type.caption, color = muted, textAlign = TextAlign.Center)
        } else {
            AppButton(stringResource(R.string.bike_display_pair), actions::pairDisplay, size = ButtonSize.MEDIUM)
            Text(stringResource(R.string.bike_display_none_sub), Modifier.fillMaxWidth(), style = AppTheme.type.caption, color = muted, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
    }
}
