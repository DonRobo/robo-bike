package dev.donrobo.ebike.ui.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightSettings
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.light.RainLevel
import dev.donrobo.ebike.display.rememberDisplayCapabilities
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.light.SunDay
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.IconBadge
import dev.donrobo.ebike.ui.components.InsetPanel
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.StepperButton
import dev.donrobo.ebike.ui.connection.rememberSnapshot
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.cssLineBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.ZonedDateTime
import java.util.Locale
import dev.donrobo.ebike.ui.components.rememberActiveBike

/** User actions on the Licht screen. [persist] = false while a point is being dragged. */
interface LightActions {
    fun back()
    fun setMode(mode: LightMode)
    fun setRain(on: Boolean)
    fun setRainLevel(level: RainLevel)
    fun setMorningOffDeg(deg: Double, persist: Boolean = true)
    fun setEveningOnDeg(deg: Double, persist: Boolean = true)
}

/** Licht (artboard "Licht mit Sonnenkurve"), wired to the active bike's light settings and [Repo]. */
@Composable
fun LightScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bike by rememberActiveBike()
    val id = bike?.id

    @Composable
    fun <T> setting(s: Setting<T>): T {
        val v by remember(id) { id?.let { settings.flow(s, it) } ?: flowOf(s.default) }
            .collectAsStateWithLifecycle(id?.let { settings.get(s, it) } ?: s.default)
        return v
    }
    val mode = setting(LightSettings.mode)
    val rain = setting(LightSettings.rain)
    val rainLevel = setting(LightSettings.rainLevel)
    val morning = setting(LightSettings.morningOffDeg)
    val evening = setting(LightSettings.eveningOnDeg)

    val snapshot = rememberSnapshot(id)
    val status by Repo.light.collectAsStateWithLifecycle()
    val liveFix by Locations.fix.collectAsStateWithLifecycle()
    val now by produceState(ZonedDateTime.now()) {
        while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); value = ZonedDateTime.now() }
    }
    // Live fix while the service runs, else the newest position the app knows (system last known or persisted).
    val pos = (liveFix ?: remember { Locations.best(ctx) })?.point?.let { it.lat to it.lon }
    val date = now.toLocalDate()
    val posKey = pos?.let { String.format(Locale.ROOT, "%.2f,%.2f", it.first, it.second) }
    val day = remember(posKey, date, now.zone) { pos?.let { SunDay.of(date, now.zone, it.first, it.second) } }

    // While dragging, the point follows the finger without writing settings on every move.
    var draftMorning by remember { mutableStateOf<Double?>(null) }
    var draftEvening by remember { mutableStateOf<Double?>(null) }

    val state = lightUiState(
        snapshot?.liveReport, // only this bike's live report counts (A2)
        status, mode, rain, rainLevel,
        draftMorning ?: morning, draftEvening ?: evening, day?.let { CurveUi(it, SunDay.minuteOf(now)) },
        lampSwitch = rememberDisplayCapabilities(id).supports(DisplayFeature.LAMP_SWITCH),
    )
    val actions = remember(id, nav) {
        object : LightActions {
            fun <T> put(s: Setting<T>, v: T) { id?.let { settings.set(s, it, v) } }
            override fun back() = nav.back()
            override fun setMode(mode: LightMode) = put(LightSettings.mode, mode)
            override fun setRain(on: Boolean) = put(LightSettings.rain, on)
            override fun setRainLevel(level: RainLevel) = put(LightSettings.rainLevel, level)
            override fun setMorningOffDeg(deg: Double, persist: Boolean) {
                if (persist) { put(LightSettings.morningOffDeg, deg); draftMorning = null } else draftMorning = deg
            }
            override fun setEveningOnDeg(deg: Double, persist: Boolean) {
                if (persist) { put(LightSettings.eveningOnDeg, deg); draftEvening = null } else draftEvening = deg
            }
        }
    }
    LightContent(state, actions)
}

/** Stateless Licht screen (used by the screenshot tests with fixed data). */
@Composable
fun LightContent(state: LightUiState, actions: LightActions, modifier: Modifier = Modifier) {
    Screen(header = { BackHeader(stringResource(R.string.light_title), actions::back) }, modifier = modifier) {
        if (!state.lampSwitch) {
            NoLampCard()
        } else {
            StatusCard(state, actions)
            CurveCard(state, actions)
            RainCard(state, actions)
        }
    }
}

