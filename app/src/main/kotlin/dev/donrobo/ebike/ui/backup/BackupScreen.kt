package dev.donrobo.ebike.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.backup.Backup
import dev.donrobo.ebike.backup.BackupManager
import dev.donrobo.ebike.backup.DataOverview
import dev.donrobo.ebike.backup.StagedBackup
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.ui.AppNavigator
import dev.donrobo.ebike.ui.components.AppButton
import dev.donrobo.ebike.ui.components.AppCard
import dev.donrobo.ebike.ui.components.AppIcons
import dev.donrobo.ebike.ui.components.BackHeader
import dev.donrobo.ebike.ui.components.ButtonKind
import dev.donrobo.ebike.ui.components.CardTitle
import dev.donrobo.ebike.ui.components.Screen
import dev.donrobo.ebike.ui.components.StatusBanner
import dev.donrobo.ebike.ui.components.Tone
import dev.donrobo.ebike.ui.components.ConfirmDialog
import dev.donrobo.ebike.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import dev.donrobo.ebike.ui.components.rememberActiveBike
import dev.donrobo.ebike.ui.components.currentLocale

enum class BackupBusy { EXPORTING, CHECKING, RESTORING }

/** Outcome line under the buttons. */
sealed interface BackupMessage {
    data object Exported : BackupMessage
    data object ExportFailed : BackupMessage
    data object Restored : BackupMessage
    data object RestoreFailed : BackupMessage
    data object Recording : BackupMessage
    data class Invalid(val problem: Backup.Problem) : BackupMessage
}

data class BackupUiState(val current: DataOverview, val busy: BackupBusy? = null, val message: BackupMessage? = null)

interface BackupActions {
    fun back()
    fun export()
    fun pickRestore()
}

/** Export und Sicherung (F10; no artboard, linked from the Einstellungen row). */
@Composable
fun BackupScreen(nav: AppNavigator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bike by rememberActiveBike()
    val rides by remember(bike?.id) { bike?.id?.let { RideRepository.summaries(ctx, it) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    var busy by remember { mutableStateOf<BackupBusy?>(null) }
    var message by remember { mutableStateOf<BackupMessage?>(null) }
    var staged by remember { mutableStateOf<StagedBackup?>(null) }
    // re-counted whenever the ride list changes (also after a restore)
    val current = remember(rides, busy) { BackupManager.current(ctx) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = BackupBusy.EXPORTING
            message = try { BackupManager.export(ctx, uri); BackupMessage.Exported } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Repo.log("Backup failed: $e"); BackupMessage.ExportFailed
            }
            busy = null
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = BackupBusy.CHECKING; message = null
            try { staged = BackupManager.stage(ctx, uri) } catch (e: CancellationException) { throw e } catch (e: Backup.Invalid) {
                Repo.log("Backup rejected: ${e.problem} ${e.message}"); message = BackupMessage.Invalid(e.problem)
            } catch (e: Exception) {
                Repo.log("Backup check failed: $e"); message = BackupMessage.Invalid(Backup.Problem.CORRUPT)
            }
            busy = null
        }
    }
    BackupContent(
        BackupUiState(current, busy, message),
        object : BackupActions {
            override fun back() = nav.back()
            override fun export() { message = null; create.launch(BackupManager.fileName()) }
            override fun pickRestore() {
                message = if (Repo.recording.value) BackupMessage.Recording else null
                if (message == null) open.launch(arrayOf(Backup.MIME, "application/octet-stream", "application/x-zip-compressed"))
            }
        },
    )
    staged?.let { s ->
        ConfirmDialog(
            stringResource(R.string.backup_confirm_title), confirmBody(s, current), stringResource(R.string.backup_confirm),
            onConfirm = {
                staged = null
                scope.launch {
                    if (Repo.recording.value) { BackupManager.discard(ctx); message = BackupMessage.Recording; return@launch }
                    busy = BackupBusy.RESTORING
                    message = try { BackupManager.commit(ctx, s); BackupMessage.Restored } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Repo.log("Restore failed: $e"); BackupMessage.RestoreFailed
                    }
                    busy = null
                }
            },
            onDismiss = { staged = null; BackupManager.discard(ctx) },
        )
    }
}

@Composable
private fun overviewText(o: DataOverview) =
    pluralStringResource(R.plurals.backup_bikes, o.bikes.size, o.bikes.size) to pluralStringResource(R.plurals.backup_rides, o.rides, o.rides)

@Composable
private fun confirmBody(s: StagedBackup, current: DataOverview): String {
    val (bBikes, bRides) = overviewText(s.overview)
    val (cBikes, cRides) = overviewText(current)
    val created = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(currentLocale())
        .format(Instant.ofEpochMilli(s.createdMs).atZone(ZoneId.systemDefault()))
    return stringResource(R.string.backup_confirm_body, created, bBikes, s.overview.bikes.joinToString(), bRides, cBikes, cRides)
}

/** Stateless backup screen (screenshot tests). */
@Composable
fun BackupContent(state: BackupUiState, actions: BackupActions) {
    val c = AppTheme.colors
    Screen(header = { BackHeader(stringResource(R.string.backup_title), actions::back) }, bodyTop = 4.dp) {
        Text(stringResource(R.string.backup_intro), Modifier.padding(horizontal = 4.dp), style = AppTheme.type.bodySmall, color = c.textMuted)
        val (bikes, rides) = overviewText(state.current)
        AppCard(contentPadding = PaddingValues(16.dp), gap = 12.dp) {
            CardTitle(stringResource(R.string.backup_create_title), description = stringResource(R.string.backup_on_phone, bikes, rides))
            AppButton(
                stringResource(if (state.busy == BackupBusy.EXPORTING) R.string.backup_creating else R.string.backup_create),
                actions::export, enabled = state.busy == null, icon = AppIcons.Share,
            )
        }
        AppCard(contentPadding = PaddingValues(16.dp), gap = 12.dp) {
            CardTitle(stringResource(R.string.backup_restore_title), description = stringResource(R.string.backup_restore_body))
            AppButton(
                stringResource(
                    when (state.busy) {
                        BackupBusy.CHECKING -> R.string.backup_checking
                        BackupBusy.RESTORING -> R.string.backup_restoring
                        else -> R.string.backup_restore
                    },
                ),
                actions::pickRestore, kind = ButtonKind.OUTLINED, enabled = state.busy == null,
            )
        }
        state.message?.let { m ->
            val (text, tone) = when (m) {
                BackupMessage.Exported -> R.string.backup_created to Tone.POSITIVE
                BackupMessage.ExportFailed -> R.string.backup_create_failed to Tone.DANGER
                BackupMessage.Restored -> R.string.backup_restored to Tone.POSITIVE
                BackupMessage.RestoreFailed -> R.string.backup_restore_failed to Tone.DANGER
                BackupMessage.Recording -> R.string.backup_recording to Tone.WARNING
                is BackupMessage.Invalid -> when (m.problem) {
                    Backup.Problem.FOREIGN -> R.string.backup_problem_foreign
                    Backup.Problem.CORRUPT -> R.string.backup_problem_corrupt
                    Backup.Problem.NEWER -> R.string.backup_problem_newer
                    Backup.Problem.TOO_LARGE -> R.string.backup_problem_too_large
                } to Tone.DANGER
            }
            StatusBanner(stringResource(text), tone = tone, icon = if (tone == Tone.POSITIVE) AppIcons.Check else AppIcons.Warning)
        }
        AppCard(contentPadding = PaddingValues(16.dp)) {
            CardTitle(stringResource(R.string.backup_pairing_title), description = stringResource(R.string.backup_pairing_body))
        }
    }
}
