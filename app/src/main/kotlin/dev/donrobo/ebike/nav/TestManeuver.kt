package dev.donrobo.ebike.nav

import dev.donrobo.ebike.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The test maneuver of the Diagnose screen ([Repo.testNav]), which [NavForwarder] prefers over Google Maps. It
 * ends by itself [TIMEOUT_MS] after the last [start], whichever screen is open, so a forgotten test does not keep
 * the arrow on the display. The only writer of [Repo.testNav].
 */
object TestManeuver {
    const val TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeout: Job? = null

    @Synchronized fun start(s: NavState) {
        timeout?.cancel()
        Repo.publishTestNav(s)
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            // cancelled by a newer start/stop while waiting for the lock: that one decides
            synchronized(this@TestManeuver) { if (isActive) Repo.publishTestNav(null) }
        }
    }

    @Synchronized fun stop() {
        timeout?.cancel()
        timeout = null
        Repo.publishTestNav(null)
    }
}
