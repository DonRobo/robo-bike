package dev.donrobo.ebike.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.R
import dev.donrobo.ebike.clock.ClockSettings
import dev.donrobo.ebike.counters.CounterSettings
import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.counters.descriptionRes
import dev.donrobo.ebike.counters.labelRes
import dev.donrobo.ebike.ui.components.LineIcon
import dev.donrobo.ebike.display.rememberDisplayCapabilities
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.notify.NotifySettings
import dev.donrobo.ebike.settings.AppLanguage
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.AppDialog
import dev.donrobo.ebike.ui.components.CancelButton
import dev.donrobo.ebike.ui.components.ListGroup
import dev.donrobo.ebike.ui.components.ListRow
import dev.donrobo.ebike.ui.components.PageHeader
import dev.donrobo.ebike.ui.components.RadioRow
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.RowTint
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.stats.ElectricityPriceDialog
import dev.donrobo.ebike.ui.stats.formatPrice
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitSystem
import dev.donrobo.ebike.widget.BikeWidget
import kotlinx.coroutines.flow.map
import dev.donrobo.ebike.ui.components.rememberActiveBike

/** What the Einstellungen list can do; implemented by [SettingsScreen], a no-op in the screenshot test. */
interface SettingsActions {
    /** A sub-screen ([Routes]) or, for a tab root, that tab. */
    fun open(route: String)
    fun setConnectionDetails(on: Boolean)
    fun setDriveErrorAlerts(on: Boolean)
    fun setAutoConnect(on: Boolean)
    fun setClockAuto(on: Boolean)
    fun openReminders()
    fun editPrice()
    fun pickLanguage()
    fun pickUnits()
    fun setWallpaperColors(on: Boolean)
    fun pickStartCounter() {}
    fun pickWidgetCounter() {}
}

/** Einstellungen (artboard Einstellungen, S1/S3/A5): every row opens a real screen or changes a stored setting. */
@Composable
fun SettingsScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bikes = ctx.bikes
    val bike by rememberActiveBike()
    val allBikes by remember { bikes.allFlow() }.collectAsStateWithLifecycle(bikes.all())
    // Every value below is a stored setting: one flow of "anything changed" keeps the list current.
    val tick by remember { settings.anyChange().map { System.nanoTime() } }.collectAsStateWithLifecycle(0L)
    val caps = rememberDisplayCapabilities(bike?.id)
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // The language lives in the system's per-app locale store, not in Settings: re-read after the picker closes.
    val language = remember(dialog) { AppLanguage.current() }

    tick // read so the state is rebuilt on every settings change
    val state = settingsUiState(settings, allBikes, bike?.id, caps, language)
    val actions = remember(nav) {
        object : SettingsActions {
            override fun open(route: String) = Tab.entries.firstOrNull { it.route == route }?.let(nav::switchTab) ?: nav.open(route)
            override fun setConnectionDetails(on: Boolean) { settings[NotifySettings.connectionDetails] = on }
            override fun setDriveErrorAlerts(on: Boolean) { settings[NotifySettings.driveError] = on }
            override fun setAutoConnect(on: Boolean) = BikeCompanion.setAutoConnect(ctx, on)
            override fun setClockAuto(on: Boolean) { settings[ClockSettings.auto] = on }
            override fun openReminders() { dialog = SettingsDialog.REMINDERS }
            override fun editPrice() { dialog = SettingsDialog.PRICE }
            override fun pickLanguage() { dialog = SettingsDialog.LANGUAGE }
            override fun pickUnits() { dialog = SettingsDialog.UNITS }
            override fun pickStartCounter() { dialog = SettingsDialog.START_COUNTER }
            override fun pickWidgetCounter() { dialog = SettingsDialog.WIDGET_COUNTER }
            override fun setWallpaperColors(on: Boolean) {
                settings[GeneralSettings.wallpaperColors] = on
                BikeWidget.refresh(ctx) // the widget is drawn in the app's colours too
            }
        }
    }
    SettingsContent(state, actions)

    val close = { dialog = null }
    when (dialog) {
        SettingsDialog.LANGUAGE -> ChoiceDialog(
            stringResource(R.string.settings_language), AppLanguage.entries, language, { languageLabel(it) },
            onPick = { AppLanguage.set(it); close() }, onDismiss = close,
        )
        SettingsDialog.UNITS -> ChoiceDialog(
            stringResource(R.string.settings_units), UnitSystem.entries, state.units, { unitsLabel(it) },
            onPick = { settings[GeneralSettings.units] = it; close() }, onDismiss = close,
        )
        SettingsDialog.START_COUNTER -> CounterDialog(
            stringResource(R.string.counters_setting_start), state.startCounter,
            onPick = { settings[CounterSettings.start] = it; close() }, onDismiss = close,
        )
        SettingsDialog.WIDGET_COUNTER -> CounterDialog(
            stringResource(R.string.counters_setting_widget), state.widgetCounter,
            onPick = { settings[CounterSettings.widget] = it; BikeWidget.refresh(ctx); close() }, onDismiss = close,
        )
        SettingsDialog.PRICE -> ElectricityPriceDialog(close)
        SettingsDialog.REMINDERS -> RemindersSheet(state.reminders, { s, on -> settings[s] = on }, close)
        null -> {}
    }
}

