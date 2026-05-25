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
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.local.DownloadState
import helium314.keyboard.latin.voice.local.ModelDownloadRepository
import helium314.keyboard.latin.voice.local.ModelDownloadService
import helium314.keyboard.latin.voice.local.ModelInfo
import helium314.keyboard.latin.voice.local.ModelRegistry
import helium314.keyboard.latin.voice.local.ModelStorage
import helium314.keyboard.settings.SearchSettingsScreen

@Composable
fun LocalModelsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val states by ModelDownloadRepository.states.collectAsState()
    val freeBytes = remember(states) { ModelStorage.availableBytes(ctx) }
    var pendingLicenseModel by remember { mutableStateOf<ModelInfo?>(null) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.local_models_title),
        settings = emptyList(),
    ) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            items(ModelRegistry.ALL, key = { it.id }) { model ->
                val state = states[model.id] ?: DownloadState.NotDownloaded
                ModelCard(
                    model = model,
                    state = state,
                    freeBytes = freeBytes,
                    onDownload = {
                        if (model.requiresLicense && state is DownloadState.NotDownloaded) {
                            pendingLicenseModel = model
                        } else {
                            ModelDownloadService.start(ctx, model.id)
                        }
                    },
                    onCancel = { ModelDownloadService.cancel(ctx, model.id) },
                    onDelete = {
                        ModelStorage.delete(ctx, model)
                        ModelDownloadRepository.update(model.id, DownloadState.NotDownloaded)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
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
