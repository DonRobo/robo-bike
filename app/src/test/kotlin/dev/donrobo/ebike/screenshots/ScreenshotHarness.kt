package dev.donrobo.ebike.screenshots

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.donrobo.ebike.ui.ShellFrame
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.RoboBikeTheme
import org.robolectric.Shadows

/** The screen as the app shows it: theme, and the shell's frame with the bottom navigation for [tab]. */
@Composable
private fun Shell(tab: Tab?, content: @Composable () -> Unit) {
    RoboBikeTheme { ShellFrame(tab, {}) { content() } }
}

/**
 * Renders [content] like the artboards: 390 dp wide, the device height from the test's @Config
 * qualifiers, and the bottom navigation for [tab] (null = sub-screen without bar). Writes
 * `app/build/screenshots/<name>.png` when run via `./gradlew :app:recordRoborazziDebug`.
 */
fun ComposeContentTestRule.screenshot(name: String, tab: Tab? = null, content: @Composable () -> Unit) {
    setContent { Shell(tab, content) }
    onRoot().captureRoboImage("build/screenshots/$name.png")
}

/**
 * Like [screenshot], but captures the whole screen including dialog and bottom-sheet windows that [content] opens
 * (Material AlertDialog, ModalBottomSheet), over a dimmed scrim like on the phone.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun AndroidComposeTestRule<*, ComponentActivity>.screenshotWindows(name: String, tab: Tab? = null, content: @Composable () -> Unit) {
    // activity.setContent instead of the rule's setContent, and a fixed clock: under Robolectric a text field in a
    // dialog window never lets waitForIdle() finish. Two seconds cover the dialog/sheet open animations.
    mainClock.autoAdvance = false
    activity.setContent { Shell(tab, content) }
    repeat(20) { mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle() }
    captureScreenRoboImage("build/screenshots/$name.png")
}

/** How far a [scrolled] screenshot scrolls the screen. */
sealed interface Scroll {
    /** One finger drag of [distance] (no fling): a large top bar collapses first, then the content moves. */
    data class By(val distance: Dp) : Scroll

    /** Like [By], captured while the finger is still down: a half-collapsed bar snaps open or shut once it is lifted. */
    data class Holding(val distance: Dp) : Scroll

    /** Dragged until the content ends: end padding, last item above the bottom navigation / gesture area. */
    data object ToEnd : Scroll

    companion object {
        /** A large top bar half collapsed (it collapses over 88 dp) under the finger, the content not moved yet. */
        val COLLAPSING = Holding(44.dp)

        /** The top bar collapsed and the content scrolled well under it (and under tabs below it). */
        val UNDER = By(260.dp)
    }
}

/** System bars of an edge-to-edge phone: their insets go to the content, and they are drawn over it. */
enum class SystemBars(val status: Dp, val navigation: Dp) {
    /** Gesture navigation: a thin handle at the bottom. */
    GESTURE(32.dp, 24.dp),

    /** 3-button navigation: a 48 dp bar. */
    BUTTONS(32.dp, 48.dp),
}

/** Qualifiers for [scrolled] shots: a common phone height (390 x 844 dp), so long pages really scroll. */
const val SCROLL_PHONE = "w390dp-h844dp-port-xhdpi"

private const val SCREEN_TAG = "screen"

/**
 * Like [screenshot], but on a phone with [bars] (their insets dispatched to the content, the status bar and the
 * navigation handle/buttons drawn transparent over it, as edge-to-edge on the phone) and after [scroll] by touch
 * input, so the top bar's nested scroll sees it and a large title really collapses. Use [SCROLL_PHONE].
 */
fun ComposeContentTestRule.scrolled(
    name: String, tab: Tab? = null, scroll: Scroll, bars: SystemBars = SystemBars.GESTURE, content: @Composable () -> Unit,
) {
    var view: View? = null
    setContent {
        view = LocalView.current
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag(SCREEN_TAG)) { Shell(tab, content) }
            RoboBikeTheme { SystemBarsOverlay(bars) }
        }
    }
    runOnIdle {
        val v = view!!
        val px = { d: Dp -> (d.value * v.resources.displayMetrics.density).toInt() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(bars.status), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(bars.navigation)))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        ViewCompat.dispatchApplyWindowInsets(v, insets)
    }
    waitForIdle()
    val screen = onNodeWithTag(SCREEN_TAG)
    when (scroll) {
        is Scroll.By -> screen.performTouchInput { dragUp(scroll.distance.toPx()); release() }
        is Scroll.Holding -> screen.performTouchInput { dragUp(scroll.distance.toPx()) }
        Scroll.ToEnd -> repeat(30) { screen.performTouchInput { dragUp(height * 0.6f); release() } }
    }
    waitForIdle()
    onRoot().captureRoboImage("build/screenshots/$name.png")
    if (scroll is Scroll.Holding) screen.performTouchInput { release() }
}

/** Puts a finger down in the lower part of the screen and drags it up by [distance] px (after the touch slop). */
private fun TouchInjectionScope.dragUp(distance: Float) {
    down(Offset(centerX, height * 0.8f))
    moveBy(Offset(0f, -viewConfiguration.touchSlop)) // the drag starts after the touch slop; the rest of the way scrolls
    val steps = (distance / 20f).toInt().coerceAtLeast(1)
    repeat(steps) { moveBy(Offset(0f, -distance / steps)) }
}

/** Holds the finger still before lifting it, so the drag ends without a fling. */
private fun TouchInjectionScope.release() {
    advanceEventTime(200)
    up()
}

/** The status bar (clock, icons) and the navigation handle or buttons, transparent like edge-to-edge on the phone. */
@Composable
private fun SystemBarsOverlay(bars: SystemBars) {
    val ink = AppTheme.colors.text
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxWidth().height(bars.status).align(Alignment.TopCenter)) {
            val h = size.height
            drawRoundRect(ink, Offset(24.dp.toPx(), h * 0.35f), Size(32.dp.toPx(), h * 0.3f), CornerRadius(2.dp.toPx())) // clock
            for (i in 0..2) drawCircle(ink, 5.dp.toPx(), Offset(size.width - (24 + 18 * i).dp.toPx(), h / 2)) // icons
        }
        Canvas(Modifier.fillMaxWidth().height(bars.navigation).align(Alignment.BottomCenter)) {
            when (bars) {
                SystemBars.GESTURE -> handle(ink)
                SystemBars.BUTTONS -> buttons(ink)
            }
        }
    }
}

private fun DrawScope.handle(ink: androidx.compose.ui.graphics.Color) {
    val w = 108.dp.toPx()
    drawRoundRect(ink, Offset((size.width - w) / 2, size.height / 2 - 2.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
}

private fun DrawScope.buttons(ink: androidx.compose.ui.graphics.Color) {
    val stroke = Stroke(2.dp.toPx())
    val y = size.height / 2
    val r = 8.dp.toPx()
    listOf(0.25f, 0.5f, 0.75f).forEachIndexed { i, f ->
        val x = size.width * f
        when (i) {
            0 -> drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x + r, y - r); lineTo(x - r, y); lineTo(x + r, y + r); close() }, ink, style = stroke)
            1 -> drawCircle(ink, r, Offset(x, y), style = stroke)
            else -> drawRect(ink, Offset(x - r, y - r), Size(2 * r, 2 * r), style = stroke)
        }
    }
}

/** Qualifiers: 390 dp wide at 2x (780 px), the width of the design artboards. */
const val PHONE = "w390dp-h1060dp-port-xhdpi"
