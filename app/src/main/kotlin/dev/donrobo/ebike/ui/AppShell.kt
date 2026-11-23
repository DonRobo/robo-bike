package dev.donrobo.ebike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.donrobo.ebike.ui.bike.BikeScreen
import dev.donrobo.ebike.ui.components.BottomNavBar
import dev.donrobo.ebike.ui.components.BottomNavItem
import dev.donrobo.ebike.ui.diagnostics.DiagnosticsScreen
import dev.donrobo.ebike.ui.handlebar.HandlebarScreen
import dev.donrobo.ebike.ui.handlebar.HandlebarTilesScreen
import dev.donrobo.ebike.ui.light.LightScreen
import dev.donrobo.ebike.ui.nav.NavigationScreen
import dev.donrobo.ebike.ui.range.BandsScreen
import dev.donrobo.ebike.ui.range.RangeScreen
import dev.donrobo.ebike.ui.rides.RideScreen
import dev.donrobo.ebike.ui.rides.RidesScreen
import dev.donrobo.ebike.ui.rides.RouteScreen
import dev.donrobo.ebike.ui.backup.BackupScreen
import dev.donrobo.ebike.ui.settings.SettingsScreen
import dev.donrobo.ebike.ui.setup.PermissionsScreen
import dev.donrobo.ebike.ui.setup.SetupScreen
import dev.donrobo.ebike.ui.start.StartScreen
import dev.donrobo.ebike.ui.stats.StatsScreen
import dev.donrobo.ebike.ui.theme.AppTheme

private class ControllerNavigator(private val nav: NavHostController) : AppNavigator {
    override fun open(route: String) { nav.navigate(route) { launchSingleTop = true } }
    override fun back() { nav.popBackStack() }
    override fun switchTab(tab: Tab) {
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    override fun restart(route: String) {
        // The graph may have started at the setup; from now on tab switches pop up to [route].
        nav.graph.setStartDestination(route)
        nav.navigate(route) { popUpTo(nav.graph.id) { inclusive = true } }
    }
}

/**
 * Root of the UI: bottom navigation on the tab roots, one destination per artboard. Each feature owns
 * its screen file; this file only maps routes to them.
 */
@Composable
fun AppShell(startRoute: String = Routes.START, requestedRoute: String? = null, onRouteOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val navigator = remember(nav) { ControllerNavigator(nav) }
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val tab = Tab.of(entry?.destination?.route ?: startRoute)

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        ShellFrame(tab, onSelect = navigator::switchTab, snackbar = snackbar) {
            NavHost(nav, startDestination = startRoute) {
                composable(Routes.START) { StartScreen(navigator) }
                composable(Routes.RANGE) { RangeScreen(navigator) }
                composable(Routes.RANGE_BANDS) { BandsScreen(navigator) }
                composable(Routes.LIGHT) { LightScreen(navigator) }
                composable(Routes.NAVIGATION) { NavigationScreen(navigator) }
                composable(Routes.HANDLEBAR) { HandlebarScreen(navigator) }
                composable(Routes.HANDLEBAR_TILES) { HandlebarTilesScreen(navigator) }
                composable(Routes.RIDES) { RidesScreen(navigator) }
                composable(Routes.RIDE, listOf(navArgument(Routes.ARG_RIDE_ID) { type = NavType.StringType })) {
                    RideScreen(navigator, it.arguments?.getString(Routes.ARG_RIDE_ID).orEmpty())
                }
                composable(Routes.STATS) { StatsScreen(navigator) }
                composable(Routes.ROUTE, listOf(navArgument(Routes.ARG_ROUTE_ID) { type = NavType.StringType })) {
                    RouteScreen(navigator, it.arguments?.getString(Routes.ARG_ROUTE_ID).orEmpty())
                }
                composable(Routes.BACKUP) { BackupScreen(navigator) }
                composable(Routes.BIKE) { BikeScreen(navigator) }
                composable(Routes.SETTINGS) { SettingsScreen(navigator) }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(navigator) }
                composable(Routes.SETUP) { SetupScreen(navigator) }
                composable(Routes.PERMISSIONS) { PermissionsScreen(navigator) }
            }
            // A route asked for from outside (handlebar auto-start / its notification), opened on top of the start
            // screen. After NavHost in the same composition, so the graph is set when the effect runs.
            LaunchedEffect(requestedRoute) { requestedRoute?.let { navigator.open(it); onRouteOpened() } }
        }
    }
}

/**
 * Frame of the shell: [tab]'s bottom navigation (none on sub-screens) and the snackbar host around [content]. The
 * content gets only the window insets the bottom bar has not covered. The screenshot tests render screens in it too,
 * so they see the same insets as the app.
 */
@Composable
fun ShellFrame(tab: Tab?, onSelect: (Tab) -> Unit, snackbar: SnackbarHostState? = null, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        bottomBar = {
            if (tab != null) BottomNavBar(
                Tab.entries.map { BottomNavItem(stringResource(it.label), it.icon) },
                selectedIndex = tab.ordinal,
                onSelect = { onSelect(Tab.entries[it]) },
            )
        },
    ) { padding ->
        // consumeWindowInsets: screens below add only the insets the bottom bar has not covered.
        Box(Modifier.fillMaxSize().background(AppTheme.colors.background).padding(padding).consumeWindowInsets(padding)) { content() }
    }
}