private enum class SettingsDialog { LANGUAGE, UNITS, PRICE, REMINDERS, START_COUNTER, WIDGET_COUNTER }

/** Stateless Einstellungen list (used by the screenshot test with fixed data). */
@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    Screen(header = { PageHeader(stringResource(R.string.settings_title)) }, modifier = modifier, bodyTop = 4.dp, gap = 8.dp) {
        SectionLabel(stringResource(R.string.settings_group_driving))
        ListGroup {
            state.light?.let { l -> item { ListRow(stringResource(R.string.light_title), { actions.open(Routes.LIGHT) }, subtitle = lightSubtitle(l), icon = AppIcons.Bulb, tint = RowTint.AMBER) } }
            item {
                val r = state.range
                ListRow(
                    stringResource(R.string.range_title), { actions.open(Routes.RANGE) },
                    subtitle = stringResource(if (r.home) R.string.settings_range_sub_home else R.string.settings_range_sub, r.window, r.marginPct),
                    icon = AppIcons.Battery, tint = RowTint.PRIMARY,
                )
            }
            item {
                val sub = stringResource(if (state.navForward) R.string.settings_nav_always else R.string.settings_nav_off)
                ListRow(stringResource(R.string.nav_title), { actions.open(Routes.NAVIGATION) }, subtitle = sub, icon = AppIcons.TurnLeft, tint = RowTint.TERTIARY)
            }
            item { ListRow(stringResource(R.string.handlebar_title), { actions.open(Routes.HANDLEBAR_TILES) }, subtitle = stringResource(R.string.settings_handlebar_sub), icon = AppIcons.HandlebarMode, tint = RowTint.SECONDARY) }
            item { ListRow(stringResource(R.string.counters_setting_start), actions::pickStartCounter, value = stringResource(state.startCounter.labelRes()), icon = CounterIcon, tint = RowTint.PRIMARY) }
            item { ListRow(stringResource(R.string.counters_setting_widget), actions::pickWidgetCounter, value = stringResource(state.widgetCounter.labelRes()), icon = CounterIcon, tint = RowTint.TERTIARY) }
        }

        SectionLabel(stringResource(R.string.settings_group_recording))
        ListGroup {
            item {
                ListRow(
                    stringResource(R.string.settings_rides), { actions.open(Routes.RIDES) },
                    subtitle = stringResource(if (state.gpsTrack) R.string.settings_rides_gps_on else R.string.settings_rides_gps_off),
                    icon = AppIcons.TabRides, tint = RowTint.PRIMARY,
                )
            }
            item { ListRow(stringResource(R.string.backup_title), { actions.open(Routes.BACKUP) }, subtitle = stringResource(R.string.settings_backup_sub), icon = AppIcons.Backup, tint = RowTint.TERTIARY) }
            item {
                ListRow(
                    stringResource(R.string.stats_price), actions::editPrice,
                    subtitle = stringResource(R.string.stats_price_value, formatPrice(state.priceCentPerKwh)),
                    icon = AppIcons.Euro, tint = RowTint.AMBER,
                )
            }
        }

        SectionLabel(stringResource(R.string.settings_group_notifications))
        ListGroup {
            item {
                SwitchRow(
                    stringResource(R.string.notif_setting_details_title), state.connectionDetails, actions::setConnectionDetails,
                    subtitle = stringResource(if (state.connectionDetails) R.string.notif_setting_details_sub else R.string.notif_setting_details_off),
                    icon = AppIcons.Bell, tint = RowTint.SECONDARY,
                )
            }
            item { ListRow(stringResource(R.string.notif_setting_reminders_title), actions::openReminders, subtitle = stringResource(R.string.notif_setting_reminders_sub), icon = AppIcons.Clock, tint = RowTint.TERTIARY) }
            item {
                SwitchRow(
                    stringResource(R.string.notif_setting_error), state.driveErrorAlerts, actions::setDriveErrorAlerts,
                    subtitle = stringResource(R.string.notif_setting_error_sub),
                    icon = AppIcons.Warning, tint = RowTint.DANGER,
                )
            }
        }

        SectionLabel(stringResource(R.string.settings_group_bikes))
        ListGroup {
            item {
                ListRow(
                    stringResource(R.string.settings_bikes), { actions.open(Routes.BIKE) },
                    subtitle = state.bikeNames.joinToString(", ").ifEmpty { null },
                    icon = AppIcons.TabBike, tint = RowTint.PRIMARY,
                )
            }
            item { SwitchRow(stringResource(R.string.conn_auto), state.autoConnect, actions::setAutoConnect, subtitle = stringResource(R.string.conn_auto_sub), icon = AppIcons.Bluetooth, tint = RowTint.TERTIARY) }
            state.clockAuto?.let { on ->
                item { SwitchRow(stringResource(R.string.settings_clock), on, actions::setClockAuto, subtitle = stringResource(R.string.settings_clock_sub), icon = AppIcons.Clock, tint = RowTint.SECONDARY) }
            }
            // "Home Assistant und Webhooks" (K4) is left out of this iteration (owner decision).
        }

        SectionLabel(stringResource(R.string.settings_group_general))
        ListGroup {
            item { ListRow(stringResource(R.string.settings_language), actions::pickLanguage, value = languageLabel(state.language), icon = AppIcons.Globe, tint = RowTint.TERTIARY) }
            item { ListRow(stringResource(R.string.settings_units), actions::pickUnits, value = unitsLabel(state.units), icon = AppIcons.Ruler, tint = RowTint.SECONDARY) }
            item {
                SwitchRow(
                    stringResource(R.string.settings_wallpaper_colors), state.wallpaperColors, actions::setWallpaperColors,
                    subtitle = stringResource(R.string.settings_wallpaper_colors_sub), icon = AppIcons.Palette, tint = RowTint.PRIMARY,
                )
            }
        }

        SectionLabel(stringResource(R.string.settings_group_advanced))
        ListGroup {
            item { ListRow(stringResource(R.string.setup_permissions_title), { actions.open(Routes.PERMISSIONS) }, subtitle = stringResource(R.string.setup_settings_permissions_sub), icon = AppIcons.Shield, tint = RowTint.NEUTRAL) }
            item { ListRow(stringResource(R.string.diagnostics_title), { actions.open(Routes.DIAGNOSTICS) }, subtitle = stringResource(R.string.settings_diagnostics_sub), icon = AppIcons.Wrench, tint = RowTint.NEUTRAL) }
        }
    }
}

