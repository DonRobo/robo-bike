package dev.donrobo.ebike.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.SnackbarHostState
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.LineIcon

/** Navigation routes, one per artboard (Widgets is a home-screen widget/notification, not a screen). */
object Routes {
    const val START = "start"                 // Main
    const val RANGE = "range"                 // Reichweite
    const val LIGHT = "light"                 // Licht
    const val NAVIGATION = "navigation"       // Navigation
    const val HANDLEBAR = "handlebar"         // Lenkermodus
    const val HANDLEBAR_TILES = "handlebar/tiles" // tile editor + auto-start (no artboard; Einstellungen links here)
    const val RIDES = "rides"                 // Fahrten
    const val RIDE = "rides/{rideId}"         // Fahrt
    const val STATS = "stats"                 // Statistik
    const val ROUTE = "rides/route/{routeId}" // recurring route comparison (no artboard; from Fahrt and Statistik)
    const val BACKUP = "backup"               // Export und Sicherung (no artboard; Einstellungen row links here)
    const val BIKE = "bike"                   // Rad
    const val SETTINGS = "settings"           // Einstellungen
    const val DIAGNOSTICS = "diagnostics"     // Diagnose
    const val SETUP = "setup"                 // Einrichtung
    const val PERMISSIONS = "setup/permissions" // Berechtigungen

    const val ARG_RIDE_ID = "rideId"
    fun ride(rideId: String) = "rides/$rideId"
    const val ARG_ROUTE_ID = "routeId"
    fun route(routeId: String) = "rides/route/$routeId"

    /** Intent extra for MainActivity: route to open (notifications, auto-start). Only [EXTERNAL] routes are honoured. */
    const val EXTRA_ROUTE = "dev.donrobo.ebike.ROUTE"
    val EXTERNAL = setOf(HANDLEBAR, RANGE, BIKE) // RANGE, BIKE: tap targets of the home-range and maintenance alerts
}

/** The four bottom-navigation tabs and the routes that belong to each (for the selected state). */
enum class Tab(val route: String, @StringRes val label: Int, val icon: LineIcon, val routes: Set<String>) {
    START(Routes.START, R.string.tab_start, AppIcons.TabStart, setOf(Routes.START)),
    RIDES(Routes.RIDES, R.string.tab_rides, AppIcons.TabRides, setOf(Routes.RIDES, Routes.STATS)),
    BIKE(Routes.BIKE, R.string.tab_bike, AppIcons.TabBike, setOf(Routes.BIKE)),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, AppIcons.TabSettings, setOf(Routes.SETTINGS));

    companion object {
        /** Tab whose bar is shown on [route]; null = no bottom bar (sub-screens, handlebar mode, setup). */
        fun of(route: String?): Tab? = entries.firstOrNull { route in it.routes }
    }
}

/**
 * What every screen gets to move around. Screens take `nav: AppNavigator` (plus route arguments) and
 * nothing else from the shell, so their signatures stay stable while features are built in parallel.
 */
interface AppNavigator {
    fun open(route: String)
    fun back()
    fun switchTab(tab: Tab)
    /** Opens [route] as the only entry of the back stack (end of the first-run setup). */
    fun restart(route: String) = open(route)
}

/** App-wide snackbar for screens (provided by the shell). */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** No-op navigator for previews and screenshot tests. */
object PreviewNavigator : AppNavigator {
    override fun open(route: String) {}
    override fun back() {}
    override fun switchTab(tab: Tab) {}
}
