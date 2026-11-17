package dev.donrobo.ebike.ui.nav

import dev.donrobo.ebike.nav.DisplayNav
import dev.donrobo.ebike.nav.NavState

/** Status chip of "Das zeigt dein Display". */
enum class NavCardStatus {
    /** A hint is on the display. */
    RUNNING,
    /** Connected and forwarding, no navigation. */
    READY,
    NOT_CONNECTED,
    /** Forwarding switched off (N6). */
    OFF,
}

/** Everything the Navigation screen shows; built by [navigationUiState], rendered by [NavigationContent]. */
data class NavigationUiState(
    val status: NavCardStatus,
    /** The hint on the display ([DisplayNav.active]) or the last one sent in this app process. */
    val shown: DisplayNav?,
    val forward: Boolean,
    val notificationAccess: Boolean,
    val nowMs: Long,
) {
    val running: Boolean get() = status == NavCardStatus.RUNNING
}

fun navigationUiState(
    connected: Boolean,
    displayNav: DisplayNav?,
    testNav: NavState?,
    forward: Boolean,
    notificationAccess: Boolean,
    nowMs: Long,
): NavigationUiState {
    val status = when {
        displayNav?.active == true -> NavCardStatus.RUNNING
        !forward && testNav == null -> NavCardStatus.OFF
        !connected -> NavCardStatus.NOT_CONNECTED
        else -> NavCardStatus.READY
    }
    return NavigationUiState(status, displayNav, forward, notificationAccess, nowMs)
}

/** "vor 3 Sekunden" in the largest whole unit. */
data class Ago(val amount: Long, val unit: Unit) {
    enum class Unit { SECONDS, MINUTES, HOURS, DAYS }

    companion object {
        fun of(thenMs: Long, nowMs: Long): Ago {
            val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> Ago(s, Unit.SECONDS)
                s < 3600 -> Ago(s / 60, Unit.MINUTES)
                s < 86_400 -> Ago(s / 3600, Unit.HOURS)
                else -> Ago(s / 86_400, Unit.DAYS)
            }
        }
    }
}
