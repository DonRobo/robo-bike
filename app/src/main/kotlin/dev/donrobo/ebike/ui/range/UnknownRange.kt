package dev.donrobo.ebike.ui.range

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Stand-in for the Start screen's 80 sp range number while nothing is learned: two soft placeholder
 * digits and the muted unit on one compact line (the card looks like the learned state minus the number),
 * instead of a glyph dash that renders as a heavy black bar at that size. The footer explains why ("Wird aus
 * deinen ersten Fahrten gelernt"), or, once learned but without a battery level, shows the full-battery range.
 */
@Composable
fun UnknownHeroNumber(unit: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(Modifier.size(32.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(c.inactive)) }
        }
        Text(unit, style = AppTheme.type.unitLarge, color = c.textMuted)
    }
}
