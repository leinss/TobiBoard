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

/**
 * The Gemma `.task` bundle applies its own chat template inside MediaPipe at runtime — passing
 * a pre-wrapped `<start_of_turn>user…<end_of_turn>` string double-templates and (on ARM64
 * Gemma 3 1B INT4) makes the model emit only the EOS token, returning 0 chars. So we pass
 * the system prompt + user text as plain text and let MediaPipe handle the templating.
 *
 * Gemma 3 1B INT4 is a 1B-parameter model and routinely ignores "no commentary" instructions
 * that appear once near the top of the prompt — it likes to append a chatty "I've corrected
 * the errors and improved grammar…" summary after the corrected text. Wrapping the input with
 * an Input/Output block and repeating the strict "reply with only…" rule immediately before
 * generation suppresses that behavior far more reliably (small models weight the trailing
 * tokens of the prompt highest).
 */
private fun formatGemmaChat(systemPrompt: String, userText: String): String = buildString {
    val sp = systemPrompt.trim()
    if (sp.isNotEmpty()) {
        append(sp)
        append("\n\n")
    }
    append("Input:\n")
    append(userText)
    append("\n\nReply with ONLY the result. No preamble, no quotes, no explanation, no summary, no commentary. Output nothing else.\nOutput:\n")
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
