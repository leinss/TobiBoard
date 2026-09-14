// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import helium314.keyboard.latin.voice.local.LocalSherpaEngine
import helium314.keyboard.latin.voice.local.ModelStorage
import helium314.keyboard.latin.voice.local.SttModelInfo
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that sherpa-onnx + Parakeet TDT v3-int8 transcribes a known WAV.
 *
 * Pre-flight: the Parakeet model files must be on disk under
 * `noBackupFilesDir/models/parakeet-tdt-0.6b-v3-int8/`. Trigger via the
 * On-device models settings screen before running this test. Test fails (does
 * not skip) when the model is absent — silent skips here would hide regressions.
 */
@RunWith(AndroidJUnit4::class)
class LocalSherpaEngineTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    @Test fun transcribesEnglishUtterance() {
        if (!ModelStorage.isReady(ctx, SttModelInfo.ParakeetTdt06b)) {
            fail("Parakeet model not on disk. Open Settings → On-device models → Download and re-run this test.")
        }

        val wav = copyAssetToCache("hello_quick_brown_fox.wav")
        val engine = LocalSherpaEngine(ctx)

        val started = System.currentTimeMillis()
        val transcript = engine.transcribe(wav)
        val elapsed = System.currentTimeMillis() - started

        Log.i(TAG, "transcript=\"$transcript\"  elapsed=${elapsed}ms")
        val lower = transcript.lowercase()
        // Parakeet rarely emits an empty result on actual speech; if it does we want to know.
        assertTrue("empty transcript — recognizer probably mis-initialised", transcript.isNotBlank())
        // The reference text is "the quick brown fox jumps over the lazy dog".
        // Asserting two distinctive tokens keeps the test robust against minor
        // capitalisation / punctuation drift in different model revisions.
        assertTrue("expected 'quick' in transcript, got: $transcript", lower.contains("quick"))
        assertTrue("expected 'fox' in transcript, got: $transcript", lower.contains("fox"))
    }

    private fun copyAssetToCache(name: String): File {
        val out = File(ctx.cacheDir, name)
        // Assets are bundled in the test APK, not the app under test.
        testCtx.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    companion object {
        private const val TAG = "LocalSherpaEngineTest"
    }
}
