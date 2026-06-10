// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.TextInputDialog

@Composable
fun ClipboardManagementScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { ClipboardDao.getInstance(context) }

    // revision drives recomposition after any mutation
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(revision) {
        dao?.let { d -> (0 until d.count()).map { d.getAt(it) } } ?: emptyList()
    }

    var editingAnnotationFor by remember { mutableStateOf<ClipboardHistoryEntry?>(null) }

    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.clipboard_management)) },
        filteredItems = { query ->
            if (query.isBlank()) entries
            else entries.filter { entry ->
                entry.text.contains(query, ignoreCase = true) ||
                    entry.annotation?.contains(query, ignoreCase = true) == true
            }
        },
        itemContent = { entry ->
            ClipboardEntryItem(
                entry = entry,
                onPin = {
                    dao?.togglePinned(entry.id)
                    revision++
                },
                onAnnotate = { editingAnnotationFor = entry },
                onDelete = {
                    dao?.deleteById(entry.id)
                    revision++
                }
            )
            HorizontalDivider()
        },
    )

    if (editingAnnotationFor != null) {
        val editEntry = editingAnnotationFor!!
        TextInputDialog(
            title = { Text(stringResource(R.string.clipboard_annotation_dialog_title)) },
            initialText = editEntry.annotation ?: "",
            textInputLabel = { Text(stringResource(R.string.clipboard_annotation_hint)) },
            singleLine = true,
            checkTextValid = { true },
            neutralButtonText = if (editEntry.annotation != null) stringResource(android.R.string.cancel) else null,
            onNeutral = {
                dao?.setAnnotation(editEntry.id, null)
                revision++
            },
            onConfirmed = { annotation ->
                dao?.setAnnotation(editEntry.id, annotation.ifBlank { null })
                revision++
                editingAnnotationFor = null
            },
            onDismissRequest = { editingAnnotationFor = null },
        )
    }
}

@Composable
private fun ClipboardEntryItem(
    entry: ClipboardHistoryEntry,
    onPin: () -> Unit,
    onAnnotate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = entry.text.take(200),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.annotation.isNullOrBlank()) {
                Text(
                    text = entry.annotation!!,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val relTime = DateUtils.getRelativeTimeSpanString(
                    entry.timeStamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
                Text(
                    text = relTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.useCount > 0) {
                    Text(
                        text = stringResource(R.string.clipboard_used_count, entry.useCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row {
            IconButton(onClick = onPin) {
                Icon(
                    painter = painterResource(R.drawable.ic_clipboard_pin_rounded),
                    contentDescription = if (entry.isPinned)
                        stringResource(R.string.clipboard_context_unpin)
                    else
                        stringResource(R.string.clipboard_context_pin),
                    tint = if (entry.isPinned)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAnnotate) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.clipboard_annotation_dialog_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_bin),
                    contentDescription = stringResource(R.string.clipboard_context_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
