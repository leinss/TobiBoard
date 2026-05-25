// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
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
     */
    fun rehydrate(context: Context) {
        val snapshot = ModelRegistry.ALL.associate { model ->
            model.id to if (ModelStorage.isReady(context, model)) {
                DownloadState.Ready
            } else {
                DownloadState.NotDownloaded
            }
        }
        _states.value = snapshot
    }
}
