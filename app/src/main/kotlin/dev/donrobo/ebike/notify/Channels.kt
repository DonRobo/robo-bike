package dev.donrobo.ebike.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import dev.donrobo.ebike.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * All notification channels of the app.
 * One channel per kind, so the rider can mute exactly that kind in the system settings. Ids are stable:
 * the system keeps the rider's per-channel choices under them ("connection" is the pre-redesign id).
 */
enum class NotifyChannel(val id: String, @StringRes val nameRes: Int, @StringRes val descRes: Int, val importance: Int) {
    /** Foreground-service notification: low, never makes a sound. Must not be lower than LOW for a foreground service. */
    CONNECTION("connection", R.string.notif_channel_connection, R.string.notif_channel_connection_desc, NotificationManager.IMPORTANCE_LOW),
    /** Range getting too short for the way home: worth interrupting the ride. */
    HOME_RANGE("alert.homeRange", R.string.notif_channel_home, R.string.notif_channel_home_desc, NotificationManager.IMPORTANCE_HIGH),
    CHARGE("alert.charge", R.string.notif_channel_charge, R.string.notif_channel_charge_desc, NotificationManager.IMPORTANCE_DEFAULT),
    MAINTENANCE("alert.maintenance", R.string.notif_channel_maintenance, R.string.notif_channel_maintenance_desc, NotificationManager.IMPORTANCE_DEFAULT),
    /** A drive error while riding: heads-up, the rider may have to stop. */
    DRIVE_ERROR("alert.driveError", R.string.notif_channel_error, R.string.notif_channel_error_desc, NotificationManager.IMPORTANCE_HIGH),
    /** Lenkermodus auto-start fallback (M5) when Android blocks the direct start: heads-up, it is a tap target. */
    HANDLEBAR("handlebar", R.string.notif_channel_handlebar, R.string.notif_channel_handlebar_desc, NotificationManager.IMPORTANCE_HIGH),
}

object Channels {
    private val created = AtomicBoolean(false)

    /** Creates (or renames, after a language change in a new process) all channels once per process. */
    fun ensure(ctx: Context) {
        if (!created.compareAndSet(false, true)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            NotifyChannel.entries.map { c ->
                NotificationChannel(c.id, ctx.getString(c.nameRes), c.importance).apply { description = ctx.getString(c.descRes) }
            },
        )
    }
}
