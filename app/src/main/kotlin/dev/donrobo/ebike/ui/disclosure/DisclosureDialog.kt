package dev.donrobo.ebike.ui.disclosure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.components.AppDialog
import dev.donrobo.ebike.ui.components.DialogButton
import dev.donrobo.ebike.ui.theme.AppTheme

/** Texts of one disclosure: what is read and for which features ([points]), where it goes, that it is optional. */
private class DisclosureText(val title: Int, val lead: Int, val points: List<Int>, val after: List<Int>)

private fun textOf(d: Disclosure) = when (d) {
    Disclosure.BACKGROUND_LOCATION -> DisclosureText(
        R.string.disclosure_bg_location_title, R.string.disclosure_bg_location_lead,
        listOf(
            R.string.disclosure_bg_location_track, R.string.disclosure_bg_location_parked,
            R.string.disclosure_bg_location_home, R.string.disclosure_bg_location_light,
        ),
        listOf(R.string.disclosure_bg_location_data, R.string.disclosure_bg_location_optional),
    )
    Disclosure.NOTIFICATION_ACCESS -> DisclosureText(
        R.string.disclosure_listener_title, R.string.disclosure_listener_lead,
        listOf(R.string.disclosure_listener_only_maps, R.string.disclosure_listener_data),
        listOf(R.string.disclosure_listener_optional),
    )
}

/**
 * The prominent disclosure for [d] as a Material dialog. [onAgree] is the only way on to the system dialog or page;
 * [onDecline] (also back and a tap outside) asks for nothing. [backgroundOptionLabel] is the system's name for
 * "Allow all the time" (blank when the system has none).
 */
@Composable
fun DisclosureDialog(d: Disclosure, onAgree: () -> Unit, onDecline: () -> Unit, backgroundOptionLabel: String = "") {
    val text = textOf(d)
    val next = when {
        d == Disclosure.NOTIFICATION_ACCESS -> stringResource(R.string.disclosure_listener_next)
        backgroundOptionLabel.isNotBlank() -> stringResource(R.string.disclosure_bg_location_next, backgroundOptionLabel)
        else -> stringResource(R.string.disclosure_bg_location_next_generic)
    }
    val body = AppTheme.type.body
    val muted = AppTheme.colors.textMuted
    AppDialog(
        stringResource(text.title), onDecline,
        confirm = { DialogButton(stringResource(R.string.disclosure_agree), onAgree) },
        dismiss = { DialogButton(stringResource(R.string.disclosure_not_now), onDecline) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(text.lead), style = body)
            text.points.forEach { Text(stringResource(R.string.disclosure_bullet, stringResource(it)), style = body) }
        }
        text.after.forEach { Text(stringResource(it), style = body, color = muted) }
        Text(next, style = AppTheme.type.bodySmall, color = muted)
    }
}
