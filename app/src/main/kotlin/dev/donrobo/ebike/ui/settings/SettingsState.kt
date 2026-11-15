package dev.donrobo.ebike.ui.settings

import dev.donrobo.ebike.ConnectionSettings
import dev.donrobo.ebike.clock.ClockSettings
import dev.donrobo.ebike.counters.CounterSettings
import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.display.DisplayCapabilities
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightSettings
import dev.donrobo.ebike.nav.NavSettings
import dev.donrobo.ebike.nav.ShowFrom
import dev.donrobo.ebike.notify.NotifySettings
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.range.RangeSettings
import dev.donrobo.ebike.ride.RideSettings
import dev.donrobo.ebike.settings.AppLanguage
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.Settings
import dev.donrobo.ebike.stats.StatsSettings
import dev.donrobo.ebike.units.UnitSystem

/** The light row's summary; null in [SettingsUiState.light] = the display has no lamp switch, row hidden (Q4). */
data class LightSummary(val mode: LightMode, val rain: Boolean)

data class RangeSummary(val window: Int, val marginPct: Int, val home: Boolean)

/** [showFrom] only matters while [forward] is on. */
data class NavSummary(val forward: Boolean, val showFrom: ShowFrom)

/** The three switches behind "Erinnerungen" (W1, W4, R5). */
data class Reminders(val homeRange: Boolean, val charge: Boolean, val maintenance: Boolean)

/**
 * Everything the Einstellungen list shows (S1): row summaries and switch states, all read from the stored settings
 * (one place per fact; this is only a view). Built by [settingsUiState], rendered by [SettingsContent].
 */
data class SettingsUiState(
    val light: LightSummary?,
    val range: RangeSummary,
    val nav: NavSummary,
    val gpsTrack: Boolean,
    val priceCentPerKwh: Double,
    val connectionDetails: Boolean,
    val reminders: Reminders,
    val driveErrorAlerts: Boolean,
    val bikeNames: List<String>,
    val autoConnect: Boolean,
    /** Null = the display has no clock (feature block without DATE_TIME): the row is hidden (Q4). */
    val clockAuto: Boolean?,
    val language: AppLanguage,
    val units: UnitSystem,
    /** Colours from the wallpaper instead of the Robo Bike green. */
    val wallpaperColors: Boolean,
    /** Distance counter of the Start tile and of the widget. */
    val startCounter: DistanceCounter = CounterSettings.start.default,
    val widgetCounter: DistanceCounter = CounterSettings.widget.default,
)

/**
 * Pure assembly from [settings]. Per-bike values come from the active bike ([activeId], defaults without one);
 * [caps] are the active bike's display capabilities (unknown = everything supported).
 */
fun settingsUiState(
    settings: Settings,
    bikes: List<Bike>,
    activeId: String?,
    caps: DisplayCapabilities,
    language: AppLanguage,
): SettingsUiState {
    fun <T> bike(s: Setting<T>): T = activeId?.let { settings.get(s, it) } ?: s.default
    return SettingsUiState(
        light = if (caps.supports(DisplayFeature.LAMP_SWITCH)) LightSummary(bike(LightSettings.mode), bike(LightSettings.rain)) else null,
        range = RangeSummary(bike(RangeSettings.window), bike(RangeSettings.marginPct), settings[RangeSettings.homeEnabled]),
        nav = NavSummary(settings[NavSettings.forward], settings[NavSettings.showFrom]),
        gpsTrack = settings[RideSettings.gpsTrack],
        priceCentPerKwh = settings[StatsSettings.priceCentPerKwh],
        connectionDetails = settings[NotifySettings.connectionDetails],
        reminders = Reminders(settings[NotifySettings.homeRange], settings[NotifySettings.charge], settings[NotifySettings.maintenance]),
        driveErrorAlerts = settings[NotifySettings.driveError],
        bikeNames = bikes.map { it.name },
        autoConnect = settings[ConnectionSettings.autoConnect],
        clockAuto = settings[ClockSettings.auto].takeIf { caps.supports(DisplayFeature.DATE_TIME) },
        language = language,
        units = settings[GeneralSettings.units],
        wallpaperColors = settings[GeneralSettings.wallpaperColors],
        startCounter = settings[CounterSettings.start],
        widgetCounter = settings[CounterSettings.widget],
    )
}