/** Q4: the display cannot switch the lamp, so there is nothing for the app to automate. */
@Composable
private fun NoLampCard() {
    AppCard(gap = 4.dp) {
        Text(stringResource(R.string.light_no_lamp_title), style = AppTheme.type.cardTitle)
        Text(stringResource(R.string.light_no_lamp_text), style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
    }
}

@Composable
private fun StatusCard(state: LightUiState, actions: LightActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val on = state.lightOn
    AppCard(gap = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                AppIcons.Bulb, size = 48.dp, radius = 16.dp, iconSize = 26.dp,
                container = if (on) c.accent else c.track, content = if (on) c.text else c.textMuted,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        when {
                            !state.live -> R.string.status_off
                            on -> R.string.common_light_is_on
                            else -> R.string.common_light_is_off
                        },
                    ),
                    style = t.statusTitle,
                )
                Text(lightLongText(state.status, on), style = t.bodySmall, color = c.textMuted)
            }
        }
        val modes = LightMode.entries
        val label = stringResource(R.string.light_mode_label)
        SegmentedControl(
            modes.map {
                stringResource(
                    when (it) {
                        LightMode.AUTO -> R.string.light_mode_auto
                        LightMode.ALWAYS_ON -> R.string.light_mode_always
                        LightMode.OFF -> R.string.light_mode_off
                    },
                )
            },
            modes.indexOf(state.mode), { actions.setMode(modes[it]) },
            Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun CurveCard(state: LightUiState, actions: LightActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val curve = state.curve
    val schedule = state.schedule
    AppCard(gap = 12.dp) {
        CardTitle(stringResource(R.string.light_curve_title), description = stringResource(R.string.light_curve_hint))
        if (curve != null && schedule != null) {
            val day = curve.day
            SunCurveChart(
                day, schedule, curve.nowMinute,
                day.morningHandle(schedule, state.morningOffDeg), day.eveningHandle(schedule, state.eveningOnDeg),
                onDrag = { p, m ->
                    when (p) {
                        CurvePoint.MORNING -> actions.setMorningOffDeg(day.morningDegFor(m), persist = false)
                        CurvePoint.EVENING -> actions.setEveningOnDeg(day.eveningDegFor(m), persist = false)
                    }
                },
                onDragEnd = { p ->
                    when (p) {
                        CurvePoint.MORNING -> actions.setMorningOffDeg(state.morningOffDeg)
                        CurvePoint.EVENING -> actions.setEveningOnDeg(state.eveningOnDeg)
                    }
                },
            )
        } else {
            InsetPanel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                Text(stringResource(R.string.light_no_position_title), style = t.bodyStrong)
                Text(stringResource(R.string.light_no_position_text), style = t.bodySmall, color = c.textMuted)
            }
        }
        val day = curve?.day
        PointRow(
            stringResource(R.string.light_morning_off),
            pointText(schedule?.morning, state.morningOffDeg, schedule, day),
            stringResource(R.string.light_morning_earlier), stringResource(R.string.light_morning_later),
            onEarlier = { actions.setMorningOffDeg(stepMorning(day, state, forward = false)) },
            onLater = { actions.setMorningOffDeg(stepMorning(day, state, forward = true)) },
        )
        PointRow(
            stringResource(R.string.light_evening_on),
            pointText(schedule?.evening, state.eveningOnDeg, schedule, day),
            stringResource(R.string.light_evening_earlier), stringResource(R.string.light_evening_later),
            onEarlier = { actions.setEveningOnDeg(stepEvening(day, state, forward = false)) },
            onLater = { actions.setEveningOnDeg(stepEvening(day, state, forward = true)) },
        )
        Text(stringResource(R.string.light_curve_footnote), style = t.caption, color = c.textMuted)
    }
}

/** Without a curve the steppers move the elevation by 1° (morning later = higher sun, evening later = lower). */
private fun stepMorning(day: SunDay?, s: LightUiState, forward: Boolean): Double =
    day?.stepMorning(s.morningOffDeg, s.eveningOnDeg, forward)
        ?: (s.morningOffDeg + if (forward) 1 else -1).coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)

private fun stepEvening(day: SunDay?, s: LightUiState, forward: Boolean): Double =
    day?.stepEvening(s.morningOffDeg, s.eveningOnDeg, forward)
        ?: (s.eveningOnDeg + if (forward) -1 else 1).coerceIn(LightSettings.MIN_DEG, LightSettings.MAX_DEG)

@Composable
private fun PointRow(label: String, text: PointText, earlier: String, later: String, onEarlier: () -> Unit, onLater: () -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = t.label, color = c.textMuted)
                val big = t.numberMedium.copy(lineHeight = t.numberMedium.fontSize * 1.1f)
                Text(text.time(), Modifier.cssLineBox(big), style = big)
                Text(text.detail(), style = t.caption, color = c.textMuted)
            }
            StepperButton(AppIcons.Minus, earlier, onEarlier)
            StepperButton(AppIcons.Plus, later, onLater)
        }
    }
}

@Composable
private fun RainCard(state: LightUiState, actions: LightActions) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val title = stringResource(R.string.light_rain_title)
    AppCard(gap = 8.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
        SwitchRow(
            title, state.rainEnabled, actions::setRain, Modifier.bleed(16.dp), titleStyle = t.cardTitle,
            leading = { AppIcon(AppIcons.Rain, null, size = 24.dp, tint = c.textMuted) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.light_rain_level_label), style = t.label, color = if (state.rainEnabled) c.textMuted else c.textMuted.copy(alpha = 0.38f))
            val levels = RainLevel.entries
            val a11y = stringResource(R.string.light_rain_level_a11y)
            SegmentedControl(
                levels.map {
                    stringResource(
                        when (it) {
                            RainLevel.DRIZZLE -> R.string.light_rain_drizzle
                            RainLevel.RAIN -> R.string.light_rain_rain
                            RainLevel.HEAVY -> R.string.light_rain_heavy
                        },
                    )
                },
                levels.indexOf(state.rainLevel), { actions.setRainLevel(levels[it]) },
                Modifier.semantics { contentDescription = a11y },
                enabled = state.rainEnabled,
            )
        }
    }
}

