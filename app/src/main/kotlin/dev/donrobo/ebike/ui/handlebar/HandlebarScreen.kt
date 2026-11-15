package dev.donrobo.ebike.ui.handlebar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.textRes
import dev.donrobo.ebike.R
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.handlebar.HandlebarAutoStart
import dev.donrobo.ebike.display.rememberDisplayCapabilities
import dev.donrobo.ebike.errors.DriveErrorState
import dev.donrobo.ebike.ui.bike.DriveErrorBanner
import dev.donrobo.ebike.handlebar.HandlebarSettings
import dev.donrobo.ebike.proto.DisplayFeature
import dev.donrobo.ebike.handlebar.HandlebarTiles
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.Routes
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.LineIcon
import dev.donrobo.ebike.ui.components.NumberWithUnit
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.colors
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.connection.rememberConnectionController
import dev.donrobo.ebike.ui.connection.rememberSnapshot
import dev.donrobo.ebike.ui.components.formatAsOf
import dev.donrobo.ebike.ui.start.maneuverPhrase
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.cssLineBox
import dev.donrobo.ebike.ui.theme.displayStyle
import dev.donrobo.ebike.units.UnitFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import dev.donrobo.ebike.ui.components.rememberActiveBike
import dev.donrobo.ebike.ui.components.currentLocale

/** User actions in the handlebar mode. */
interface HandlebarActions {
    fun setLight(on: Boolean)
    /** Tap on the light button while the bike is locked: explain instead of failing silently. */
    fun lightLocked()
    fun customize()
    fun exit()
    /** Tap on the drive-error banner: leave the mode for the error explanation on the Rad tab. */
    fun explainError() {}
}

// Sizes of the artboard that are specific to this screen (read at a glance, from arm's length).
private val SpeedNumber = displayStyle(200, FontWeight.Bold, tracking = -0.04, lineHeight = 0.9)
private val SpeedUnit = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
private val TurnNumber = displayStyle(64, FontWeight.Bold, lineHeight = 1.0)
private val TurnUnit = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
private val PanelText = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
private val PanelSub = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
private val ButtonText = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
private val PanelShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(24.dp)

/** Lenkermodus (M1–M6), wired to [Repo], the bike's tile layout and the range state. */
@Composable
fun HandlebarScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val bikes = ctx.bikes
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val connection = rememberConnectionController(snackbar)

    val bike by rememberActiveBike()
    val bikeId = bike?.id
    val snapshot = rememberSnapshot(bikeId)
    val tiles by remember(bikeId) { bikeId?.let { settings.flow(HandlebarSettings.tiles, it) } ?: flowOf(HandlebarTiles.DEFAULT) }
        .collectAsStateWithLifecycle(bikeId?.let { settings.get(HandlebarSettings.tiles, it) } ?: HandlebarTiles.DEFAULT)
    val mapsNav by Repo.mapsNav.collectAsStateWithLifecycle()
    val testNav by Repo.testNav.collectAsStateWithLifecycle()
    val range by RangeState.estimate.collectAsStateWithLifecycle()
    val home by RangeState.home.collectAsStateWithLifecycle()
    val now by produceState(LocalTime.now()) {
        while (true) {
            val t = LocalTime.now()
            value = t
            delay(60_000L - t.second * 1000L - t.nano / 1_000_000L) // tick on the minute for the clock tile
        }
    }

    KeepScreenOnFullScreen()
    LifecycleResumeEffect(Unit) {
        HandlebarAutoStart.visible.value = true
        HandlebarAutoStart.cancelNotification(ctx)
        onPauseOrDispose { HandlebarAutoStart.visible.value = false }
    }

    val state = handlebarUiState(
        connection.status(), snapshot, mapsNav, testNav, range, home, tiles, now,
        lampSwitch = rememberDisplayCapabilities(bikeId).supports(DisplayFeature.LAMP_SWITCH),
        driveError = DriveErrorState.current.collectAsStateWithLifecycle().value,
        counters = dev.donrobo.ebike.counters.CounterState.inputs.collectAsStateWithLifecycle().value,
    )
    val lockedHint = stringResource(R.string.handlebar_locked_hint)
    val actions = remember(nav, connection) {
        object : HandlebarActions {
            override fun setLight(on: Boolean) = connection.setLight(on, "handlebar")
            override fun lightLocked() { scope.launch { snackbar.showSnackbar(lockedHint) } }
            override fun customize() = nav.open(Routes.HANDLEBAR_TILES)
            override fun exit() = nav.back()
            override fun explainError() = nav.switchTab(Tab.BIKE)
        }
    }
    HandlebarContent(state, actions)
}

