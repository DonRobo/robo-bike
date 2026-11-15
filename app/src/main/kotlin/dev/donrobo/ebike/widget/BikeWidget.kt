package dev.donrobo.ebike.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.R
import dev.donrobo.ebike.textRes
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.notify.Alerts
import dev.donrobo.ebike.notify.RequestCodes
import dev.donrobo.ebike.ui.components.formatAsOf
import dev.donrobo.ebike.ui.theme.appColors
import dev.donrobo.ebike.units.res
import dev.donrobo.ebike.units.text
import dev.donrobo.ebike.counters.labelRes
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.settings.bikes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.donrobo.ebike.ui.components.uiLocale

/** Home-screen widget (K1): rendering, pushing and the throttled updater. */
object BikeWidget {
    /** Battery and range redraw at most this often; connection and light changes go out at once. */
    const val MIN_INTERVAL_MS = 60_000L

    fun views(ctx: Context, g: BikeGlance): RemoteViews {
        val c = ctx.appColors()
        val v = RemoteViews(ctx.packageName, R.layout.widget_bike)
        v.setColorStateList(android.R.id.background, "setBackgroundTintList", csl(c.surface))
        v.setTextColor(R.id.widget_name, c.text.toArgb())
        v.setTextViewText(R.id.widget_name, g.bikeName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.app_name))

        val (dot, statusColor) = when (g.link) {
            ConnectionStatus.CONNECTED -> c.primary to c.primaryStrong
            ConnectionStatus.CONNECTING, ConnectionStatus.WAITING -> c.accent to c.onAccentTint
            ConnectionStatus.LOST, ConnectionStatus.OFF -> c.neutral to c.textMuted
        }
        v.setColorStateList(R.id.widget_dot, "setImageTintList", csl(dot))
        v.setTextColor(R.id.widget_status, statusColor.toArgb())
        v.setTextViewText(R.id.widget_status, ctx.getString(statusText(g)))

        v.setTextColor(R.id.widget_range, c.text.toArgb())
        v.setTextColor(R.id.widget_unit, c.textMuted.toArgb())
        v.setTextViewText(R.id.widget_range, g.range?.value ?: ctx.getString(R.string.widget_no_value))
        g.range?.let { v.setTextViewText(R.id.widget_unit, ctx.getString(it.unit.res())) }

        v.setTextColor(R.id.widget_counter_label, c.textMuted.toArgb())
        v.setTextViewText(R.id.widget_counter_label, ctx.getString(g.counter.labelRes()))
        v.setTextColor(R.id.widget_counter_value, c.text.toArgb())
        v.setTextViewText(R.id.widget_counter_value, g.counterValue?.text(ctx) ?: ctx.getString(R.string.widget_no_value))

        v.setColorStateList(R.id.widget_battery_bar, "setProgressBackgroundTintList", csl(c.primaryTint))
        v.setColorStateList(R.id.widget_battery_bar, "setProgressTintList", csl(if (g.live) c.primary else c.neutral))
        v.setProgressBar(R.id.widget_battery_bar, 100, g.batteryPct ?: 0, false)
        v.setTextColor(R.id.widget_battery, c.textMuted.toArgb())
        v.setTextViewText(R.id.widget_battery, batteryText(ctx, g))

        val on = g.light == LightControl.ON
        v.setColorStateList(R.id.widget_light, "setBackgroundTintList", csl(if (on) c.accent else c.track))
        v.setColorStateList(R.id.widget_light, "setImageTintList", csl(if (g.light.switchable) c.text else c.neutral))
        v.setContentDescription(
            R.id.widget_light,
            ctx.getString(
                when {
                    !g.light.switchable -> R.string.widget_light_unavailable
                    on -> R.string.widget_light_turn_off
                    else -> R.string.widget_light_turn_on
                },
            ),
        )
        val open = Alerts.openApp(ctx, null, RequestCodes.WIDGET_OPEN)
        v.setOnClickPendingIntent(android.R.id.background, open)
        // Not switchable (not connected, locked): the button opens the app like the rest of the widget.
        v.setOnClickPendingIntent(
            R.id.widget_light,
            if (g.light.switchable) SurfaceActionReceiver.setLight(ctx, !on, RequestCodes.WIDGET_LIGHT, from = "widget") else open,
        )
        return v
    }

    fun statusText(g: BikeGlance): Int =
        if (g.link == ConnectionStatus.CONNECTED && g.locked) R.string.status_locked else g.link.textRes()

    /** "75 % Akku" live; "75 % Akku · Stand 14:32" from the last known state; nothing known: a hint. */
    fun batteryText(ctx: Context, g: BikeGlance): String {
        val pct = g.batteryPct ?: return ctx.getString(if (g.bikeName == null) R.string.widget_no_bike else R.string.widget_no_data)
        val at = g.asOfMs ?: return ctx.getString(R.string.widget_battery, pct)
        return ctx.getString(R.string.widget_battery_as_of, pct, formatAsOf(at, ctx.uiLocale()))
    }

    private fun csl(c: Color) = ColorStateList.valueOf(c.toArgb())

    private fun ids(ctx: Context): IntArray {
        val m = AppWidgetManager.getInstance(ctx) ?: return IntArray(0)
        return m.getAppWidgetIds(ComponentName(ctx, BikeWidgetProvider::class.java)) ?: IntArray(0)
    }

    /** Draws [g] on every placed widget (nothing to do if none is placed). */
    fun push(ctx: Context, g: BikeGlance) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, views(ctx, g))
    }

    /** One-off redraw from the current state, e.g. after the service stopped or the active bike changed. */
    fun refresh(ctx: Context) = push(ctx, currentGlance(ctx))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var following = false

    /**
     * Redraws the widget whenever the active bike changes (selection, rename, removal), whoever changed it: the
     * bike switcher, the Rad tab, pairing or the presence rule. Runs for the whole process, started by the App.
     */
    fun followActiveBike(ctx: Context) {
        if (following) return
        following = true
        val app = ctx.applicationContext
        scope.launch {
            app.bikes.activeFlow().drop(1).collect {
                try { refresh(app) } catch (e: Exception) { Repo.log("widget refresh failed: $e") }
            }
        }
    }

    /**
     * Keeps the widget in step while the service runs: every visible change of [glanceFlow], throttled by
     * [UpdateThrottle] (the live report arrives twice a second; battery and range change slowly anyway).
     */
    class Updater(private val ctx: Context) {
        suspend fun run() = coroutineScope {
            val throttle = UpdateThrottle(MIN_INTERVAL_MS, ::glanceUrgent)
            val states = Channel<BikeGlance>(Channel.CONFLATED)
            launch { glanceFlow(ctx).collect { states.send(it) } }
            while (true) {
                val due = throttle.nextDueMs
                val next = if (due == null) states.receive()
                else withTimeoutOrNull((due - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { states.receive() }
                val now = SystemClock.elapsedRealtime()
                val show = if (next != null) throttle.offer(next, now) else throttle.poll(now)
                show?.let { push(ctx, it) }
            }
        }
    }
}

/** System entry point: placement, resize, the hourly repair update. */
class BikeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, BikeWidget.views(ctx, currentGlance(ctx)))
    }
}
