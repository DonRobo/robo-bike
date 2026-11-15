package dev.donrobo.ebike.screenshots

import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.ConnectionStatus
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.donrobo.ebike.R
import dev.donrobo.ebike.notify.Alert
import dev.donrobo.ebike.notify.alertContent
import dev.donrobo.ebike.notify.connectionNotice
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.LineIcon
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import dev.donrobo.ebike.widget.BikeGlance
import dev.donrobo.ebike.widget.BikeWidget
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.widget.bikeGlance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Artboard "Widgets". The home-screen widget is the real RemoteViews layout (inflated with
 * RemoteViews.apply, as a launcher does). Tile and notifications are drawn by the system on a device, so
 * here they are stand-ins in the artboard's look, filled with the real mapped content (labels, texts,
 * actions) from [LightControl], [connectionNotice] and [alertContent].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val bike = Bike("b1", "Stadtrad", null)
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 21.0, tripKm = 10.6, odoKm = 1284.3)
    private val range = RangeEstimate(64.0, 86.0, 1.2)

    private val counters = dev.donrobo.ebike.counters.CounterInputs(
        live = true, odoKm = 1284.3, tripKm = 10.6, openRide = null, lastRideKm = 8.2, fullChargeOdoKm = 1250.0, homeDepartureOdoKm = null,
    )

    private fun glance(locale: Locale) =
        bikeGlance(bike, ConnectionStatus.CONNECTED, Snapshot.live(report), range, UnitFormatter(UnitSystem.METRIC, locale), counters = counters)

    @Test @Config(qualifiers = "de-$BOARD")
    fun widgets_de() = compose.screenshot("widgets_de") { Board(glance(Locale.GERMANY), Locale.GERMANY) }

    @Test @Config(qualifiers = "en-$BOARD")
    fun widgets_en() = compose.screenshot("widgets_en") { Board(glance(Locale.UK), Locale.UK) }

    /** The connection dropped ("Getrennt"): last known state with its time, range not learned yet, light button inert. */
    @Test @Config(qualifiers = "de-$BOARD")
    fun widgets_offline_de() = compose.screenshot("widgets_offline_de") {
        val fmt = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
        val at = java.time.LocalDate.now().atTime(14, 32).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Board(
            bikeGlance(bike, ConnectionStatus.LOST, Snapshot(report, at), null, fmt, counter = dev.donrobo.ebike.counters.DistanceCounter.THIS_RIDE, counters = counters),
            Locale.GERMANY,
        )
    }

    /** The framework-rendered (VectorDrawable) icons of tile, notifications and widget, white on dark like a status bar. */
    @Test @Config(qualifiers = "de-$BOARD")
    fun notification_icons() = compose.screenshot("notification_icons") {
        Row(Modifier.background(Color(0xFF18241F)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(R.drawable.ic_notify_bike, R.drawable.ic_light, R.drawable.ic_notify_warning, R.drawable.ic_notify_battery, R.drawable.ic_notify_wrench).forEach { res ->
                AndroidView(factory = { android.widget.ImageView(it).apply { setImageResource(res) } }, modifier = Modifier.size(48.dp))
            }
        }
    }

    @Composable
    private fun Board(g: BikeGlance, locale: Locale, tile: LightControl = g.light) {
        val c = AppTheme.colors
        val ctx = LocalContext.current
        val resources = LocalResources.current
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Section("Widget für den Startbildschirm") {
                AndroidView(
                    factory = { context ->
                        val host = FrameLayout(context)
                        BikeWidget.views(context, g).apply(context, host).also {
                            it.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            host.addView(it)
                        }
                        host
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Section("Schnellkachel") { TileStandIn(tile) }
            Section("Mitteilungen") {
                val n = connectionNotice(ctx, g, details = true)
                NotificationStandIn(AppIcons.TabBike, c.primary, c.onPrimary, n.title, n.text, buildList {
                    n.lightAction?.let { add(resources.getString(if (it) R.string.common_light_on else R.string.common_light_off)) }
                    add(resources.getString(R.string.common_disconnect))
                })
                val a = alertContent(ctx, bike.name, Alert.HomeRange(9.0, 7.0), UnitFormatter(UnitSystem.METRIC, locale))
                NotificationStandIn(AppIcons.Warning, c.accentTint, c.onAccentTint, a.title, a.text, emptyList())
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = AppTheme.type.labelStrong, color = AppTheme.colors.textMuted)
            content()
        }
    }

    @Composable
    private fun TileStandIn(t: LightControl) {
        val c = AppTheme.colors
        val ctx = LocalContext.current
        val resources = LocalResources.current // resources follow configuration changes
        val active = t == LightControl.ON
        Row(
            Modifier.width(180.dp).heightIn(min = 64.dp).background(if (active) c.accent else c.track, RoundedCornerShape(32.dp)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fg = if (t == LightControl.ON || t == LightControl.OFF) c.text else c.neutral
            AppIcon(AppIcons.Bulb, null, size = 24.dp, strokeWidth = 1.8f, tint = fg)
            Column {
                Text(resources.getString(R.string.widget_tile_label), fontSize = 15.sp, style = AppTheme.type.labelStrong, color = fg)
                val sub = when (t) {
                    LightControl.ON -> R.string.widget_tile_on
                    LightControl.OFF -> R.string.widget_tile_off
                    LightControl.NOT_CONNECTED -> R.string.status_off
                    LightControl.LOCKED -> R.string.widget_tile_locked
                    LightControl.NO_LAMP -> R.string.widget_tile_no_lamp
                }
                Text(resources.getString(sub), style = AppTheme.type.caption, color = fg)
            }
        }
    }

    @Composable
    private fun NotificationStandIn(icon: LineIcon, badge: Color, onBadge: Color, title: String, text: String?, actions: List<String>) {
        val c = AppTheme.colors
        Column(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (actions.isEmpty()) 16.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(badge, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    AppIcon(icon, null, size = 24.dp, strokeWidth = 1.8f, tint = onBadge)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = AppTheme.type.itemTitleStrong, color = c.text)
                    text?.let { Text(it, style = AppTheme.type.bodySmall, color = c.textMuted) }
                }
            }
            if (actions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                actions.forEach {
                    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                        Text(it, style = AppTheme.type.labelStrong, color = c.primary)
                    }
                }
            }
        }
    }

    private companion object {
        const val BOARD = "w390dp-h844dp-port-xhdpi"
    }
}
