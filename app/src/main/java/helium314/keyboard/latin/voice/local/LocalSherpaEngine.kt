// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.voice.SttEngine
import java.io.File
import java.io.IOException

/**
 * On-device STT backed by sherpa-onnx + Parakeet TDT 0.6 B v3 (INT8). One [OfflineRecognizer]
 * is constructed lazily on the first request and held in [SharedRecognizer] for the rest of
 * the process — cold init takes ~1–2 s, which is unacceptable on every utterance.
 *
 * Cancellation is per-engine instance; it short-circuits before decode but cannot interrupt
 * sherpa-onnx's native decode call, so a late cancel still pays for the in-flight transcription.
 */
internal class LocalSherpaEngine(
    private val context: Context,
    /**
     * Fired on the background thread once the recognizer exists and the decode is about to start.
     * The caller uses it to move out of its "preparing" state; see [LocalLiteRtEngine].
     */
    private val onModelReady: (() -> Unit)? = null,
) : SttEngine {

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun transcribe(audioFile: File): String {
        if (cancelled) return ""
        val recognizer = try {
            SharedRecognizer.acquire(context)
        } catch (e: LocalModelLoadException) {
            // The recognizer wouldn't build. Verify the on-disk model and self-heal a corrupt one
            // (deletes it + forces a fresh download); rethrows either way so we never proceed with a
            // broken model. Slow (hashes the model) but this only runs on an actual failure.
            SharedRecognizer.verifyAndHeal(context, e)
        } ?: throw LocalModelLoadException(
            IOException("Parakeet model files not on disk — open Settings → On-device models.")
        )
        onModelReady?.invoke()
        val wave = WaveReader.readWaveFromFile(audioFile.absolutePath)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            if (cancelled) ""
            else {
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            }
        } finally {
            stream.release()
        }
    }

    companion object {
        /** Tear down the shared recognizer (e.g. after the model is deleted by the user). */
        fun releaseShared() = SharedRecognizer.release()

        /**
         * Force the recognizer to be built ahead of the first transcription request. Safe to
         * call from any thread (spawns its own worker), no-op if the model isn't on disk.
         */
        fun warmUp(context: Context) {
            // acquire() now throws LocalModelLoadException on a native-init failure; swallow it on
            // the pre-warm path (already logged) so a bad model can't crash this fire-and-forget
            // worker. The next real transcribe re-attempts and surfaces the error to the user.
            Thread {
                try {
                    SharedRecognizer.acquire(context)
                } catch (_: Throwable) {
                    // Logged in acquire(); nothing actionable here.
                }
            }.start()
        }
    }
}

private object SharedRecognizer {
    private const val TAG = "LocalSherpaEngine"
    private val model = SttModelInfo.ParakeetTdt06b

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val initLock = Any()

    fun acquire(context: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(initLock) {
            recognizer?.let { return it }
            if (!ModelStorage.isReady(context, model)) return null
            val modelDir = ModelStorage.dirFor(context, model)
            val cfg = buildConfig(modelDir)
            return try {
                val started = System.currentTimeMillis()
                val rec = OfflineRecognizer(null, cfg)
                Log.i(TAG, "Initialised recognizer in ${System.currentTimeMillis() - started} ms")
                recognizer = rec
                rec
            } catch (t: Throwable) {
                // Files are present (isReady passed) but the native handle would not build — e.g. a
                // corrupt model or an incompatible native library. Surface it as a typed load
                // failure so safeUserFacingError shows the re-download hint instead of swallowing
                // the cause. Put the exception type + message ON the message line (not only in the
                // stack) so it survives the in-app log export, which collapses stack traces.
                Log.e(TAG, "Failed to initialise OfflineRecognizer: ${t.javaClass.name}: ${t.message}", t)
                throw LocalModelLoadException(t)
            }
        }
    }

    /**
     * Called after [acquire] failed to build the recognizer. Hashes the on-disk model against its
     * pinned sizes + SHA-256: if any file is corrupt/truncated the model is deleted and marked
     * NotDownloaded so the UI forces a fresh download (self-heal) — this recovers users whose model
     * predates SHA pinning or whose download was incomplete. If every file is intact the failure is
     * a genuine model/runtime incompatibility that re-downloading can't fix; the files are kept for
     * inspection. Always throws — the caller can't get a recognizer either way.
     */
    fun verifyAndHeal(context: Context, cause: LocalModelLoadException): Nothing {
        val corrupt = ModelStorage.findCorruptFiles(context, model)
        if (corrupt.isNotEmpty()) {
            Log.e(TAG, "On-device model corrupt (bad size/SHA): $corrupt — invalidating; re-download required")
            release()
            ModelStorage.delete(context, model)
            ModelDownloadRepository.update(model.id, DownloadState.NotDownloaded)
            throw LocalModelLoadException(java.io.IOException("Model corrupt: $corrupt (invalidated for re-download)"))
        }
        Log.e(TAG, "On-device model bytes verified intact but recognizer init failed — re-download will NOT help; likely a model/runtime incompatibility")
        throw cause
    }

    fun release() {
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun buildConfig(modelDir: File): OfflineRecognizerConfig {
        val transducer = OfflineTransducerModelConfig(
            File(modelDir, "encoder.int8.onnx").absolutePath,
            File(modelDir, "decoder.int8.onnx").absolutePath,
            File(modelDir, "joiner.int8.onnx").absolutePath,
        )
        val modelConfig = OfflineModelConfig().apply {
            this.transducer = transducer
            this.tokens = File(modelDir, "tokens.txt").absolutePath
            this.modelType = "nemo_transducer"
            this.numThreads = 2
        }
        return OfflineRecognizerConfig(
            FeatureConfig(16_000, 80, 0.0f),
            modelConfig,
            HomophoneReplacerConfig("", "", ""),
            "greedy_search",
            4,
            "",
            1.5f,
            "",
            "",
            0.0f,
        )
    }
}
