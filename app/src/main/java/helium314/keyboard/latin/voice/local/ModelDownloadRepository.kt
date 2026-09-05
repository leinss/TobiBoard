// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of the current [DownloadState] for every known model. Written
 * by [ModelDownloadService] as downloads progress; observed by the settings UI.
 *
 * Held as a singleton so the UI can observe state without binding to the service —
 * matches Android's general guidance to prefer broadcasts / repositories over Service
 * binders for one-way state updates.
 */
internal object ModelDownloadRepository {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun stateFor(modelId: String): DownloadState =
        _states.value[modelId] ?: DownloadState.NotDownloaded

    fun update(modelId: String, state: DownloadState) {
        _states.update { it + (modelId to state) }
    }

    /**
     * Reconcile the cached state with on-disk reality for every registered model. Run
     * at app start so the UI doesn't briefly show "Not downloaded" for models that are
     * actually ready.
     *
     * A failure reason recorded by [recordFailure] is restored here. [_states] is in-memory only
     * and this runs once per process, so without the stored reason a download that died while the
     * user was elsewhere came back as [DownloadState.NotDownloaded] on the next launch and the only
     * record of why it stopped was gone.
     */
    fun rehydrate(context: Context) {
        val prefs = context.prefs()
        val snapshot = ModelRegistry.ALL.associate { model ->
            val storedReason = prefs.getString(failureKey(model.id), null)
            model.id to when {
                ModelStorage.isReady(context, model) -> DownloadState.Ready
                !storedReason.isNullOrBlank() -> DownloadState.Failed(storedReason)
                else -> DownloadState.NotDownloaded
            }
        }
        _states.value = snapshot
    }

    /** Stores [reason] so [rehydrate] can restore it after the process is gone. */
    fun recordFailure(context: Context, modelId: String, reason: String) {
        update(modelId, DownloadState.Failed(reason))
        context.prefs().edit { putString(failureKey(modelId), reason) }
    }

    /** Drops any stored failure for [modelId]. Call when a download starts or succeeds. */
    fun clearFailure(context: Context, modelId: String) {
        context.prefs().edit { remove(failureKey(modelId)) }
    }

    private fun failureKey(modelId: String) = "local_model_download_failure_$modelId"
}
