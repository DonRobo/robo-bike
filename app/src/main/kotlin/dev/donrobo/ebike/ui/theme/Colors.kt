package dev.donrobo.ebike.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Design tokens of the app, derived from the system colour scheme by [toAppColors] (dynamic colour). Material
 * components read the scheme directly; use these tokens (`AppTheme.colors`) for bespoke drawings and for tones
 * with no Material role (amber accent, charts). Never raw hex in screens.
 */
@Immutable
data class AppColors(
    val background: Color,        // page background; also the Lenkermodus tile fill
    val surface: Color,           // cards, sheets, segmented "selected" pill
    val border: Color,            // dividers inside cards, round buttons
    val text: Color,              // primary text and icons
    val textMuted: Color,         // labels, subtitles, units, inactive tabs
    val primary: Color,           // switch on, progress fill, primary buttons, active tab, links
    val primaryStrong: Color,     // text on primaryTint, "connected" status, summary card
    val primaryTint: Color,       // positive banners/chips, battery track, settings row icon badges
    val onPrimaryStrongMuted: Color, // secondary text on primaryStrong
    val primarySoft: Color,       // inactive bars in bar charts
    val track: Color,             // segmented control, neutral progress track
    val outline: Color,           // outlined buttons and pill chips
    val neutral: Color,           // switch off, text field border, chart guides
    val inactive: Color,          // setup step indicator, not reached
    val accent: Color,            // light on, consumed battery, maintenance due
    val accentTint: Color,        // amber icon badge / warning background
    val onAccentTint: Color,      // text/icons on accentTint, "due soon" text
    val accentLine: Color,        // switch-point lines on the sun curve
    val danger: Color,            // destructive actions
    val dangerBorder: Color,      // outline of destructive buttons
    val inverse: Color,           // dark panel (display preview), selected pill chip
    val onInverse: Color,
    val onInverseMuted: Color,    // secondary text on inverse
    val onPrimary: Color,
    val debugTile: Color,         // grey behind the Maps arrow bitmap
    // Map palette: fixed; the map style is drawn in these colours whatever the system scheme.
    val mapLand: Color = Color(0xFFE9EEE6),   // illustrated map placeholders
    val mapPark: Color = Color(0xFFD5E6CE),
    val mapWater: Color = Color(0xFFCDE1EC),
)
