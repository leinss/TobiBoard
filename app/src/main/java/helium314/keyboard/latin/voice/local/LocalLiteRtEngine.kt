// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.voice.TextFixEngine
import java.io.IOException

/**
 * On-device text-fix backed by MediaPipe LLM Inference + Gemma 3 1B IT (INT4 `.task` bundle).
 * The [LlmInference] handle is built lazily and shared across requests — `createFromOptions`
 * allocates ~1 GB of native memory and takes seconds, unacceptable per-fix.
 *
 * `generateResponse` is synchronous and cannot be interrupted; [cancel] short-circuits before
 * the call but a late cancel still waits for the in-flight generation to finish.
 */
internal class LocalLiteRtEngine(
    private val context: Context,
    private val systemPrompt: String,
) : TextFixEngine {

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun fixText(userText: String): String {
        if (cancelled) return ""
        val inference = SharedLlm.acquire(context)
            ?: throw IOException("Gemma model not downloaded — open Settings → On-device models.")
        val prompt = formatGemmaChat(systemPrompt, userText)
        if (cancelled) return ""
        val started = System.currentTimeMillis()
        val out = inference.generateResponse(prompt)
        Log.i(TAG, "generated ${out.length} chars in ${System.currentTimeMillis() - started} ms")
        return out
    }

    companion object {
        private const val TAG = "LocalLiteRtEngine"
        fun releaseShared() = SharedLlm.release()
        fun warmUp(context: Context) {
            Thread { SharedLlm.acquire(context) }.start()
        }
    }
}

/** Gemma-IT single-turn chat template: system + user folded into one user turn. */
private fun formatGemmaChat(systemPrompt: String, userText: String): String =
    buildString {
        append("<start_of_turn>user\n")
        if (systemPrompt.isNotBlank()) {
            append(systemPrompt.trim())
            append("\n\n")
        }
        append(userText)
        append("<end_of_turn>\n<start_of_turn>model\n")
    }

private object SharedLlm {
    private const val TAG = "LocalLiteRtEngine"
    private val model = TextFixModelInfo.Gemma3_1bInt4

    @Volatile private var inference: LlmInference? = null
    private val initLock = Any()

    fun acquire(context: Context): LlmInference? {
        inference?.let { return it }
        synchronized(initLock) {
            inference?.let { return it }
            if (!ModelStorage.isReady(context, model)) return null
            val modelFile = ModelStorage.fileFor(context, model, model.files.first())
            return try {
                val started = System.currentTimeMillis()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                val llm = LlmInference.createFromOptions(context, options)
                Log.i(TAG, "Initialised LlmInference in ${System.currentTimeMillis() - started} ms")
                inference = llm
                llm
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialise LlmInference", t)
                null
            }
        }
    }

    fun release() {
        synchronized(initLock) {
            inference?.close()
            inference = null
        }
    }

    // TextFixManager caps input at 10k chars (~3k tokens); 4k tokens leaves headroom both ways.
    private const val MAX_TOKENS = 4096
}
