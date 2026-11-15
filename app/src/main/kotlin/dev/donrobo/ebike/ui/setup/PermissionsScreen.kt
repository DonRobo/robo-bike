package dev.donrobo.ebike.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcon
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.ButtonSize
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.theme.AppTheme

/** Artboard Berechtigungen outside the setup: opened from the settings, same list, no step and no "Weiter". */
@Composable
fun PermissionsScreen(nav: AppNavigator) {
    val requests = rememberPermissionRequests()
    Screen(header = { BackHeader(stringResource(R.string.setup_permissions_title), nav::back) }, gap = 10.dp) {
        Text(stringResource(R.string.setup_perm_body), style = leadStyle, color = AppTheme.colors.textMuted)
        PermissionList(requests.items, requests.backgroundOptionLabel, requests::request)
    }
    PermissionDisclosureHost(requests)
}

/** Setup step 2 (artboard Berechtigungen), stateless. */
@Composable
fun PermissionsStepContent(items: List<PermissionItem>, backgroundOptionLabel: String, onAllow: (AppPermission) -> Unit, onContinue: () -> Unit) {
    val muted = AppTheme.colors.textMuted
    val ok = canContinue(items)
    SetupFrame(
        step = 2,
        bottom = {
            if (!ok) Text(stringResource(R.string.setup_perm_bluetooth_required), style = AppTheme.type.bodySmall, color = AppTheme.colors.danger)
            AppButton(stringResource(R.string.setup_perm_continue), onContinue, size = ButtonSize.HERO, enabled = ok)
            Text(
                stringResource(R.string.setup_perm_later), Modifier.fillMaxWidth(),
                style = AppTheme.type.caption, color = muted, textAlign = TextAlign.Center,
            )
        },
    ) {
        SetupIntro(stringResource(R.string.setup_perm_title), stringResource(R.string.setup_perm_body))
        PermissionList(items, backgroundOptionLabel, onAllow)
    }
}


@Composable
internal fun PermissionList(items: List<PermissionItem>, backgroundOptionLabel: String, onAllow: (AppPermission) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { PermissionCard(it, backgroundOptionLabel) { onAllow(it.permission) } }
    }
}

@Composable
internal fun permissionTitle(p: AppPermission): String = stringResource(
    when (p) {
        AppPermission.BLUETOOTH -> R.string.setup_perm_bluetooth
        AppPermission.NOTIFICATION_ACCESS -> R.string.setup_perm_listener
        AppPermission.LOCATION -> R.string.setup_perm_location
        AppPermission.BACKGROUND_LOCATION -> R.string.setup_perm_bg_location
        AppPermission.BACKGROUND_RUN -> R.string.setup_perm_background
        AppPermission.NOTIFICATIONS -> R.string.setup_perm_notifications
    },
)

@Composable
private fun permissionSubtitle(p: AppPermission): String = stringResource(
    when (p) {
        AppPermission.BLUETOOTH -> R.string.setup_perm_bluetooth_sub
        AppPermission.NOTIFICATION_ACCESS -> R.string.setup_perm_listener_sub
        AppPermission.LOCATION -> R.string.setup_perm_location_sub
        AppPermission.BACKGROUND_LOCATION -> R.string.setup_perm_bg_location_sub
        AppPermission.BACKGROUND_RUN -> R.string.setup_perm_background_sub
        AppPermission.NOTIFICATIONS -> R.string.setup_perm_notifications_sub
    },
)

/** One permission: what it unlocks, its real state ("Erlaubt") or "Erlauben" (padding 14/16, radius 20). */
@Composable
private fun PermissionCard(item: PermissionItem, backgroundOptionLabel: String, onAllow: () -> Unit) {
    val c = AppTheme.colors
    val t = AppTheme.type
    val note = when {
        item.permission == AppPermission.LOCATION && item.state == PermissionState.PARTIAL -> stringResource(R.string.setup_perm_location_partial)
        item.permission == AppPermission.BACKGROUND_LOCATION && item.state == PermissionState.BLOCKED -> stringResource(R.string.setup_perm_bg_location_blocked)
        item.permission == AppPermission.BACKGROUND_LOCATION && item.state == PermissionState.MISSING && backgroundOptionLabel.isNotBlank() ->
            stringResource(R.string.setup_perm_bg_location_how, backgroundOptionLabel)
        else -> null
    }
    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(permissionTitle(item.permission), style = t.itemTitleStrong)
                Text(permissionSubtitle(item.permission), style = t.bodySmall, color = c.textMuted)
                if (note != null) Text(note, style = t.caption, color = c.onAccentTint)
            }
            when (item.state) {
                PermissionState.GRANTED -> Row(
                    Modifier.heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppIcon(AppIcons.Check, null, size = 18.dp, strokeWidth = 2.4f, tint = c.primaryStrong)
                    Text(stringResource(R.string.setup_perm_granted), style = t.labelStrong, color = c.primaryStrong)
                }
                PermissionState.PARTIAL -> AppButton(stringResource(R.string.setup_perm_allow_precise), onAllow, kind = ButtonKind.TONAL, size = ButtonSize.SMALL, fillWidth = false)
                PermissionState.MISSING -> AppButton(stringResource(R.string.common_allow), onAllow, kind = ButtonKind.TONAL, size = ButtonSize.SMALL, fillWidth = false)
                PermissionState.BLOCKED -> AppButton(stringResource(R.string.common_allow), onAllow, kind = ButtonKind.TONAL, size = ButtonSize.SMALL, fillWidth = false, enabled = false)
            }
        }
    }
}