/** M3: screen stays on; system bars hidden (swipe shows them briefly) while the mode is open. */
@Composable
private fun KeepScreenOnFullScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Stateless handlebar mode. Portrait follows the artboard; landscape (most handlebar mounts) puts the turn
 * and the speed on the left, tiles and buttons on the right. The speed takes whatever height is left.
 */
@Composable
fun HandlebarContent(state: HandlebarUiState, actions: HandlebarActions, modifier: Modifier = Modifier) {
    val fmt = rememberUnitFormatter()
    BoxWithConstraints(modifier.fillMaxSize().background(AppTheme.colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.42f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.driveError?.let { DriveErrorBanner(it, actions::explainError) }
                    TopPanel(state, fmt, compact = true)
                    SpeedBlock(state, fmt, Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(0.58f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TileGrid(state.tiles, state, fmt, columns = if (state.tiles.size <= 4) 2 else 3)
                    Spacer(Modifier.weight(1f))
                    Controls(state, actions)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.driveError?.let { DriveErrorBanner(it, actions::explainError) }
                TopPanel(state, fmt, compact = false)
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.top == HandlebarTop.NAV) {
                        SpeedBlock(state, fmt, Modifier.weight(1f, fill = false).fillMaxWidth())
                        TileGrid(state.tiles, state, fmt, columns = 2)
                    } else {
                        // No turn hint: the speed keeps its size, the tiles take all the height that is left (M1/M4).
                        SpeedBlock(state, fmt, Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * 0.4f).fillMaxWidth())
                        TileGrid(state.tiles, state, fmt, columns = 2, modifier = Modifier.weight(1f), fill = true)
                    }
                }
                Controls(state, actions)
            }
        }
    }
}

@Composable
private fun TopPanel(state: HandlebarUiState, fmt: UnitFormatter, compact: Boolean) {
    when (state.top) {
        HandlebarTop.NAV -> NavPanel(state.nav!!, fmt, compact)
        HandlebarTop.NONE -> Unit
        else -> StatusPanel(state)
    }
}

/** M6 / A4: the next turn, as on the artboard (green, 84 dp arrow, 64 sp distance). */
@Composable
private fun NavPanel(nav: NavState, fmt: UnitFormatter, compact: Boolean) {
    val c = AppTheme.colors
    val d = fmt.shortDistance(nav.distM.toDouble())
    val locale = currentLocale()
    val phrase = maneuverPhrase(nav.icon).replaceFirstChar { it.titlecase(locale) }
    val unit = unitLabel(d.unit)
    Row(
        Modifier.fillMaxWidth().clip(PanelShape).background(c.primary).padding(horizontal = 20.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(AppIcons.maneuver(nav.icon), phrase, size = if (compact) 64.dp else 84.dp, strokeWidth = 2.4f, tint = c.onPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoxWithConstraints {
                val m = rememberTextMeasurer()
                val f = fitScale(m, d.value, TurnNumber, unit, TurnUnit, with(LocalDensity.current) { 6.dp.toPx() }, constraints.maxWidth.toFloat())
                NumberWithUnit(d.value, unit, TurnNumber.scaled(f), TurnUnit.scaled(f), gap = 6.dp, color = c.onPrimary, unitColor = c.onPrimary)
            }
            Text(phrase, style = PanelText, color = c.onPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** No navigation and no live link: say so where the turn would be, with the age of the values below. */
@Composable
private fun StatusPanel(state: HandlebarUiState) {
    val tone = if (state.top == HandlebarTop.OFFLINE || state.top == HandlebarTop.LOST) Tone.NEUTRAL else Tone.WARNING
    val tc = tone.colors()
    val c = AppTheme.colors
    val locale = currentLocale()
    Row(
        Modifier.fillMaxWidth().clip(PanelShape).background(if (tone == Tone.NEUTRAL) c.track else tc.container)
            .padding(horizontal = 20.dp, vertical = 18.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(tc.dot))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(state.connection.textRes()),
                style = PanelText, color = if (tone == Tone.NEUTRAL) c.text else tc.content,
            )
            Text(
                state.lastKnownAtMs?.let { stringResource(R.string.handlebar_as_of, formatAsOf(it, locale)) } ?: stringResource(R.string.handlebar_no_data),
                style = PanelSub, color = c.textMuted,
            )
        }
    }
}

/**
 * M1: the speed, as large as the space allows (200 sp on the artboard's phone). The size is fitted to "88"
 * (or the actual value if wider), so it does not jump while the number changes.
 */
@Composable
private fun SpeedBlock(state: HandlebarUiState, fmt: UnitFormatter, modifier: Modifier) {
    val c = AppTheme.colors
    val speed = state.speedKmh?.let { fmt.speed(it) }
    val value = speed?.value ?: stringResource(R.string.handlebar_no_value)
    val unit = unitLabel(speed?.unit ?: fmt.speedUnit)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val m = rememberTextMeasurer()
        val density = LocalDensity.current
        val widest = max(m.measure("88", SpeedNumber).size.width, m.measure(value, SpeedNumber).size.width).toFloat()
        val unitH = m.measure(unit, SpeedUnit).size.height
        val numberH = with(density) { SpeedNumber.fontSize.toPx() * 0.9f }
        val padding = with(density) { 8.dp.toPx() }
        val byWidth = constraints.maxWidth / widest
        val byHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - unitH - padding) / numberH else 1f
        // Without a live speed a 200 sp dash would read as a solid bar: show it at half size.
        val f = min(1f, min(byWidth, byHeight)).coerceAtLeast(0.2f) * (if (speed == null) 0.5f else 1f)
        Column(Modifier.padding(top = 6.dp, bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val style = SpeedNumber.scaled(f)
            // Unbounded so a tight box does not clip the text's own layout before cssLineBox centres it.
            Text(value, Modifier.cssLineBox(style).wrapContentHeight(unbounded = true), style = style, color = if (speed != null) c.text else c.textMuted, maxLines = 1, softWrap = false)
            Text(unit, style = SpeedUnit, color = c.textMuted, maxLines = 1)
        }
    }
}

/** M4: three large targets (light 2fr, tiles 1fr, exit 1fr, 72 dp high). */
@Composable
private fun Controls(state: HandlebarUiState, actions: HandlebarActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.lampSwitch) LightButton(state.light, actions, Modifier.weight(2f))
        ControlButton(AppIcons.Tiles, stringResource(R.string.handlebar_customize), actions::customize, Modifier.weight(1f))
        ControlButton(AppIcons.Close, stringResource(R.string.handlebar_exit), actions::exit, Modifier.weight(1f), strokeWidth = 2.2f)
    }
}

