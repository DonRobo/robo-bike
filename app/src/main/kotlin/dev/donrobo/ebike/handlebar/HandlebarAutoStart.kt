package dev.donrobo.ebike.handlebar

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.notify.Channels
import dev.donrobo.ebike.notify.NotificationIds
import dev.donrobo.ebike.notify.NotifyChannel
import dev.donrobo.ebike.notify.RequestCodes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.MainActivity
import dev.donrobo.ebike.ui.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * M5: opens the handlebar mode when the bike connects, if the rider wants that.
 *
 * Android blocks activity starts from the background (since API 29). The documented way that always works
 * is a notification the rider taps: a PendingIntent sent by the system on a tap is an exempt launch. A direct
 * start is tried first because AOSP exempts apps with a Companion Device association
 * (BackgroundActivityStartController: `isAssociatedCompanionApp` -> "Companion App"); that exemption is no
 * longer listed in the public developer guide and OEM builds may differ, so a blocked start (which fails
 * silently) is detected by the screen not becoming visible, and the notification is posted then.
 *
 * Runs inside BikeService for the lifetime of one service instance; opens the mode at most once per instance,
 * so a short link drop mid-ride does not pop the mode up again after the rider closed it.
 */
class HandlebarAutoStart(private val ctx: Context) {

    suspend fun run() {
        var opened = false
        Repo.conn.map { it == ConnState.CONNECTED }.distinctUntilChanged().collect { connected ->
            if (!connected) { cancelNotification(ctx); return@collect }
            if (opened || !ctx.settings[HandlebarSettings.autoStart] || visible.value) return@collect
            opened = true
            open()
        }
    }

    private suspend fun open() {
        try {
            ctx.startActivity(launchIntent(ctx))
        } catch (e: Exception) {
            Repo.log("handlebar auto-start: direct start failed: $e")
        }
        delay(VISIBLE_TIMEOUT_MS)
        if (visible.value) { Repo.log("handlebar auto-start: opened"); return }
        Repo.log("handlebar auto-start: blocked by the system, posting notification")
        notifyFallback()
    }

    private fun notifyFallback() {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Repo.log("handlebar auto-start: notifications not allowed, cannot offer the fallback")
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Channels.ensure(ctx)
        val tap = PendingIntent.getActivity(ctx, RequestCodes.HANDLEBAR_OPEN, launchIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NotificationIds.HANDLEBAR,
            NotificationCompat.Builder(ctx, NotifyChannel.HANDLEBAR.id)
                .setSmallIcon(R.drawable.ic_bike)
                .setContentTitle(ctx.getString(R.string.handlebar_notif_title))
                .setContentText(ctx.getString(R.string.handlebar_notif_text))
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setTimeoutAfter(NOTIF_TIMEOUT_MS)
                .build(),
        )
    }

    companion object {
        private const val VISIBLE_TIMEOUT_MS = 2_500L
        private const val NOTIF_TIMEOUT_MS = 10 * 60_000L

        /** True while the handlebar screen is resumed. Single writer: the handlebar screen. */
        val visible = MutableStateFlow(false)

        /** Opens MainActivity on the handlebar route, reusing the running instance (onNewIntent). */
        fun launchIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
            .putExtra(Routes.EXTRA_ROUTE, Routes.HANDLEBAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** The fallback notification is pointless once the mode is open or the bike is gone. */
        fun cancelNotification(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NotificationIds.HANDLEBAR)
    }
}
