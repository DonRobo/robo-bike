package dev.donrobo.ebike

import android.content.Context
import androidx.annotation.StringRes
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings

/**
 * E4 in one value: the connection of one bike as every surface names it (Start, Lenkermodus, widget,
 * notification). [LOST]: a connection of the running service dropped and is not back yet (bike switched off
 * or out of range; the service keeps trying a little longer before it stops).
 */
enum class ConnectionStatus { CONNECTED, CONNECTING, LOST, WAITING, OFF }

/**
 * Pure mapping for the bike [bikeId] (A2): a link that belongs to another bike counts as no link for this one, and
 * a running service waits for this bike only if it is the active one. Without the service it is still WAITING
 * when the system will start it as soon as the display appears ([autoArmed], see [autoConnectArmed]).
 */
fun connectionStatus(
    bikeId: String?,
    activeBikeId: String?,
    sessionBikeId: String?,
    conn: ConnState,
    running: Boolean,
    linkLost: Boolean,
    autoArmed: Boolean,
): ConnectionStatus {
    val other = sessionBikeId != null && sessionBikeId != bikeId
    val c = if (other) ConnState.OFF else conn
    val runningHere = running && !other && bikeId == activeBikeId
    return when {
        c == ConnState.CONNECTED -> ConnectionStatus.CONNECTED
        // after a drop the reconnect attempts stay "lost", not a flicker of connecting / waiting
        runningHere && linkLost -> ConnectionStatus.LOST
        c == ConnState.CONNECTING -> ConnectionStatus.CONNECTING
        runningHere -> ConnectionStatus.WAITING
        autoArmed -> ConnectionStatus.WAITING
        else -> ConnectionStatus.OFF
    }
}

/**
 * Auto-connect will start the service for [bike] when its display appears: the switch is on, the bike is not
 * paused by Disconnect (E5) and its display is paired with the system (or, on a phone without companion support,
 * known by its MAC). Asks the system for its associations, so callers evaluate it only when it matters.
 */
fun autoConnectArmed(ctx: Context, bike: Bike?, associatedMacs: List<String> = BikeCompanion.associatedMacs(ctx)): Boolean {
    val mac = bike?.displayMac ?: return false
    if (!ctx.settings[ConnectionSettings.autoConnect] || ctx.settings.get(ConnectionSettings.pausedUntilOff, bike.id)) return false
    return associatedMacs.any { it.equals(mac, ignoreCase = true) } || !BikeCompanion.supported(ctx)
}

/** [connectionStatus] of [bike] from the current [Repo] state (for the surfaces outside Compose). */
fun currentConnectionStatus(ctx: Context, bike: Bike?): ConnectionStatus {
    val running = Repo.running.value
    // The service running covers every waiting case; only without it do the associations matter.
    return connectionStatus(
        bike?.id, ctx.bikes.active()?.id, Repo.sessionBikeId.value, Repo.conn.value, running, Repo.linkLost.value,
        autoArmed = !running && autoConnectArmed(ctx, bike),
    )
}

/**
 * The one text per state (strings_common.xml). [detail]: the longer form for surfaces with room for the reason
 * (Start, the notification); the widget and the handlebar banner use the short one.
 */
@StringRes
fun ConnectionStatus.textRes(detail: Boolean = false): Int = when (this) {
    ConnectionStatus.CONNECTED -> R.string.status_connected
    ConnectionStatus.CONNECTING -> R.string.status_connecting
    ConnectionStatus.LOST -> if (detail) R.string.status_lost_detail else R.string.status_lost
    ConnectionStatus.WAITING -> R.string.status_waiting
    ConnectionStatus.OFF -> R.string.status_off
}
