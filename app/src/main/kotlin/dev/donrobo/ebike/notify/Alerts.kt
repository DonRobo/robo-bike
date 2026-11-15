package dev.donrobo.ebike.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.MainActivity
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.theme.appColors
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.text
import dev.donrobo.ebike.maintenance.dueText
import dev.donrobo.ebike.ui.components.uiLocale

/**
 * The kinds of alert, each with its own channel (mutable in the system settings), on/off setting, tap
 * target and de-duplication rules ([AlertGate]).
 */
enum class AlertKind(
    val channel: NotifyChannel,
    val enabled: Setting<Boolean>,
    /** Route opened on tap; null = Start. Must be in [Routes.EXTERNAL]. */
    val route: String?,
    /** [Alerts.resolve] also removes the notification (otherwise it stays as a record until dismissed). */
    val dismissOnResolve: Boolean,
    /** A resolved episode that comes back within this time is shown silently. */
    val quietMs: Long,
    @DrawableRes val icon: Int,
    /** From [NotificationIds]; also the request code of its tap. */
    val notificationId: Int,
) {
    HOME_RANGE(NotifyChannel.HOME_RANGE, NotifySettings.homeRange, Routes.RANGE, true, 30 * MIN, R.drawable.ic_notify_warning, NotificationIds.ALERT_HOME_RANGE),
    CHARGE(NotifyChannel.CHARGE, NotifySettings.charge, null, true, 6 * 60 * MIN, R.drawable.ic_notify_battery, NotificationIds.ALERT_CHARGE),
    MAINTENANCE(NotifyChannel.MAINTENANCE, NotifySettings.maintenance, Routes.BIKE, true, 24 * 60 * MIN, R.drawable.ic_notify_wrench, NotificationIds.ALERT_MAINTENANCE),
    DRIVE_ERROR(NotifyChannel.DRIVE_ERROR, NotifySettings.driveError, null, false, 30 * MIN, R.drawable.ic_notify_warning, NotificationIds.ALERT_DRIVE_ERROR);

    /** Per bike: the last episode ([AlertGate]). Written by [Alerts] only. */
    val memory: Setting<AlertMemory?> =
        Setting.mapped("notify.alert.$name", null, Scope.PER_BIKE, AlertMemory::decode) { it?.encode() }
}

private const val MIN = 60_000L

/** One alert with its data. [key] identifies the episode: same key = same alert, never alerted twice. */
sealed class Alert(val kind: AlertKind) {
    abstract val key: String

    /** R5: the remaining range no longer covers the way home plus the reserve. One episode per [episode]. */
    data class HomeRange(val remainingKm: Double, val homeKm: Double, val episode: String = "home") : Alert(AlertKind.HOME_RANGE) {
        override val key get() = episode
    }

    /** W4: battery below the rider's threshold after a ride; once per ride ([rideId]). */
    data class ChargeReminder(val batteryPct: Int, val rideId: String) : Alert(AlertKind.CHARGE) {
        override val key get() = rideId
    }

    /** W1: a maintenance item is due ([dueInKm] < 0 = overdue); once per item and service interval ([itemKey]). */
    data class MaintenanceDue(val itemKey: String, val itemName: String, val dueInKm: Double) : Alert(AlertKind.MAINTENANCE) {
        override val key get() = itemKey
    }

    /** A drive error appeared while riding (raw byte of report 1, decoded [code]). Posted by [DriveErrorAlerts]. */
    data class DriveError(val raw: Int, val code: Int?) : Alert(AlertKind.DRIVE_ERROR) {
        override val key get() = raw.toString(16)
    }
}

/** What an alert says; built by [alertContent]. */
data class AlertContent(val title: String, val text: String, val subText: String? = null)

