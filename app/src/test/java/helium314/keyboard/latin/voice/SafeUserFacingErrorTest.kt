// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.local.LocalModelLoadException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the error-message mapping in [safeUserFacingError]. The on-device engine distinguishes
 * "model not downloaded" (handled by the preflight → Settings navigation) from "files present but
 * the native handle failed to load" ([LocalModelLoadException]); the latter must surface a distinct
 * re-download hint rather than the generic "Text fix failed" fallback.
 */
@RunWith(RobolectricTestRunner::class)
class SafeUserFacingErrorTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun localModelLoadExceptionMapsToTheReDownloadHintNotTheGenericFallback() {
        val message = safeUserFacingError(
            context,
            LocalModelLoadException(RuntimeException("corrupt .task")),
            R.string.text_fix_error_failed,
        )
        assertEquals(context.getString(R.string.text_fix_error_local_load_failed), message)
    }

    @Test
    fun localModelLoadExceptionMapsToTheReDownloadHintOnTheVoiceSttPathToo() {
        // The on-device STT engine (LocalSherpaEngine) now throws LocalModelLoadException when the
        // native recognizer fails to build; with the voice fallback resId it must still surface the
        // actionable re-download hint, not the generic "Transcription failed".
        val message = safeUserFacingError(
            context,
            LocalModelLoadException(RuntimeException("recognizer init failed")),
            R.string.voice_error_transcription_failed,
        )
        assertEquals(context.getString(R.string.text_fix_error_local_load_failed), message)
    }

    @Test
    fun unrecognisedExceptionFallsBackToTheProvidedResId() {
        // A bare IOException (e.g. the not-downloaded race) must NOT be mapped to the load-failed
        // hint — it falls back to the generic resId so the mapping stays narrow.
        val message = safeUserFacingError(context, IOException("boom"), R.string.text_fix_error_failed)
        assertEquals(context.getString(R.string.text_fix_error_failed), message)
    }
}
