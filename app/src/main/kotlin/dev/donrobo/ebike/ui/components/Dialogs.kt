package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Material 3 [OutlinedTextField], single line, radius 16 (`shapes.medium`). [label] floats in the outline,
 * [placeholder] shows while empty, [suffix] is a unit after the text ("km", "Wh"), [message] the supporting text
 * below (in the error colour with [error]).
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    suffix: String? = null,
    number: Boolean = false,
    error: Boolean = false,
    message: String? = null,
) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(),
        singleLine = true,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        isError = error,
        supportingText = message?.let { { Text(it) } },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
        else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
    )
}

/**
 * Material 3 [AlertDialog]: title, scrolling [content] (gap 14), [confirm] and [dismiss] as text buttons at
 * the bottom end. Secondary actions (e.g. "Entfernen") belong into [content] as buttons of their own.
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = confirm,
        dismissButton = dismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) },
    )
}

/** Dialog text button ([AppDialog] confirm/dismiss); [danger] for destructive confirmations. */
@Composable
fun DialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) =
    AppButton(text, onClick, kind = if (danger) ButtonKind.DANGER_TEXT else ButtonKind.TEXT, size = ButtonSize.SMALL, enabled = enabled, fillWidth = false)

/** "Abbrechen" as the dialog's dismiss button. */
@Composable
fun CancelButton(onDismiss: () -> Unit) = DialogButton(stringResource(R.string.common_cancel), onDismiss)

/** Single-line text input in a dialog (bike name, route name, electricity price). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
    number: Boolean = false,
    suffix: String? = null,
    label: String? = null,
    saveLabel: String = stringResource(R.string.common_save),
    valid: (String) -> Boolean = { true },
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AppDialog(
        title, onDismiss,
        confirm = { DialogButton(saveLabel, { onSave(text) }, enabled = valid(text)) },
        dismiss = { CancelButton(onDismiss) },
    ) { AppTextField(text, { text = it }, label = label, placeholder = placeholder, number = number, suffix = suffix) }
}

/** Confirmation of a destructive action (delete a ride, remove a bike, replace all data). */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title, onDismiss,
        confirm = { DialogButton(confirm, onConfirm, danger = true) },
        dismiss = { CancelButton(onDismiss) },
    ) { Text(body, style = AppTheme.type.body, color = AppTheme.colors.textMuted) }
}

/** Two fields or buttons side by side, gap 8. */
@Composable
fun DialogButtonRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
