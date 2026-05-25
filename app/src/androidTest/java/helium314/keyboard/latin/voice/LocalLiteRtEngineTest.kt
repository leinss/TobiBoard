// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import helium314.keyboard.latin.voice.local.LocalLiteRtEngine
import helium314.keyboard.latin.voice.local.ModelStorage
import helium314.keyboard.latin.voice.local.TextFixModelInfo
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that MediaPipe LLM Inference + Gemma 3 1B IT (INT4) produces a coherent
 * reply for a short fix-text prompt.
 *
 * Pre-flight: the Gemma `.task` bundle must be on disk under
 * `noBackupFilesDir/models/gemma3-1b-it-int4/gemma3-1b-it-int4.task`. Trigger via the
 * On-device models settings screen before running this test (HF Gemma3-1B-IT is auto-gated;
 * accept the license on huggingface.co, paste the token, then Download). The test fails
 * (does not skip) when the model is absent — silent skips would hide regressions.
 */
@RunWith(AndroidJUnit4::class)
class LocalLiteRtEngineTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun respondsToShortFixPrompt() {
        if (!ModelStorage.isReady(ctx, TextFixModelInfo.Gemma3_1bInt4)) {
            fail("Gemma model not on disk. Open Settings → On-device models → Download Gemma and re-run.")
        }

        val systemPrompt = "Rewrite the following text in clear, correct English. " +
                "Reply with only the corrected sentence, no preamble."
        val userText = "the qik brwn fox jmps ovr the lzy dog"
        val engine = LocalLiteRtEngine(ctx, systemPrompt)

        val started = System.currentTimeMillis()
        val out = engine.fixText(userText).trim()
        val elapsed = System.currentTimeMillis() - started

        Log.i(TAG, "input=\"$userText\"  output=\"$out\"  elapsed=${elapsed}ms")
        // 1B INT4 Gemma is noisy; we only assert it produced *something* coherent —
        // non-blank, more than a couple of characters, and at least one expected token.
        assertTrue("empty response — recognizer probably mis-initialised", out.isNotBlank())
        assertTrue("response too short to be meaningful: '$out'", out.length >= 5)
        val lower = out.lowercase()
        assertTrue(
            "expected 'fox' or 'dog' in cleaned output, got: $out",
            lower.contains("fox") || lower.contains("dog"),
        )
    }

    companion object {
        private const val TAG = "LocalLiteRtEngineTest"
    }
}
