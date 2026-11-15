package dev.donrobo.ebike.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.harmonize
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

private val LocalAppColors = staticCompositionLocalOf<AppColors> { error("AppColors: no RoboBikeTheme above this call") }
private val LocalAppType = staticCompositionLocalOf { AppTypography() }

/** Spacing values that recur across the artboards. Corner radii live in [AppShapes]. */
object AppDimens {
    val pagePadding = 20.dp        // horizontal page padding
    val cardGap = 12.dp            // gap between cards
    val cardPadding = 16.dp
    val touch = 48.dp              // Material minimum touch target
    val listRowHeight = 56.dp      // one/two-line Material list item
}

/** Access point for the design tokens: `AppTheme.colors.primary`, `AppTheme.type.cardTitle`. */
object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val type: AppTypography @Composable @ReadOnlyComposable get() = LocalAppType.current
}

/** Robo Bike green: the seed of the app's own colour scheme, the app's identity. */
val BrandSeed = Color(0xFF1F7A5C)

/**
 * Amber tones that carry a meaning (light on, consumed battery, waiting, maintenance due). They do not come from the
 * scheme, so "amber = light" holds whatever the wallpaper; [toAppColors] only harmonizes them toward the scheme's
 * primary (Material's custom-colour rule: hue shifted at most 15 degrees).
 */
private class Amber(val accent: Color, val tint: Color, val onTint: Color, val line: Color)
private val AmberLight = Amber(Color(0xFFF2A93B), Color(0xFFFDF1DA), Color(0xFF8A5300), Color(0xFFB5730A))
private val AmberDark = Amber(Color(0xFFF2A93B), Color(0xFF5E4100), Color(0xFFFFDDB3), Color(0xFFE8A33D))

/**
 * The Material colour scheme: from [BrandSeed] (Material tonal palette, the default), or from the wallpaper
 * (Material You) when [wallpaper] is on.
 */
fun appColorScheme(ctx: Context, dark: Boolean, wallpaper: Boolean): ColorScheme = when {
    wallpaper && dark -> dynamicDarkColorScheme(ctx)
    wallpaper -> dynamicLightColorScheme(ctx)
    else -> dynamicColorScheme(seedColor = BrandSeed, isDark = dark)
}

/**
 * Design tokens from a Material scheme. Each token takes the Material role that plays the same part: cards are
 * `surfaceContainer` (tonal, no border), the page is `background`, danger is `error`. Amber tones are fixed
 * ([Amber]), harmonized with the scheme. Map tokens are not here: the map keeps its own palette.
 */
fun ColorScheme.toAppColors(dark: Boolean): AppColors {
    val amber = if (dark) AmberDark else AmberLight
    fun Color.fit() = harmonize(primary)
    return AppColors(
        background = background,
        surface = surfaceContainer,
        border = outlineVariant,
        text = onSurface,
        textMuted = onSurfaceVariant,
        primary = primary,
        primaryStrong = onPrimaryContainer,
        primaryTint = primaryContainer,
        onPrimaryStrongMuted = primaryContainer,
        primarySoft = primary.copy(alpha = 0.4f),
        track = surfaceVariant,
        outline = outline,
        neutral = outline,
        inactive = outlineVariant,
        accent = amber.accent.fit(),
        accentTint = amber.tint.fit(),
        onAccentTint = amber.onTint.fit(),
        accentLine = amber.line.fit(),
        danger = error,
        dangerBorder = error.copy(alpha = 0.5f),
        inverse = inverseSurface,
        onInverse = inverseOnSurface,
        onInverseMuted = inverseOnSurface.copy(alpha = 0.7f),
        onPrimary = onPrimary,
        debugTile = outline,
    )
}

/**
 * Corner radii on the Material shape scale: chips/small tags 8, banners and inner panels 12, text fields and
 * segmented rails 16, cards 24, hero cards and sheets 28. Buttons keep Material's full pill.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The Material type scale on the system font; sizes follow the artboards where they name one, else Material's. */
fun AppTypography.toMaterial(): Typography = Typography(
    displayLarge = numberHero, displayMedium = numberLarge, displaySmall = numberTileLarge,
    headlineLarge = pageTitle, headlineMedium = bikeTitle, headlineSmall = barTitle,
    titleLarge = statusTitle, titleMedium = cardTitle, titleSmall = label,
    bodyLarge = itemTitle.copy(fontWeight = body.fontWeight), bodyMedium = body, bodySmall = caption,
    labelLarge = label, labelMedium = tab, labelSmall = tab.copy(fontSize = caption.fontSize * 0.85f),
)

/** Tokens for code outside composition (notifications, widget): the same scheme and mapping as [RoboBikeTheme]. */
fun Context.appColors(): AppColors {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return appColorScheme(this, night, settings[GeneralSettings.wallpaperColors]).toAppColors(night)
}

/**
 * Theme of the app: Material 3 with the Robo Bike scheme or the wallpaper's ([appColorScheme], setting
 * [GeneralSettings.wallpaperColors]), light or dark as the system is, the design's type scale and shapes.
 * [AppTheme] also exposes the tokens for bespoke drawings (charts, maps, big numbers); Material components take
 * their colours from the scheme.
 */
@Composable
fun RoboBikeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val wallpaper by remember { ctx.settings.flow(GeneralSettings.wallpaperColors) }.collectAsState(ctx.settings[GeneralSettings.wallpaperColors])
    val scheme = remember(dark, wallpaper) { appColorScheme(ctx, dark, wallpaper) }
    val palette = remember(scheme, dark) { scheme.toAppColors(dark) }
    val t = AppTypography()
    CompositionLocalProvider(LocalAppColors provides palette, LocalAppType provides t) {
        MaterialTheme(colorScheme = scheme, typography = t.toMaterial(), shapes = AppShapes) {
            CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
        }
    }
}
