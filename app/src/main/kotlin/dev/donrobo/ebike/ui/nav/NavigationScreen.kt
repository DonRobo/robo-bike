package dev.donrobo.ebike.ui.nav

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.nav.NavOrigin
import dev.donrobo.ebike.nav.NavSettings
import dev.donrobo.ebike.nav.ShowFrom
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.disclosure.Disclosure
import dev.donrobo.ebike.ui.disclosure.DisclosureDialog
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.StatusDot
import dev.donrobo.ebike.ui.components.SwitchCard
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.components.unitLabel
import dev.donrobo.ebike.ui.start.maneuverPhrase
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.cssLineBox
import dev.donrobo.ebike.ui.theme.displayStyle
import dev.donrobo.ebike.units.UnitFormatter
import kotlinx.coroutines.delay

/** User actions on the Navigation screen. */
interface NavigationActions {
    fun setForward(on: Boolean)
    fun setShowFrom(v: ShowFrom)
    fun openNotificationAccess()
}

/** Navigation (artboard Navigation, without the source list and the idle-screen block: owner decisions N2/N4). */
@Composable
fun NavigationScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val forward by remember { settings.flow(NavSettings.forward) }.collectAsStateWithLifecycle(settings[NavSettings.forward])
    val showFrom by remember { settings.flow(NavSettings.showFrom) }.collectAsStateWithLifecycle(settings[NavSettings.showFrom])
    val conn by Repo.conn.collectAsStateWithLifecycle()
    val displayNav by Repo.displayNav.collectAsStateWithLifecycle()
    val mapsNav by Repo.mapsNav.collectAsStateWithLifecycle()
    val testNav by Repo.testNav.collectAsStateWithLifecycle()
    // "vor 3 Sekunden" keeps counting while the screen is open.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    var access by remember { mutableStateOf(false) }
    // Re-check on resume: the user grants access in the system settings and comes back.
    LifecycleResumeEffect(Unit) {
        access = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        onPauseOrDispose { }
    }
    val state = navigationUiState(conn == ConnState.CONNECTED, displayNav, mapsNav, testNav, forward, showFrom, access, now)
    // Prominent disclosure first; only "Agree" opens the system's notification-access page.
    var disclosure by remember { mutableStateOf(false) }
    val actions = remember {
        object : NavigationActions {
            override fun setForward(on: Boolean) { settings[NavSettings.forward] = on }
            override fun setShowFrom(v: ShowFrom) { settings[NavSettings.showFrom] = v }
            override fun openNotificationAccess() { disclosure = true }
        }
    }
    NavigationContent(state, actions, nav::back)
    if (disclosure) DisclosureDialog(
        Disclosure.NOTIFICATION_ACCESS,
        onAgree = { disclosure = false; ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        onDecline = { disclosure = false },
    )
}

/** Stateless Navigation screen (used by the screenshot tests). */
@Composable
fun NavigationContent(state: NavigationUiState, actions: NavigationActions, onBack: () -> Unit) {
    val fmt = rememberUnitFormatter()
    Screen(header = { BackHeader(stringResource(R.string.nav_title), onBack) }) {
        DisplayCard(state, fmt)
        SwitchCard(
            stringResource(R.string.nav_forward_title), state.forward, actions::setForward,
            subtitle = stringResource(R.string.nav_forward_sub),
        )
        AppCard(gap = 10.dp) {
            CardTitle(stringResource(R.string.nav_show_from_title), description = stringResource(R.string.nav_show_from_sub))
            val options = ShowFrom.entries
            SegmentedControl(
                options.map { o -> o.meters?.let { fmt.shortDistance(it.toDouble()).text() } ?: stringResource(R.string.nav_show_from_always) },
                options.indexOf(state.showFrom),
                { actions.setShowFrom(options[it]) },
            )
        }
        AccessBanner(state.notificationAccess, actions::openNotificationAccess)
    }
}

private val DisplayNumber = displayStyle(56, FontWeight.SemiBold, lineHeight = 1.0)

