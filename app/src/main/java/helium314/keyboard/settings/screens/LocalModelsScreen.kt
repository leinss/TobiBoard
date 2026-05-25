// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.local.DownloadState
import helium314.keyboard.latin.voice.local.HfAuth
import helium314.keyboard.latin.voice.local.LocalSherpaEngine
import helium314.keyboard.latin.voice.local.ModelDownloadRepository
import helium314.keyboard.latin.voice.local.ModelDownloadService
import helium314.keyboard.latin.voice.local.ModelInfo
import helium314.keyboard.latin.voice.local.ModelRegistry
import helium314.keyboard.latin.voice.local.ModelStorage
import helium314.keyboard.latin.voice.local.SttModelInfo
import helium314.keyboard.settings.SearchSettingsScreen

@Composable
fun LocalModelsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val states by ModelDownloadRepository.states.collectAsState()
    val freeBytes = remember(states) { ModelStorage.availableBytes(ctx) }
    var pendingLicenseModel by remember { mutableStateOf<ModelInfo?>(null) }
    var tokenDialogOpen by remember { mutableStateOf(false) }
    // Track when a model is waiting on the token dialog so we can advance to the next
    // gating step (license, then service start) once the user saves.
    var pendingAfterTokenModel by remember { mutableStateOf<ModelInfo?>(null) }
    var hasToken by remember { mutableStateOf(HfAuth.currentToken(ctx) != null) }

    fun startOrGate(model: ModelInfo) {
        when {
            model.requiresAuth && !hasToken -> {
                pendingAfterTokenModel = model
                tokenDialogOpen = true
            }
            model.requiresLicense -> pendingLicenseModel = model
            else -> ModelDownloadService.start(ctx, model.id)
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.local_models_title),
        settings = emptyList(),
    ) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item {
                HfTokenRow(
                    hasToken = hasToken,
                    onTap = { tokenDialogOpen = true },
                )
                Spacer(Modifier.height(12.dp))
            }
            items(ModelRegistry.ALL, key = { it.id }) { model ->
                val state = states[model.id] ?: DownloadState.NotDownloaded
                ModelCard(
                    model = model,
                    state = state,
                    freeBytes = freeBytes,
                    onDownload = { startOrGate(model) },
                    onCancel = { ModelDownloadService.cancel(ctx, model.id) },
                    onDelete = {
                        if (model is SttModelInfo.ParakeetTdt06b) LocalSherpaEngine.releaseShared()
                        ModelStorage.delete(ctx, model)
                        ModelDownloadRepository.update(model.id, DownloadState.NotDownloaded)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (tokenDialogOpen) {
        HfTokenDialog(
            initialValue = "",
            onDismiss = {
                tokenDialogOpen = false
                pendingAfterTokenModel = null
            },
            onSave = { value ->
                HfAuth.setToken(ctx, value)
                hasToken = HfAuth.currentToken(ctx) != null
                tokenDialogOpen = false
                pendingAfterTokenModel?.let { model ->
                    pendingAfterTokenModel = null
                    if (model.requiresLicense) pendingLicenseModel = model
                    else ModelDownloadService.start(ctx, model.id)
                }
            },
            onClear = {
                HfAuth.clear(ctx)
                hasToken = false
                tokenDialogOpen = false
                pendingAfterTokenModel = null
            },
        )
    }

    pendingLicenseModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingLicenseModel = null },
            title = { Text(stringResource(R.string.local_model_license_title, model.displayName)) },
            text = { Text(model.licenseSummary.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLicenseModel = null
                    ModelDownloadService.start(ctx, model.id)
                }) { Text(stringResource(R.string.local_model_license_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLicenseModel = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HfTokenRow(hasToken: Boolean, onTap: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.local_model_hf_token_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (hasToken) R.string.local_model_hf_token_status_set
                    else R.string.local_model_hf_token_status_unset
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTap) {
                Text(stringResource(R.string.local_model_hf_token_dialog_title))
            }
        }
    }
}

@Composable
private fun HfTokenDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_model_hf_token_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.local_model_hf_token_dialog_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.local_model_hf_token_field_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.local_model_hf_token_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.local_model_hf_token_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    state: DownloadState,
    freeBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.local_model_size, formatBytes(model.totalBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            StateRow(state)
            if (model.totalBytes > 0 && state is DownloadState.NotDownloaded && freeBytes in 1..<((model.totalBytes * 12) / 10)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.local_model_low_space),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    DownloadState.NotDownloaded, is DownloadState.Failed, DownloadState.Cancelled ->
                        OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.local_model_download)) }
                    DownloadState.Queued, is DownloadState.Downloading, is DownloadState.Verifying ->
                        OutlinedButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    DownloadState.Ready ->
                        OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.local_model_delete)) }
                }
            }
        }
    }
}

@Composable
private fun StateRow(state: DownloadState) {
    when (state) {
        DownloadState.NotDownloaded ->
            Text(stringResource(R.string.local_model_state_not_downloaded))
        DownloadState.Queued ->
            Text(stringResource(R.string.local_model_state_queued))
        is DownloadState.Downloading -> {
            val pct = if (state.bytesTotal > 0)
                ((state.bytesDownloaded * 100) / state.bytesTotal).toInt().coerceIn(0, 100)
            else 0
            Text(
                stringResource(
                    R.string.local_model_state_downloading,
                    state.fileIndex + 1, state.fileCount, state.currentFile, pct,
                )
            )
            Spacer(Modifier.height(4.dp))
            if (state.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.bytesDownloaded.toFloat() / state.bytesTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is DownloadState.Verifying ->
            Text(stringResource(R.string.local_model_state_verifying, state.currentFile))
        DownloadState.Ready ->
            Text(
                stringResource(R.string.local_model_state_ready),
                color = MaterialTheme.colorScheme.primary,
            )
        DownloadState.Cancelled ->
            Text(stringResource(R.string.local_model_state_cancelled))
        is DownloadState.Failed ->
            Text(
                stringResource(R.string.local_model_state_failed, state.reason),
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> stringResource(R.string.local_model_size_unknown)
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
