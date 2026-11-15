package dev.donrobo.ebike.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.components.StepIndicator
import dev.donrobo.ebike.ui.theme.AppTheme
import dev.donrobo.ebike.ui.theme.CssLineHeight

/** Setup headline: 30/600, line-height 1.15 (artboards Einrichtung, Berechtigungen). */
internal val headlineStyle: TextStyle
    @Composable @ReadOnlyComposable get() = AppTheme.type.pageTitle.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.15.em, lineHeightStyle = CssLineHeight)

/** Setup lead text: 16/400, line-height 1.45, muted. */
internal val leadStyle: TextStyle
    @Composable @ReadOnlyComposable get() = AppTheme.type.body.copy(fontSize = 16.sp, lineHeight = 1.45.em, lineHeightStyle = CssLineHeight)

/**
 * Page of the guided setup: padding 24, gap 16, "Schritt n von 3" on top, [bottom] pinned to the bottom (the
 * artboards' `margin-top: auto`) while the page fits, scrolling otherwise.
 */
@Composable
internal fun SetupFrame(step: Int, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // The status-bar inset outside the scroll (like Start): the page scrolls below the status bar, never under its icons.
    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(stringResource(R.string.setup_step, step, SETUP_STEPS), step, SETUP_STEPS)
            content()
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = bottom)
        }
    }
}

/** Headline + lead text block (gap 8). */
@Composable
internal fun SetupIntro(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = headlineStyle)
        Text(body, style = leadStyle, color = AppTheme.colors.textMuted)
    }
}

enum class OrbitCenter { DISPLAY, CHECK }

/** The artboard's 342×200 illustration: two rings around a tinted disc with the display (or a check) and a dot. */
@Composable
internal fun OrbitIllustration(center: OrbitCenter, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Canvas(modifier.fillMaxWidth().aspectRatio(342f / 200f)) {
        val s = size.width / 342f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        drawCircle(c.primary.copy(alpha = 0.18f), 92 * s, p(171f, 100f), style = Stroke(2 * s))
        drawCircle(c.primary.copy(alpha = 0.32f), 66 * s, p(171f, 100f), style = Stroke(2 * s))
        drawCircle(c.primaryTint, 40 * s, p(171f, 100f))
        val line = Stroke(2.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (center) {
            OrbitCenter.DISPLAY -> {
                drawRoundRect(c.surface, p(149f, 82f), Size(44 * s, 36 * s), CornerRadius(9 * s))
                drawRoundRect(c.text, p(149f, 82f), Size(44 * s, 36 * s), CornerRadius(9 * s), style = Stroke(2.5f * s))
                val path = Path().apply { moveTo(159 * s, 108 * s); lineTo(167 * s, 98 * s); lineTo(173 * s, 104 * s); lineTo(183 * s, 92 * s) }
                drawPath(path, c.primary, style = line)
                drawCircle(c.accent, 7 * s, p(236f, 52f))
            }
            OrbitCenter.CHECK -> {
                val path = Path().apply { moveTo(155 * s, 101 * s); lineTo(166 * s, 112 * s); lineTo(188 * s, 89 * s) }
                drawPath(path, c.primaryStrong, style = Stroke(4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(c.primary, 7 * s, p(236f, 52f))
            }
        }
    }
}