@Composable
private fun DisplayCard(state: NavigationUiState, fmt: UnitFormatter) {
    val c = AppTheme.colors
    val t = AppTheme.type
    AppCard(gap = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_card_title), Modifier.weight(1f), style = t.cardTitle)
            StatusDot(
                stringResource(
                    when (state.status) {
                        NavCardStatus.RUNNING -> R.string.nav_status_running
                        NavCardStatus.WAITING -> R.string.nav_status_waiting
                        NavCardStatus.READY -> R.string.nav_status_ready
                        NavCardStatus.NOT_CONNECTED -> R.string.status_off
                        NavCardStatus.OFF -> R.string.nav_status_off
                    },
                ),
                tone = if (state.running) Tone.POSITIVE else Tone.NEUTRAL,
                style = t.sectionLabel, gap = 6.dp,
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.inverse).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val hint = state.shown?.takeIf { state.running }?.state
            if (hint != null) {
                val phrase = maneuverPhrase(hint.icon).replaceFirstChar { it.uppercase() }
                AppIcon(AppIcons.maneuver(hint.icon), stringResource(R.string.nav_arrow_cd, phrase), size = 72.dp, strokeWidth = 2.2f, tint = c.onInverse)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val d = fmt.shortDistance(hint.distM.toDouble())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.value, Modifier.alignBy(LastBaseline).cssLineBox(DisplayNumber), style = DisplayNumber, color = c.onInverse, maxLines = 1)
                        Text(unitLabel(d.unit), Modifier.alignBy(LastBaseline), style = t.unitLarge, color = c.onInverse)
                    }
                    Text(phrase, style = t.body, color = c.onInverseMuted)
                }
            } else {
                AppIcon(AppIcons.TabBike, null, size = 56.dp, strokeWidth = 1.8f, tint = c.onInverseMuted)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.nav_idle_title), style = t.statusTitle, color = c.onInverse)
                    Text(stringResource(R.string.nav_idle_sub), style = t.body, color = c.onInverseMuted)
                }
            }
        }
        Text(footer(state, fmt), style = t.bodySmall, color = c.textMuted)
    }
}

@Composable
private fun footer(state: NavigationUiState, fmt: UnitFormatter): String {
    val last = state.shown
    return when {
        state.status == NavCardStatus.RUNNING && last != null -> sentAgo(last.sinceMs, last.origin, state.nowMs)
        state.status == NavCardStatus.WAITING && state.pending != null && state.showFrom.meters != null -> stringResource(
            R.string.nav_waiting,
            fmt.shortDistance(state.pending.distM.toDouble()).text(),
            fmt.shortDistance(state.showFrom.meters.toDouble()).text(),
        )
        state.status == NavCardStatus.OFF -> stringResource(R.string.nav_off)
        state.status == NavCardStatus.NOT_CONNECTED -> stringResource(R.string.nav_not_connected)
        last != null -> sentAgo(last.sinceMs, last.origin, state.nowMs)
        else -> stringResource(R.string.nav_ready)
    }
}

@Composable
private fun sentAgo(atMs: Long, origin: NavOrigin, nowMs: Long): String {
    val ago = Ago.of(atMs, nowMs)
    val n = ago.amount.toInt()
    val agoText = pluralStringResource(
        when (ago.unit) {
            Ago.Unit.SECONDS -> R.plurals.nav_ago_seconds
            Ago.Unit.MINUTES -> R.plurals.nav_ago_minutes
            Ago.Unit.HOURS -> R.plurals.nav_ago_hours
            Ago.Unit.DAYS -> R.plurals.nav_ago_days
        },
        n, n,
    )
    val from = stringResource(if (origin == NavOrigin.TEST) R.string.nav_origin_test else R.string.nav_origin_maps)
    return stringResource(R.string.nav_sent_ago, agoText, from)
}

@Composable
private fun AccessBanner(granted: Boolean, onChange: () -> Unit) {
    val tone = if (granted) Tone.POSITIVE else Tone.WARNING
    StatusBanner(
        stringResource(if (granted) R.string.nav_access_granted else R.string.nav_access_missing),
        tone = tone,
        icon = if (granted) AppIcons.Check else AppIcons.Warning,
        large = true,
        action = {
            TextButton(onChange, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                Text(stringResource(if (granted) R.string.nav_access_change else R.string.common_allow), style = AppTheme.type.labelStrong)
            }
        },
    )
}
