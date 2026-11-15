package dev.donrobo.ebike.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitLabel
import dev.donrobo.ebike.units.res

/** The [UnitFormatter] for the current units setting and UI locale; recomposes when either changes. */
@Composable
fun rememberUnitFormatter(): UnitFormatter {
    val ctx = LocalContext.current
    val units by remember { ctx.settings.flow(GeneralSettings.units) }.collectAsStateWithLifecycle(ctx.settings[GeneralSettings.units])
    val locale = currentLocale()
    return remember(units, locale) { UnitFormatter(units, locale) }
}

@Composable
fun unitLabel(u: UnitLabel): String = stringResource(u.res())

/** "4,2 km" as one string (for running text). */
@Composable
fun Measure.text(): String = "$value ${unitLabel(unit)}"
