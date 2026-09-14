// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.voice.local.ModelDownloadService

/**
 * Returns a function that starts a model download, asking for `POST_NOTIFICATIONS` first.
 *
 * The manifest has claimed since the feature landed that the permission "is requested at runtime
 * when the user first taps Download", and nothing did. On Android 13 and later that meant the
 * foreground-service notification carrying progress, the cancel action and (since this package) the
 * failure message was never shown, so a several-hundred-megabyte download ran invisibly.
 *
 * The download starts whether or not the permission is granted: it is what makes the download
 * observable, not what makes it work.
 */
@Composable
fun rememberModelDownloadStarter(): (String) -> Unit {
    val ctx = LocalContext.current
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingModelId?.let { ModelDownloadService.start(ctx, it) }
        pendingModelId = null
    }
    return { modelId ->
        val needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        if (needsRequest) {
            pendingModelId = modelId
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ModelDownloadService.start(ctx, modelId)
        }
    }
}
