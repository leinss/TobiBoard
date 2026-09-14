// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Offline, a model download used to fail with the raw platform message, e.g.
 * `Unable to resolve host "huggingface.co"`, which reads as a bug in the app.
 */
class ModelDownloadFailureReasonTest {

    private val offline = "No internet connection"
    private val generic = "Unexpected download error"

    @Test
    fun transportFailuresBecomeTheOfflineMessage() {
        assertEquals(offline, ModelDownloadService.downloadFailureReason(java.net.UnknownHostException("huggingface.co"), offline, generic))
        assertEquals(offline, ModelDownloadService.downloadFailureReason(java.net.SocketTimeoutException(), offline, generic))
        assertEquals(offline, ModelDownloadService.downloadFailureReason(java.net.ConnectException(), offline, generic))
    }

    @Test
    fun otherFailuresKeepTheirOwnMessage() {
        assertEquals("HTTP 500 for https://example/model", ModelDownloadService.downloadFailureReason(java.io.IOException("HTTP 500 for https://example/model"), offline, generic))
    }

    @Test
    fun aMessagelessFailureFallsBackToTheGenericSentence() {
        assertEquals(generic, ModelDownloadService.downloadFailureReason(RuntimeException(), offline, generic))
        assertEquals(generic, ModelDownloadService.downloadFailureReason(RuntimeException("   "), offline, generic))
    }
}
