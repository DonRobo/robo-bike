package dev.donrobo.ebike.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.theme.appColors
import dev.donrobo.ebike.units.text
import dev.donrobo.ebike.widget.BikeGlance
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.textRes
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.widget.SurfaceActionReceiver

/**
 * Content of the foreground-service notification (K3). [lightAction] = the state the "Licht an/aus"
 * action switches to; null = no light action.
 */
data class ConnectionNotice(val title: String, val text: String?, val lightAction: Boolean?)

/**
 * "Stadtrad ist verbunden — 75 % Akku, noch 64 km, Licht an" while connected; the connection state
 * otherwise. With [details] off only that the bike is connected, so the notification stays the same for
 * the whole ride ([NotifySettings.connectionDetails]).
 */
fun connectionNotice(ctx: Context, g: BikeGlance, details: Boolean): ConnectionNotice {
    val name = g.bikeName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.app_name)
    return when (g.link) {
        // The notification exists only while the service runs, so OFF does not occur there; it reads as waiting.
        ConnectionStatus.OFF -> ConnectionNotice(name, ctx.getString(ConnectionStatus.WAITING.textRes()), null)
        ConnectionStatus.WAITING, ConnectionStatus.CONNECTING, ConnectionStatus.LOST ->
            ConnectionNotice(name, ctx.getString(g.link.textRes(detail = true)), null)
        ConnectionStatus.CONNECTED -> {
            val title = ctx.getString(R.string.notif_connected_title, name)
            if (!details || !g.live) return ConnectionNotice(title, null, null)
            val parts = buildList {
                g.batteryPct?.let { add(ctx.getString(R.string.notif_part_battery, it)) }
                g.range?.let { add(ctx.getString(R.string.notif_part_range, it.text(ctx))) }
                g.lightOn?.let { add(ctx.getString(if (it) R.string.notif_part_light_on else R.string.notif_part_light_off)) }
                if (g.locked) add(ctx.getString(R.string.notif_part_locked))
            }
            ConnectionNotice(title, parts.joinToString(ctx.getString(R.string.notif_part_separator)), g.light.switchable.takeIf { it }?.let { g.light == LightControl.OFF })
        }
    }
}

/** The notification itself; [disconnect] is BikeService's own stop intent. */
fun connectionNotification(ctx: Context, n: ConnectionNotice, disconnect: PendingIntent): Notification {
    Channels.ensure(ctx)
    return NotificationCompat.Builder(ctx, NotifyChannel.CONNECTION.id)
        .setSmallIcon(R.drawable.ic_notify_bike)
        .setColor(ctx.appColors().primary.toArgb())
        .setContentTitle(n.title)
        .apply { n.text?.let(::setContentText) }
        .setContentIntent(Alerts.openApp(ctx, null, RequestCodes.CONNECTION_OPEN))
        .apply {
            n.lightAction?.let { on ->
                addAction(R.drawable.ic_light, ctx.getString(if (on) R.string.common_light_on else R.string.common_light_off), SurfaceActionReceiver.setLight(ctx, on, RequestCodes.CONNECTION_LIGHT))
            }
        }
        .addAction(0, ctx.getString(R.string.common_disconnect), disconnect)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()
}
