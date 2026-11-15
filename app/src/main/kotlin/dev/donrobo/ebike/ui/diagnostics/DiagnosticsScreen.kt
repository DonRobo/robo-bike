package dev.donrobo.ebike.ui.diagnostics

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.R
import dev.donrobo.ebike.sim.SimulatorHook
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.clock.ClockCheck
import dev.donrobo.ebike.clock.ClockSettings
import dev.donrobo.ebike.clock.ClockState
import dev.donrobo.ebike.nav.NavState
import dev.donrobo.ebike.nav.TestManeuver
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.proto.Icon
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.LocalSnackbar
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.Hairline
import dev.donrobo.ebike.ui.components.ListGroup
import dev.donrobo.ebike.ui.components.ListRow
import dev.donrobo.ebike.ui.components.MonoBlock
import dev.donrobo.ebike.ui.components.PillChipGroup
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.SectionLabel
import dev.donrobo.ebike.ui.components.SegmentedControl
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.ScreenFrame
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.components.rememberUnitFormatter
import dev.donrobo.ebike.ui.components.text
import dev.donrobo.ebike.ui.connection.rememberConnectionController
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** User actions on the Diagnose screen. */
interface DiagnosticsActions {
    fun selectTestIcon(icon: Icon)
    fun selectTestDistance(m: Int)
    fun sendTest()
    fun stopTest()
    fun setClockNow()
    fun setBigEndian(on: Boolean)
    fun readErrorMemory()
    fun openFullLog()
    fun shareLog()
}

private enum class Page { MAIN, LOG }

/**
 * Diagnose (artboard Diagnose) plus a drive-error raw-data card. The full log is a sub-page local to
 * this screen.
 */
@Composable
fun DiagnosticsScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val settings = ctx.settings
    val snackbar = LocalSnackbar.current
    val connection = rememberConnectionController(snackbar)
    val scope = rememberCoroutineScope()

    val conn by Repo.conn.collectAsStateWithLifecycle()
    val live by Repo.live.collectAsStateWithLifecycle()
    val testNav by Repo.testNav.collectAsStateWithLifecycle()
    val navDebug by Repo.navDebug.collectAsStateWithLifecycle()
    val log by Repo.log.collectAsStateWithLifecycle()
    val clock by ClockState.last.collectAsStateWithLifecycle()
    val bigEndian by remember { settings.flow(ClockSettings.bigEndian) }.collectAsStateWithLifecycle(settings[ClockSettings.bigEndian])

    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var testIcon by rememberSaveable { mutableStateOf(Icon.LEFT) }
    var testDist by rememberSaveable { mutableStateOf(200) }
    var errorMemory by remember { mutableStateOf<ErrorMemoryRead?>(null) }
    val clockFailed = stringResource(R.string.diag_clock_failed)
    val shareSubject = stringResource(R.string.diag_log_share_subject)
    val shareTitle = stringResource(R.string.diag_log_share)

    fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, shareSubject)
            .putExtra(Intent.EXTRA_TEXT, Repo.log.value.joinToString("\n"))
        ctx.startActivity(Intent.createChooser(send, shareTitle))
    }

    val actions = object : DiagnosticsActions {
        override fun selectTestIcon(icon: Icon) { testIcon = icon }
        override fun selectTestDistance(m: Int) { testDist = m }
        override fun sendTest() = TestManeuver.start(NavState(testDist, testIcon, TEST_REMAINING_M))
        override fun stopTest() = TestManeuver.stop()
        override fun setClockNow() = connection.command(clockFailed) { setClock(settings[ClockSettings.bigEndian]) }
        override fun setBigEndian(on: Boolean) { settings[ClockSettings.bigEndian] = on }
        override fun readErrorMemory() {
            val bike = Repo.bike ?: return
            scope.launch {
                errorMemory = try {
                    val b = bike.readFull(ErrorCodes.HISTORY_TARGET, ErrorCodes.HISTORY_REG, ErrorCodes.HISTORY_LEN)
                    Repo.log("error memory 0xA5:120: ${b.size} bytes: ${b.hex()}")
                    ErrorMemoryRead(b.map { it.toInt() and 0xFF }, null)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Repo.log("error memory read failed: $e")
                    ErrorMemoryRead(null, e.message ?: e.javaClass.simpleName)
                }
            }
        }
        override fun openFullLog() { page = Page.LOG }
        override fun shareLog() = shareLog()
    }

    if (page != Page.MAIN) BackHandler { page = Page.MAIN }
    when (page) {
        Page.MAIN -> DiagnosticsContent(
            DiagnosticsUiState(
                connected = conn == ConnState.CONNECTED,
                testIcon = testIcon, testDistM = testDist, testNav = testNav,
                navDebug = navDebug, bigEndian = bigEndian, clock = clock,
                errorRaw = live?.report?.errorRaw, errorMemory = errorMemory,
                logTail = log.takeLast(4),
            ),
            actions,
            extra = { SimulatorHook.DiagnosticsCard() }, // debug builds only; the release stub draws nothing
            onBack = nav::back,
        )
        Page.LOG -> FullLog(log, { page = Page.MAIN }, ::shareLog)
    }
}

