package dev.donrobo.ebike.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.theme.AppDimens
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Explanations (S4): a screen shows at most one short line; anything longer sits behind an ⓘ ([InfoButton]) that
 * opens this Material [ModalBottomSheet] — title, the explanation ([content], usually [InfoParagraph]s) and a
 * "Got it" button. [openFully] skips the half-expanded state (screenshot tests and long texts).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, onDismiss: () -> Unit, openFully: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = openFully)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = AppTheme.type.barTitle)
            content()
            Button(onDismiss, Modifier.fillMaxWidth().heightIn(min = AppDimens.touch).padding(top = 8.dp)) {
                Text(stringResource(R.string.common_got_it))
            }
        }
    }
}

/** One paragraph of an [InfoSheet]. */
@Composable
fun InfoParagraph(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = AppTheme.type.body)

/** A small heading inside an [InfoSheet]. */
@Composable
fun InfoHeading(text: String, modifier: Modifier = Modifier) = Text(text, modifier.padding(top = 4.dp), style = AppTheme.type.cardTitle)

/**
 * The ⓘ icon button (muted, it is secondary to the content) that opens an [InfoSheet]; it keeps the open state itself. [label] = what it explains, for
 * screen readers ("More about the sun curve"); [sheet] = the sheet's title and content.
 */
@Composable
fun InfoButton(label: String, title: String, modifier: Modifier = Modifier, sheet: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton({ open = true }, modifier) { AppIcon(AppIcons.Info, label, tint = AppTheme.colors.textMuted) }
    if (open) InfoSheet(title, { open = false }, content = sheet)
}
