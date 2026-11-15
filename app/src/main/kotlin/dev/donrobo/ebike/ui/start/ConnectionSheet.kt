package dev.donrobo.ebike.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.SwitchRow
import dev.donrobo.ebike.ui.components.bleed
import dev.donrobo.ebike.ui.connection.ConnectionController
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Opened from the connection status on Start: Connect/Disconnect, auto-connect and the hints of the
 * pre-redesign connection section (permissions, pairing, pause by Disconnect). No artboard exists for
 * it; it uses the design-system components. The guided setup (E1/E2) will replace the pairing part.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(control: ConnectionController, status: ConnectionStatus, running: Boolean, onDismiss: () -> Unit) {
    val muted = AppTheme.colors.textMuted
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.conn_sheet_title), style = AppTheme.type.barTitle)
            if (!running) AppButton(stringResource(R.string.conn_connect), control::connect)
            else AppButton(stringResource(R.string.common_disconnect), control::disconnect, kind = ButtonKind.OUTLINED, size = ButtonSize.MEDIUM)
            if (control.companionSupported) {
                SwitchRow(stringResource(R.string.conn_auto), control.autoConnect, control::updateAutoConnect, Modifier.bleed(16.dp), subtitle = stringResource(R.string.conn_auto_sub))
                if (status != ConnectionStatus.CONNECTED) {
                    val hint = when {
                        !control.autoConnect -> R.string.conn_hint_auto_off
                        !control.associated -> R.string.conn_hint_pair
                        control.userStopped -> R.string.conn_hint_paused
                        else -> null
                    }
                    hint?.let { Text(stringResource(it), style = AppTheme.type.bodySmall, color = muted) }
                }
                Text(stringResource(R.string.conn_hint_disconnect), style = AppTheme.type.caption, color = muted)
            }
            control.missingPermissions.forEach { Text(stringResource(it), style = AppTheme.type.bodySmall, color = muted) }
            if (control.associated) {
                AppButton(stringResource(R.string.common_forget_display), { control.forgetDisplay(); onDismiss() }, kind = ButtonKind.DANGER_OUTLINED, size = ButtonSize.MEDIUM)
                Text(stringResource(R.string.common_forget_display_hint), style = AppTheme.type.caption, color = muted)
            }
        }
    }
}