/** Stateless Diagnose screen (used by the screenshot test). */
@Composable
fun DiagnosticsContent(state: DiagnosticsUiState, actions: DiagnosticsActions, extra: @Composable () -> Unit = {}, onBack: () -> Unit) {
    Screen(header = { BackHeader(stringResource(R.string.diagnostics_title), onBack) }, bodyTop = 4.dp) {
        Text(
            stringResource(R.string.diag_intro), Modifier.padding(horizontal = 4.dp),
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted,
        )
        TestCard(state, actions)
        MapsDebugCard(state)
        ClockCard(state, actions)
        ErrorCard(state, actions)
        LogCard(state, actions)
        extra()
    }
}

@Composable
fun iconLabel(icon: Icon): String = stringResource(
    when (icon) {
        Icon.STRAIGHT -> R.string.diag_icon_straight
        Icon.LEFT -> R.string.diag_icon_left
        Icon.RIGHT -> R.string.diag_icon_right
        Icon.SLIGHT_LEFT -> R.string.diag_icon_slight_left
        Icon.SLIGHT_RIGHT -> R.string.diag_icon_slight_right
        Icon.SHARP_LEFT -> R.string.diag_icon_sharp_left
        Icon.SHARP_RIGHT -> R.string.diag_icon_sharp_right
        Icon.UTURN -> R.string.diag_icon_uturn
        Icon.ARRIVED -> R.string.diag_icon_arrived
        Icon.UNKNOWN -> R.string.diag_icon_unknown
    },
)

@Composable
private fun Caption(text: String) = Text(text, style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)

@Composable
private fun TestCard(state: DiagnosticsUiState, actions: DiagnosticsActions) {
    val fmt = rememberUnitFormatter()
    AppCard(gap = 12.dp) {
        CardTitle(stringResource(R.string.diag_test_title), description = stringResource(R.string.diag_test_sub))
        PillChipGroup(TEST_ICONS.map { iconLabel(it) }, TEST_ICONS.indexOf(state.testIcon), { actions.selectTestIcon(TEST_ICONS[it]) }, unselectedTextColor = AppTheme.colors.text)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_test_distance), style = AppTheme.type.label, color = AppTheme.colors.textMuted)
            SegmentedControl(
                TEST_DISTANCES_M.map { fmt.shortDistance(it.toDouble()).text() },
                TEST_DISTANCES_M.indexOf(state.testDistM),
                { actions.selectTestDistance(TEST_DISTANCES_M[it]) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton(stringResource(R.string.diag_test_send), actions::sendTest, Modifier.weight(2f))
            AppButton(stringResource(R.string.diag_test_stop), actions::stopTest, Modifier.weight(1f), kind = ButtonKind.OUTLINED, enabled = state.testNav != null)
        }
        state.testNav?.let { t ->
            val d = fmt.shortDistance(t.distM.toDouble()).text()
            Caption(stringResource(if (state.connected) R.string.diag_test_active else R.string.diag_test_waiting, iconLabel(t.icon), d))
        }
    }
}

@Composable
private fun MapsDebugCard(state: DiagnosticsUiState) {
    val c = AppTheme.colors
    val fmt = rememberUnitFormatter()
    var all by rememberSaveable { mutableStateOf(false) }
    AppCard(gap = 12.dp) {
        Text(stringResource(R.string.diag_maps_title), style = AppTheme.type.cardTitle)
        val d = state.navDebug
        if (d == null) {
            Caption(stringResource(R.string.diag_maps_none))
            return@AppCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.debugTile), contentAlignment = Alignment.Center) {
                val bmp = d.icon
                // Nearest-neighbour scaling keeps the arrow's pixels sharp; the grey shows white and black arrows.
                if (bmp != null) Image(
                    bmp.asImageBitmap(), stringResource(R.string.diag_maps_icon_cd), Modifier.size(48.dp),
                    contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                ) else AppIcon(AppIcons.maneuver(d.parsed?.icon ?: Icon.UNKNOWN), null, size = 36.dp, strokeWidth = 2.6f, tint = c.onInverse)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val p = d.parsed
                Text(
                    if (p != null) stringResource(R.string.diag_maps_recognised, iconLabel(p.icon), fmt.shortDistance(p.distM.toDouble()).text())
                    else stringResource(R.string.diag_maps_not_recognised),
                    style = AppTheme.type.itemTitleStrong,
                )
                val bmp = d.icon
                val fromText = d.textIcon != null || p?.icon == Icon.ARRIVED
                Caption(
                    when {
                        bmp == null -> stringResource(R.string.diag_maps_no_icon)
                        fromText -> stringResource(R.string.diag_maps_icon_text, bmp.width, bmp.height)
                        else -> stringResource(R.string.diag_maps_icon_shape, bmp.width, bmp.height)
                    },
                )
            }
        }
        SelectionContainer { MonoBlock(if (all || d.summary.isEmpty()) d.texts else d.summary) }
        if (d.summary.isNotEmpty()) AppButton(
            stringResource(if (all) R.string.diag_maps_less else R.string.diag_maps_all), { all = !all },
            kind = ButtonKind.OUTLINED, size = ButtonSize.SMALL,
        )
    }
}

