package dev.donrobo.ebike.ui.rides

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ride.RideShape
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.map.GeoBounds
import dev.donrobo.ebike.ui.components.map.MapGeometry
import dev.donrobo.ebike.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.donrobo.ebike.ui.components.currentLocale

/** "29 min" below an hour, "1:15 h" from an hour on. */
@Composable
fun durationText(seconds: Long): String {
    val min = (seconds + 30) / 60
    return if (min < 60) stringResource(R.string.rides_minutes, min.toInt())
    else stringResource(R.string.rides_hours_minutes, (min / 60).toInt(), (min % 60).toInt())
}

/** Duration as number + unit for the value tiles ("29" "min", "1:15" "h"). */
@Composable
fun durationParts(seconds: Long): Pair<String, String> {
    val min = (seconds + 30) / 60
    return if (min < 60) "$min" to stringResource(R.string.ride_unit_min)
    else "%d:%02d".format(min / 60, min % 60) to stringResource(R.string.ride_unit_h)
}

/** Hours:minutes for the summaries ("1:15" over "Stunden"). */
fun hoursMinutes(seconds: Long): String {
    val min = (seconds + 30) / 60
    return "%d:%02d".format(min / 60, min % 60)
}

/** "Mittwoch, 11. November" (with the year when it is not this year). */
@Composable
fun dayText(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val pattern = stringResource(if (date.year == today.year) R.string.rides_day_pattern else R.string.rides_day_pattern_year)
    return DateTimeFormatter.ofPattern(pattern, currentLocale()).format(date)
}

@Composable
fun clockText(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(stringResource(R.string.rides_time_pattern), currentLocale()).format(Instant.ofEpochMilli(epochMs).atZone(zone))

/**
 * Small map-like thumbnail of a ride (84 dp, radius 14): the outline on the map land colour, like the
 * artboard's list icons. Drawn from [RideShape] (from the index), so the list never parses tracks.
 * Without a track: a muted route icon on the same ground.
 */
@Composable
fun RideThumbnail(shape: RideShape?, modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val c = AppTheme.colors
    Box(modifier.size(size).clip(RoundedCornerShape(14.dp)).background(c.mapLand), contentAlignment = Alignment.Center) {
        if (shape == null) {
            AppIcon(AppIcons.TabRides, null, size = 28.dp, tint = c.neutral)
            return@Box
        }
        Canvas(Modifier.matchParentSize()) {
            // two light "streets" for the map look, then the outline fitted into the tile
            drawLine(c.surface, Offset(0f, this.size.height * 0.36f), Offset(this.size.width, this.size.height * 0.26f), 4.dp.toPx())
            drawLine(c.surface, Offset(this.size.width * 0.36f, 0f), Offset(this.size.width * 0.45f, this.size.height), 4.dp.toPx())
            val b = GeoBounds.of(shape.points) ?: return@Canvas
            val proj = MapGeometry.Projection(b, this.size.width, this.size.height, 14.dp.toPx())
            val p = Path()
            shape.points.forEachIndexed { i, g -> if (i == 0) p.moveTo(proj.x(g), proj.y(g)) else p.lineTo(proj.x(g), proj.y(g)) }
            drawPath(p, c.primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(c.primary, 4.dp.toPx(), Offset(proj.x(shape.end), proj.y(shape.end)))
        }
    }
}
