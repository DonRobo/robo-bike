package dev.donrobo.ebike.ui.bike

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.donrobo.ebike.R
import dev.donrobo.ebike.errors.CurrentDriveError
import dev.donrobo.ebike.notify.driveErrorText
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.Tone

/**
 * The drive error the display reports right now ([dev.donrobo.ebike.errors.DriveErrorState.current]), as a banner on
 * Start and in the Lenkermodus. [onExplain] leads to the Rad tab, where the error card explains it (W2).
 */
@Composable
fun DriveErrorBanner(error: CurrentDriveError, onExplain: () -> Unit, modifier: Modifier = Modifier) {
    val title = driveErrorText(LocalContext.current, error.code).title
    val text = error.code?.let { stringResource(R.string.bike_drive_error_banner_code, it, title) }
        ?: stringResource(R.string.bike_drive_error_banner, title)
    StatusBanner(
        text, modifier, tone = Tone.DANGER, icon = AppIcons.Warning,
        action = { TextButton(onExplain) { Text(stringResource(R.string.bike_drive_error_explain)) } },
    )
}
