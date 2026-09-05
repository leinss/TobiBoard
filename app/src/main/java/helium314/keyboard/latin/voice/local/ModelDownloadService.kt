// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.voice.isNetworkAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns long-running model downloads, so they survive the IME
 * process being dismissed. The UI starts downloads via [start] and cancels via
 * [cancel]; progress is observed through [ModelDownloadRepository.states].
 *
 * Downloads run serially, one at a time, through [SerialDownloadQueue]: a second [start]
 * request while one is in flight shows as [DownloadState.Queued] until the running one
 * finishes. Cancelling a queued download works the same as cancelling a running one.
 */
internal class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One download at a time. The class comment promised this from the start, but every start()
    // launched straight into the shared scope, so two models fought over bandwidth and disk.
    private val queue = SerialDownloadQueue(
        scope = scope,
        onState = { modelId, state -> ModelDownloadRepository.update(modelId, state) },
        onIdle = { stopForegroundAndService() },
    )
    private val downloader = ModelDownloader()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                if (modelId != null) startDownload(modelId)
            }
            ACTION_CANCEL -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                if (modelId != null) cancelDownload(modelId)
                else Log.w(TAG, "ACTION_CANCEL received without $EXTRA_MODEL_ID — stale PendingIntent?")
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(modelId: String) {
        // start() launches us via startForegroundService() on API 26+, which obligates a prompt
        // startForeground() call on EVERY path — including the early returns below — or the system
        // kills the process with ForegroundServiceDidNotStartInTimeException. Promote up front and
        // tear down again if we bail without actually starting a download.
        val model = ModelRegistry.findById(modelId)
        promoteToForeground(modelId, model?.displayName ?: modelId, percent = 0)

        if (model == null) {
            Log.w(TAG, "startDownload: unknown model $modelId")
            stopForegroundIfIdle()
            return
        }
        if (queue.isPending(modelId)) return // already downloading: that job keeps us foreground
        // A new attempt supersedes whatever the last one failed with.
        ModelDownloadRepository.clearFailure(applicationContext, modelId)
        if (ModelStorage.isReady(this, model)) {
            ModelDownloadRepository.update(modelId, DownloadState.Ready)
            stopForegroundIfIdle()
            return
        }

        // Without this the first thing the user sees is a raw UnknownHostException in the
        // Failed reason, which reads as a bug in the app rather than "you are offline".
        if (!isNetworkAvailable(this)) {
            failDownload(modelId, model.displayName, getString(R.string.voice_error_no_network))
            stopForegroundIfIdle()
            return
        }

        val authToken = if (model.requiresAuth) HfAuth.currentToken(this) else null
        if (model.requiresAuth && authToken == null) {
            failDownload(modelId, model.displayName, getString(R.string.local_model_auth_required))
            stopForegroundIfIdle()
            return
        }

        queue.enqueue(
            modelId = modelId,
            onFailure = { e ->
                // A genuine failure (network, disk, verification) — surface it as Failed with a
                // reason so the UI shows the error + retry, instead of masquerading as Cancelled.
                Log.e(TAG, "download failed for $modelId", e)
                failDownload(
                    modelId,
                    model.displayName,
                    downloadFailureReason(e, getString(R.string.voice_error_no_network), getString(R.string.local_model_download_error_generic)),
                )
            },
        ) {
            val targetDir = ModelStorage.dirFor(applicationContext, model)
            downloader.download(targetDir, model, authToken) { state ->
                ModelDownloadRepository.update(modelId, state)
                updateNotification(modelId, model.displayName, state)
            }
        }
    }

    /**
     * Record the failure and tell the user about it. A model download outlives the settings screen,
     * so without a notification a failure that happens after the user navigates away is invisible
     * until they come back and wonder why nothing downloaded.
     */
    private fun failDownload(modelId: String, displayName: String, reason: String) {
        ModelDownloadRepository.recordFailure(applicationContext, modelId, reason)
        postFailureNotification(modelId, displayName, reason)
    }

    private fun postFailureNotification(modelId: String, displayName: String, reason: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_model_download_failed_title, displayName))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        // A distinct id: stopForeground(STOP_FOREGROUND_REMOVE) clears NOTIFICATION_ID, which would
        // otherwise take the failure message with it a moment after it appeared.
        NotificationManagerCompat(this).notify(NOTIFICATION_ID_FAILED + modelId.hashCode(), notification)
    }

    /** Tear down the foreground notification + service if no download is currently running. */
    private fun stopForegroundIfIdle() {
        if (queue.isIdle) stopForegroundAndService()
    }

    private fun cancelDownload(modelId: String) {
        queue.cancel(modelId)
    }

    private fun promoteToForeground(modelId: String, displayName: String, percent: Int) {
        val notification = buildNotification(modelId, displayName, percent, indeterminate = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(modelId: String, displayName: String, state: DownloadState) {
        val notification = when (state) {
            is DownloadState.Downloading -> {
                val pct = if (state.bytesTotal > 0)
                    ((state.bytesDownloaded * 100) / state.bytesTotal).toInt().coerceIn(0, 100)
                else 0
                buildNotification(
                    modelId = modelId,
                    title = displayName,
                    percent = pct,
                    indeterminate = state.bytesTotal <= 0,
                    bodyOverride = "${state.currentFile} · ${pct}%",
                )
            }
            is DownloadState.Verifying -> buildNotification(modelId, displayName, 100, indeterminate = true, bodyOverride = getString(R.string.local_model_verifying))
            DownloadState.Ready -> null
            DownloadState.Cancelled -> null
            is DownloadState.Failed -> null
            else -> buildNotification(modelId, displayName, 0, indeterminate = true)
        }
        if (notification != null) {
            NotificationManagerCompat(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        modelId: String,
        title: String,
        percent: Int,
        indeterminate: Boolean,
        bodyOverride: String? = null,
    ): Notification {
        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_MODEL_ID, modelId)
        }
        val cancelPi = PendingIntent.getService(
            this, modelId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_model_download_title, title))
            .setContentText(bodyOverride ?: getString(R.string.local_model_download_in_progress))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(0, getString(android.R.string.cancel), cancelPi)
            .build()
    }

    private fun stopForegroundAndService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "tobiboard.local-model-downloads"
        private const val NOTIFICATION_ID = 4242
        private const val NOTIFICATION_ID_FAILED = 4300

        /**
         * The sentence shown for a failed download. A [java.net.UnknownHostException] carries only
         * the hostname it could not resolve, so surfacing `e.message` gave the user
         * "huggingface.co" and nothing else. Pure, so it is unit-tested.
         */
        internal fun downloadFailureReason(e: Throwable, offlineMessage: String, genericMessage: String): String = when (e) {
            is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
                offlineMessage
            else -> e.message?.takeUnless { it.isBlank() } ?: genericMessage
        }
        private const val ACTION_START = "helium314.keyboard.action.MODEL_DOWNLOAD_START"
        private const val ACTION_CANCEL = "helium314.keyboard.action.MODEL_DOWNLOAD_CANCEL"
        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            // startForegroundService requires API 26; on older devices a plain startService
            // followed by the service's own startForeground (see promoteToForeground) is correct.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(intent)
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val name = context.getString(R.string.local_model_download_channel_name)
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.local_model_download_channel_description)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }
}

/**
 * Tiny shim — NotificationManagerCompat from androidx is the standard call but we
 * avoid adding the dependency here; the platform NotificationManager is sufficient for
 * a single foreground-service notification.
 */
private class NotificationManagerCompat(private val context: Context) {
    fun notify(id: Int, notification: Notification) {
        // String overload is API 1; the Class<T> overload requires API 23 (minSdk is 21).
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(id, notification)
    }
}
