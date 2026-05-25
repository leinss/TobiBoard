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
internal class LocalSherpaEngine(private val context: Context) : SttEngine {

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun transcribe(audioFile: File): String {
        if (cancelled) return ""
        val recognizer = SharedRecognizer.acquire(context)
            ?: throw IOException("Parakeet model not downloaded — open Settings → On-device models.")
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
            Thread { SharedRecognizer.acquire(context) }.start()
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
                Log.e(TAG, "Failed to initialise OfflineRecognizer", t)
                null
            }
        }
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