fun alertContent(ctx: Context, bikeName: String?, alert: Alert, fmt: UnitFormatter): AlertContent = when (alert) {
    is Alert.HomeRange -> AlertContent(
        ctx.getString(R.string.notif_home_title),
        ctx.getString(R.string.notif_home_text, fmt.distance(alert.remainingKm, 0).text(ctx), fmt.distance(alert.homeKm, 0).text(ctx)),
    )
    is Alert.ChargeReminder -> AlertContent(
        ctx.getString(R.string.notif_charge_title, bikeName ?: ctx.getString(R.string.notif_bike_generic)),
        ctx.getString(R.string.notif_charge_text, alert.batteryPct),
    )
    is Alert.MaintenanceDue -> AlertContent(
        ctx.getString(R.string.notif_maint_title, alert.itemName),
        dueText(ctx, alert.dueInKm, fmt),
    )
    is Alert.DriveError -> {
        val t = driveErrorText(ctx, alert.code)
        AlertContent(t.title, t.advice, driveErrorLabel(ctx, alert.raw, alert.code))
    }
}

/**
 * The app's alerts (one module for all features). Callers own the *condition* and report it:
 *
 * ```
 * Alerts.show(ctx, bikeId, Alert.HomeRange(remainingKm = 9.0, homeKm = 7.0))  // condition holds (call as often as you like)
 * Alerts.resolve(ctx, bikeId, AlertKind.HOME_RANGE)                           // condition no longer holds
 * ```
 *
 * This module owns presentation, channels, the rider's on/off setting per kind, de-duplication and the tap
 * target. Thread-safe; cheap when nothing changes (one settings read).
 */
object Alerts {
    fun show(ctx: Context, bikeId: String, alert: Alert) = synchronized(this) {
        val settings = ctx.settings
        val kind = alert.kind
        if (!settings[kind.enabled]) return
        val fmt = UnitFormatter(settings[GeneralSettings.units], ctx.uiLocale())
        val content = alertContent(ctx, ctx.bikes.get(bikeId)?.name, alert, fmt)
        val (action, mem) = AlertGate.onShow(settings.get(kind.memory, bikeId), alert.key, content.hashCode(), System.currentTimeMillis(), kind.quietMs)
        if (action == AlertAction.NONE) return
        settings.set(kind.memory, bikeId, mem)
        if (action == AlertAction.ALERT) Repo.log("alert ${kind.name.lowercase()}: ${content.title}")
        post(ctx, bikeId, kind, content, silent = action == AlertAction.UPDATE_SILENTLY)
    }

    fun resolve(ctx: Context, bikeId: String, kind: AlertKind) = synchronized(this) {
        val settings = ctx.settings
        val mem = settings.get(kind.memory, bikeId)
        if (mem?.active != true) return
        settings.set(kind.memory, bikeId, AlertGate.onResolve(mem, System.currentTimeMillis()))
        if (kind.dismissOnResolve) ctx.getSystemService(NotificationManager::class.java)?.cancel(bikeId, kind.notificationId)
    }

    private fun post(ctx: Context, bikeId: String, kind: AlertKind, content: AlertContent, silent: Boolean) {
        Channels.ensure(ctx)
        val n = NotificationCompat.Builder(ctx, kind.channel.id)
            .setSmallIcon(kind.icon)
            .setColor(ctx.appColors().primary.toArgb())
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .apply { content.subText?.let(::setSubText) }
            .setContentIntent(openApp(ctx, kind.route, kind.notificationId))
            .setAutoCancel(true)
            .setSilent(silent)
            .setOnlyAlertOnce(true)
            .setCategory(if (kind == AlertKind.DRIVE_ERROR) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_REMINDER)
            .build()
        // Without POST_NOTIFICATIONS the system drops it; the permission is asked on the Berechtigungen screen.
        ctx.getSystemService(NotificationManager::class.java)?.notify(bikeId, kind.notificationId, n)
    }

    /** Opens the app on [route] (null = Start), reusing the running activity. */
    fun openApp(ctx: Context, route: String?, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(Routes.EXTRA_ROUTE, it) } }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
