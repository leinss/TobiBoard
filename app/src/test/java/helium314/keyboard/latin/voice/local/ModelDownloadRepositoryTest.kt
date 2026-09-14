// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The failure reason used to be erased on the next process start: the state map is in-memory, and
 * `rehydrate` runs once at app start, so a download that died while the user was elsewhere came back
 * as `NotDownloaded` with no record of why. `recordFailure` stores the reason so `rehydrate` can
 * restore it.
 */
@RunWith(RobolectricTestRunner::class)
class ModelDownloadRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun aRecordedFailureSurvivesRehydrate() {
        val modelId = SttModelInfo.ParakeetTdt06b.id
        ModelDownloadRepository.recordFailure(context, modelId, "No internet connection")
        // Wipe the in-memory map the way a process restart would.
        ModelDownloadRepository.update(modelId, DownloadState.NotDownloaded)
        ModelDownloadRepository.rehydrate(context)
        assertEquals(
            DownloadState.Failed("No internet connection"),
            ModelDownloadRepository.stateFor(modelId),
        )
        ModelDownloadRepository.clearFailure(context, modelId)
    }

    @Test
    fun clearingTheFailureReturnsTheModelToNotDownloaded() {
        val modelId = TextFixModelInfo.Qwen25_05bInstruct.id
        ModelDownloadRepository.recordFailure(context, modelId, "Unexpected download error")
        ModelDownloadRepository.clearFailure(context, modelId)
        ModelDownloadRepository.rehydrate(context)
        assertEquals(DownloadState.NotDownloaded, ModelDownloadRepository.stateFor(modelId))
    }

    @Test
    fun rehydrateStillReportsAModelThatWasNeverDownloaded() {
        val modelId = TextFixModelInfo.Gemma3_1bInt4.id
        ModelDownloadRepository.update(modelId, DownloadState.Queued)
        ModelDownloadRepository.rehydrate(context)
        assertEquals(DownloadState.NotDownloaded, ModelDownloadRepository.stateFor(modelId))
    }
}
