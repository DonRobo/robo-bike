package dev.donrobo.ebike.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.donrobo.ebike.R

/** Outfit: the Robo Bike face for big numbers and page titles (OFL, see assets/licenses). Text is the system font. */
val Outfit = FontFamily(
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

/** Text in the system font (Google Sans on Pixel): sizes and weights from the artboards, no bundled fonts. */
private fun styleOf(size: Int, weight: FontWeight) = TextStyle(fontSize = size.sp, fontWeight = weight)

/** Outfit style for page titles and big numbers; [tracking] in em like the artboards' letter-spacing. */
fun displayStyle(size: Int, weight: FontWeight = FontWeight.SemiBold, tracking: Double = 0.0, lineHeight: Double? = null) =
    TextStyle(
        fontFamily = Outfit, fontSize = size.sp, fontWeight = weight,
        letterSpacing = if (tracking == 0.0) TextUnit.Unspecified else tracking.em,
        lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
        lineHeightStyle = if (lineHeight != null) CssLineHeight else null,
    )

/** CSS line-height semantics: the (possibly negative) leading is split evenly above and below the glyphs. */
val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * Named text styles taken from the artboards (size/weight in the comment = CSS). Colour is not part
 * of a style; pass it at the call site (usually `AppTheme.colors.text` or `textMuted`).
 */
@Immutable
data class AppTypography(
    // Headings
    val pageTitle: TextStyle = displayStyle(36, FontWeight.Medium, tracking = -0.01), // tab roots, large top bar: "Fahrten", "Einstellungen"
    val bikeTitle: TextStyle = displayStyle(28, tracking = -0.01),           // Start: bike name
    val detailTitle: TextStyle = displayStyle(26, tracking = -0.01),         // ride detail date
    val barTitle: TextStyle = displayStyle(22, FontWeight.Medium),           // back header title
    val statusTitle: TextStyle = styleOf(22, FontWeight.SemiBold),           // Licht: "Licht ist an"
    // Numbers
    val numberHero: TextStyle = displayStyle(80, tracking = -0.03, lineHeight = 0.95), // Start: remaining range
    val numberLarge: TextStyle = displayStyle(52, tracking = -0.02, lineHeight = 1.0), // Reichweite: now / full
    val numberTileLarge: TextStyle = displayStyle(48, lineHeight = 1.05),    // Lenkermodus tiles
    val numberTile: TextStyle = displayStyle(30),                            // Start value tiles, cost
    val numberMedium: TextStyle = displayStyle(28),                          // ride tiles, week summary, sun-curve times
    val numberSmall: TextStyle = displayStyle(26),                           // statistics summary
    // Text
    val cardTitle: TextStyle = styleOf(17, FontWeight.Medium),               // section titles, list item headline in cards
    val itemTitle: TextStyle = styleOf(16, FontWeight.Medium),               // settings rows, radio rows
    val itemTitleStrong: TextStyle = styleOf(16, FontWeight.Bold),           // switch cards, maintenance items
    val body: TextStyle = styleOf(15, FontWeight.Normal),                    // value rows, ride summary line
    val bodyStrong: TextStyle = styleOf(15, FontWeight.SemiBold),            // key-value values, outlined buttons
    val label: TextStyle = styleOf(14, FontWeight.SemiBold),                 // muted field labels, status line, small buttons
    val labelStrong: TextStyle = styleOf(14, FontWeight.Bold),               // selected segment, filled small button
    val bodySmall: TextStyle = styleOf(14, FontWeight.Normal),               // subtitles/descriptions (muted)
    val caption: TextStyle = styleOf(13, FontWeight.Normal),                 // fine print, list row subtitle
    val captionMedium: TextStyle = styleOf(13, FontWeight.Medium),           // Start range footer
    val captionStrong: TextStyle = styleOf(13, FontWeight.SemiBold),         // value tile label
    val sectionLabel: TextStyle = styleOf(14, FontWeight.Medium),            // settings group heading (primary colour), chips
    val tab: TextStyle = styleOf(12, FontWeight.SemiBold),                   // bottom navigation (selected: Bold)
    val unitLarge: TextStyle = styleOf(22, FontWeight.SemiBold),             // unit next to numberHero
    val unitTileLarge: TextStyle = styleOf(20, FontWeight.SemiBold),         // unit in Lenkermodus tiles
    val unitMedium: TextStyle = styleOf(18, FontWeight.SemiBold),            // unit next to numberLarge
    val unit: TextStyle = styleOf(14, FontWeight.SemiBold),                  // unit next to numberTile/numberMedium
    val buttonLarge: TextStyle = styleOf(16, FontWeight.Bold),               // primary button (52 dp)
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 1.5.em, lineHeightStyle = CssLineHeight), // debug blocks
)
