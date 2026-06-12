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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns long-running model downloads, so they survive the IME
 * process being dismissed. The UI starts downloads via [start] and cancels via
 * [cancel]; progress is observed through [ModelDownloadRepository.states].
 *
 * Downloads run serially — a second [start] request while one is in flight is queued.
 * Concurrency would add bandwidth contention without user benefit (only one model is
 * actively useful at a time anyway).
 */
internal class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
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
        val model = ModelRegistry.findById(modelId) ?: run {
            Log.w(TAG, "startDownload: unknown model $modelId")
            return
        }
        if (jobs.containsKey(modelId)) return
        if (ModelStorage.isReady(this, model)) {
            ModelDownloadRepository.update(modelId, DownloadState.Ready)
            return
        }

        val authToken = if (model.requiresAuth) HfAuth.currentToken(this) else null
        if (model.requiresAuth && authToken == null) {
            ModelDownloadRepository.update(
                modelId,
                DownloadState.Failed(getString(R.string.local_model_auth_required)),
            )
            if (jobs.isEmpty()) stopForegroundAndService()
            return
        }

        promoteToForeground(modelId, model.displayName, percent = 0)

        val job = scope.launch {
            try {
                val targetDir = ModelStorage.dirFor(applicationContext, model)
                downloader.download(targetDir, model, authToken) { state ->
                    ModelDownloadRepository.update(modelId, state)
                    updateNotification(modelId, model.displayName, state)
                }
            } catch (e: Exception) {
                ModelDownloadRepository.update(modelId, DownloadState.Cancelled)
            } finally {
                jobs.remove(modelId)
                if (jobs.isEmpty()) stopForegroundAndService()
            }
        }
        jobs[modelId] = job
    }

    private fun cancelDownload(modelId: String) {
        jobs.remove(modelId)?.cancel()
        ModelDownloadRepository.update(modelId, DownloadState.Cancelled)
        if (jobs.isEmpty()) stopForegroundAndService()
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
        private const val ACTION_START = "helium314.keyboard.action.MODEL_DOWNLOAD_START"
        private const val ACTION_CANCEL = "helium314.keyboard.action.MODEL_DOWNLOAD_CANCEL"
        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startForegroundService(intent)
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
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(id, notification)
    }
}
