// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Log
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.local.ModelStorage
import helium314.keyboard.latin.voice.local.TextFixModelInfo
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof that the TextFixManager dispatches to LocalLiteRtEngine when
 * AiProvider.LOCAL is selected: set the pref, fire a fix-text request via the
 * manager (not the engine directly), and assert a non-empty proposed result comes
 * back via the Callbacks contract. Failing this test means the routing in
 * TextFixManager.startTextFix is broken even if the engine works in isolation.
 *
 * Pre-flight: Gemma must be on disk (see [LocalLiteRtEngineTest]).
 */
@RunWith(AndroidJUnit4::class)
class TextFixManagerLocalRoutingTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun startTextFixDispatchesToLocalEngineWhenProviderIsLocal() {
        if (!ModelStorage.isReady(ctx, TextFixModelInfo.Gemma3_1bInt4)) {
            fail("Gemma model not on disk. Open Settings → On-device models → Download Gemma and re-run.")
        }
        // Initialise Settings + flip prefs to LOCAL.
        Settings.init(ctx)
        ctx.prefs().edit(commit = true) {
            putString(Settings.PREF_AI_PROVIDER, AiProvider.LOCAL.prefValue)
            putBoolean(Settings.PREF_TEXT_FIX_ENABLED, true)
            putString(
                Settings.PREF_TEXT_FIX_PROMPT,
                "Rewrite the user's text in clear, correct English. Reply with only the rewrite.",
            )
        }

        val resultLatch = CountDownLatch(1)
        var resultText: String? = null
        var errorMessage: String? = null

        val callbacks = object : TextFixManager.Callbacks {
            override fun getBlockedErrorResId(): Int? = null
            override fun getSelectedText(): CharSequence = INPUT
            override fun onWorking() {}
            override fun onFinished() {}
            override fun onResult(originalText: String, proposedText: String) {
                resultText = proposedText
                resultLatch.countDown()
            }
            override fun onError(message: String) {
                errorMessage = message
                resultLatch.countDown()
            }
        }

        val manager = TextFixManager(ctx, callbacks)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.startTextFix()
            }
            assertTrue(
                "timed out after ${TIMEOUT_SEC}s waiting for fix-text response",
                resultLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS),
            )
            assertNull("manager surfaced error: $errorMessage", errorMessage)
            val out = resultText
            assertTrue("manager returned empty proposed text", !out.isNullOrBlank())
            Log.i(TAG, "input=\"$INPUT\"  proposed=\"$out\"")
        } finally {
            manager.release()
        }
    }

    companion object {
        private const val TAG = "TextFixManagerLocalRoutingTest"
        private const val INPUT = "the qik brwn fox jmps ovr the lzy dog"
        // Cold init for Gemma is 3-5 s; with the actual generation budget we
        // give the request 60 s before declaring failure.
        private const val TIMEOUT_SEC = 60L
    }
}