@Composable
private fun LightButton(light: LightControl, actions: HandlebarActions, modifier: Modifier) {
    val c = AppTheme.colors
    val on = light == LightControl.ON
    val description = stringResource(R.string.handlebar_light_toggle)
    val semantics = Modifier.semantics {
        contentDescription = description
        if (light.switchable) { role = Role.Switch; toggleableState = ToggleableState(on) }
    }
    ButtonBox(
        onClick = when (light) {
            LightControl.ON, LightControl.OFF -> ({ actions.setLight(!on) })
            LightControl.LOCKED -> actions::lightLocked
            LightControl.NOT_CONNECTED, LightControl.NO_LAMP -> ({})
        },
        modifier = modifier.then(semantics),
        filled = if (on) c.accent else null,
        enabled = light != LightControl.NOT_CONNECTED && light != LightControl.NO_LAMP,
    ) {
        AppIcon(if (light == LightControl.LOCKED) HandlebarIcons.Lock else AppIcons.Bulb, null, size = 28.dp, tint = if (light == LightControl.LOCKED) c.textMuted else c.text)
        Text(
            stringResource(
                when (light) {
                    LightControl.ON -> R.string.common_light_on
                    LightControl.OFF -> R.string.common_light_off
                    LightControl.LOCKED -> R.string.handlebar_light_locked
                    LightControl.NOT_CONNECTED, LightControl.NO_LAMP -> R.string.handlebar_light_unavailable
                },
            ),
            style = ButtonText, color = if (light == LightControl.LOCKED) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ControlButton(icon: LineIcon, description: String, onClick: () -> Unit, modifier: Modifier, strokeWidth: Float = 2f) {
    ButtonBox(onClick, modifier, filled = null) {
        AppIcon(icon, description, size = 28.dp, strokeWidth = strokeWidth, tint = AppTheme.colors.text)
    }
}

/**
 * Glove-sized Material button, 72 dp high, radius 24: a filled [Button] in [filled] (light on, amber) or an
 * [OutlinedButton] with a 2 dp outline that stays visible in sunlight.
 */
@Composable
private fun ButtonBox(onClick: () -> Unit, modifier: Modifier, filled: Color?, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = AppTheme.colors
    val m = modifier.heightIn(min = 72.dp)
    val padding = PaddingValues(horizontal = 12.dp)
    val inner: @Composable RowScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    if (filled != null) Button(
        onClick, m, enabled, shape = ButtonShape, contentPadding = padding,
        colors = ButtonDefaults.buttonColors(containerColor = filled, contentColor = c.text), content = inner,
    )
    else OutlinedButton(
        onClick, m, enabled, shape = ButtonShape, contentPadding = padding,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = c.text),
        border = BorderStroke(2.dp, c.border), content = inner,
    )
}