@Composable
private fun ClockCard(state: DiagnosticsUiState, actions: DiagnosticsActions) {
    AppCard(gap = 12.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.diag_clock_title), style = AppTheme.type.cardTitle)
        AppButton(stringResource(R.string.diag_clock_set), actions::setClockNow, kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM, enabled = state.connected)
        state.clock?.let { Caption(clockResult(it)) }
        Column {
            Hairline()
            SwitchRow(stringResource(R.string.diag_clock_alt), state.bigEndian, actions::setBigEndian, Modifier.bleed(16.dp), subtitle = stringResource(R.string.diag_clock_alt_sub))
        }
    }
}

@Composable
private fun clockResult(c: ClockCheck): String {
    val at = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(c.atMs))
    val how = if (c.automatic) stringResource(R.string.diag_clock_auto) else ""
    return when {
        c.accepted -> stringResource(R.string.diag_clock_ok, at, how)
        c.readBackHex != null -> stringResource(R.string.diag_clock_bad, at, how, c.readBackHex)
        else -> stringResource(R.string.diag_clock_no_read, at, how)
    }
}

@Composable
private fun ErrorCard(state: DiagnosticsUiState, actions: DiagnosticsActions) {
    val t = AppTheme.type
    AppCard(gap = 12.dp) {
        CardTitle(stringResource(R.string.diag_errors_title), description = stringResource(R.string.diag_errors_sub))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.diag_errors_current), style = t.label, color = AppTheme.colors.textMuted)
            val raw = state.errorRaw
            val decoded = raw?.takeIf { it != 0 }?.let { ErrorCodes.currentFromReport(byteArrayOf(it.toByte())) }
            Text(
                when {
                    raw == null -> stringResource(R.string.diag_errors_not_connected)
                    raw == 0 -> "0x00 · " + stringResource(R.string.diag_errors_none)
                    else -> errorByteReadings(raw) + " → " + (decoded?.code?.let { stringResource(R.string.diag_errors_decoded, "%02d".format(it)) } ?: stringResource(R.string.diag_errors_invalid))
                },
                style = t.bodyStrong,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_errors_history), style = t.label, color = AppTheme.colors.textMuted)
            val m = state.errorMemory
            when {
                m == null -> Caption(stringResource(R.string.diag_errors_not_read))
                m.bytes == null -> Caption(stringResource(R.string.diag_errors_read_failed, m.error ?: ""))
                else -> {
                    SelectionContainer { MonoBlock(hexLines(m.bytes)) }
                    Caption(stringResource(R.string.diag_errors_answer, m.bytes.size, minOf(m.bytes.size, ErrorCodes.HISTORY_LEN)))
                    val codes = decodedHistory(m.bytes)
                    Caption(if (codes.isEmpty()) stringResource(R.string.diag_errors_history_empty) else stringResource(R.string.diag_errors_history_codes, codes.joinToString(", ")))
                }
            }
            AppButton(stringResource(R.string.diag_errors_read), actions::readErrorMemory, kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM, enabled = state.connected)
        }
    }
}

@Composable
private fun LogCard(state: DiagnosticsUiState, actions: DiagnosticsActions) {
    AppCard(gap = 12.dp) {
        Text(stringResource(R.string.diag_log_title), style = AppTheme.type.cardTitle)
        if (state.logTail.isEmpty()) Caption(stringResource(R.string.diag_log_empty)) else MonoBlock(state.logTail)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton(stringResource(R.string.diag_log_open), actions::openFullLog, Modifier.weight(1f), kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM)
            AppButton(stringResource(R.string.diag_log_share), actions::shareLog, Modifier.weight(1f), kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM, enabled = state.logTail.isNotEmpty())
        }
    }
}

@Composable
internal fun FullLog(log: List<String>, onBack: () -> Unit, onShare: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }
    ScreenFrame(header = {
        BackHeader(stringResource(R.string.diag_log_title), onBack, trailing = {
            dev.donrobo.ebike.ui.components.RoundIconButton(AppIcons.Share, stringResource(R.string.diag_log_share), onShare)
        })
    }) { inner ->
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, contentPadding = dev.donrobo.ebike.ui.components.pagePadding(inner, top = 0.dp)) {
                items(log) { Text(it, style = AppTheme.type.mono, color = AppTheme.colors.text, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}