@Composable
private fun lightSubtitle(l: LightSummary): String = stringResource(
    when (l.mode) {
        LightMode.AUTO -> if (l.rain) R.string.settings_light_auto_rain else R.string.settings_light_auto
        LightMode.ALWAYS_ON -> R.string.settings_light_always
        LightMode.OFF -> R.string.settings_light_off
    },
)

/** "Erinnerungen": one switch per kind of reminder (W1 maintenance, W4 charging, R5 way home). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemindersSheet(r: Reminders, set: (Setting<Boolean>, Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.notif_setting_reminders_title), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = AppTheme.type.barTitle,
            )
            SwitchRow(stringResource(R.string.notif_setting_maintenance), r.maintenance, { set(NotifySettings.maintenance, it) }, subtitle = stringResource(R.string.notif_setting_maintenance_sub))
            SwitchRow(stringResource(R.string.notif_setting_charge), r.charge, { set(NotifySettings.charge, it) }, subtitle = stringResource(R.string.notif_setting_charge_sub))
            SwitchRow(stringResource(R.string.notif_setting_home), r.homeRange, { set(NotifySettings.homeRange, it) }, subtitle = stringResource(R.string.notif_setting_home_sub))
        }
    }
}

@Composable
private fun languageLabel(l: AppLanguage) = stringResource(
    when (l) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.GERMAN -> R.string.settings_language_de
        AppLanguage.ENGLISH -> R.string.settings_language_en
    },
)

@Composable
private fun unitsLabel(u: UnitSystem) = stringResource(if (u == UnitSystem.METRIC) R.string.settings_units_metric else R.string.settings_units_imperial)

/** A gauge with its needle: the distance counter rows. */
private val CounterIcon = LineIcon("M3 17a9 9 0 1 1 18 0", "M12 17l4.5-5.5")

/** Picker for a distance counter: each choice with one line on what it counts. */
@Composable
internal fun CounterDialog(title: String, selected: DistanceCounter, onPick: (DistanceCounter) -> Unit, onDismiss: () -> Unit) {
    AppDialog(title, onDismiss, confirm = { CancelButton(onDismiss) }) {
        Column(Modifier.selectableGroup()) {
            DistanceCounter.entries.forEach { c ->
                RadioRow(stringResource(c.labelRes()), c == selected, { onPick(c) }, subtitle = stringResource(c.descriptionRes()))
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AppDialog(title, onDismiss, confirm = { CancelButton(onDismiss) }) {
        Column(Modifier.selectableGroup()) { options.forEach { o -> RadioRow(label(o), o == selected, { onPick(o) }) } }
    }
}
