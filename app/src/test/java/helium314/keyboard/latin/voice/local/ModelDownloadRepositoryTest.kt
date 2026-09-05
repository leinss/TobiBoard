// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `rehydrate` used to overwrite `Failed(reason)` with `NotDownloaded`, so the only record of why a
 * several-hundred-megabyte download stopped was erased on the next process start.
 */
@RunWith(RobolectricTestRunner::class)
class ModelDownloadRepositoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun rehydrateKeepsAFailureReason() {
        val modelId = SttModelInfo.ParakeetTdt06b.id
        ModelDownloadRepository.update(modelId, DownloadState.Failed("No internet connection"))
        ModelDownloadRepository.rehydrate(context)
        assertEquals(
            DownloadState.Failed("No internet connection"),
            ModelDownloadRepository.stateFor(modelId),
        )
    }

    @Test
    fun rehydrateStillReportsAModelThatWasNeverDownloaded() {
        val modelId = TextFixModelInfo.Qwen25_05bInstruct.id
        ModelDownloadRepository.update(modelId, DownloadState.Queued)
        ModelDownloadRepository.rehydrate(context)
        assertEquals(DownloadState.NotDownloaded, ModelDownloadRepository.stateFor(modelId))
    }
}
