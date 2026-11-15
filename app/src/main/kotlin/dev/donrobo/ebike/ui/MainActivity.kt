package dev.donrobo.ebike.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import dev.donrobo.ebike.ui.setup.firstRoute
import dev.donrobo.ebike.ui.theme.RoboBikeTheme

/** AppCompatActivity (not ComponentActivity) so the in-app language also applies on API 31/32. */
class MainActivity : AppCompatActivity() {
    /** Route asked for by the launching intent ([Routes.EXTRA_ROUTE]); cleared once the shell opened it. */
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System-bar icons follow the system's light/dark mode, like the theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) requestedRoute.value = routeOf(intent)
        // First launch without a paired display opens the setup (E1); an installation with one goes to Start.
        val startRoute = firstRoute(this)
        setContent { RoboBikeTheme { AppShell(startRoute, requestedRoute = requestedRoute.value, onRouteOpened = { requestedRoute.value = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { requestedRoute.value = it }
    }

    private fun routeOf(intent: Intent?): String? = intent?.getStringExtra(Routes.EXTRA_ROUTE)?.takeIf { it in Routes.EXTERNAL }
}
