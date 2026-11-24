package dev.donrobo.ebike.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.bikes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The UI locale (the app language, Einstellungen) in Compose; recomposes when it changes. */
@Composable
fun currentLocale(): Locale = LocalLocale.current.platformLocale

/** The UI locale outside Compose (widget, notifications): the same one [currentLocale] gives. */
fun Context.uiLocale(): Locale = resources.configuration.locales[0] ?: Locale.getDefault()

/** The active bike (A2), following every change of the selection or the bike list. */
@Composable
fun rememberActiveBike(): State<Bike?> {
    val ctx = LocalContext.current
    val bikes = remember(ctx) { ctx.bikes }
    return remember(bikes) { bikes.activeFlow() }.collectAsStateWithLifecycle(bikes.active())
}

/** "Stand" of a last known value: time only for today, else short date and time ("14.11.26, 22:22"). */
fun formatAsOf(atMs: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val t = Instant.ofEpochMilli(atMs).atZone(zone)
    val f = if (t.toLocalDate() == today) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return f.withLocale(locale).format(t)
}
