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
        val raw = inference.generateResponse(prompt)
        val cleaned = stripTrailingCommentary(raw)
        Log.i(
            TAG,
            "generated ${raw.length} chars in ${System.currentTimeMillis() - started} ms (after commentary strip: ${cleaned.length})"
        )
        return cleaned
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
 * Prompt envelope history (Gemma 3 1B INT4 is finicky):
 *  - Plain `{system}\n\n{user}` → produced the corrected text *plus* a chatty meta-summary
 *    ("I've corrected the errors and improved grammar…") that leaked into the replacement.
 *  - Strict envelope with "Reply with ONLY…" and `Input:`/`Output:` markers → model
 *    over-corrected: it decided the safest "result" was to echo the input unchanged.
 *  - Current: minimal envelope (no over-restrictive language). Trailing commentary is
 *    stripped deterministically in [stripTrailingCommentary] — far more reliable than
 *    asking a 1B model to suppress itself.
 */
private fun formatGemmaChat(systemPrompt: String, userText: String): String =
    if (systemPrompt.isBlank()) userText else "${systemPrompt.trim()}\n\n$userText"

/**
 * Pattern of phrases small models append after the corrected text to narrate what they did.
 * Match against trimmed-left line content; first hit cuts the output at the prior paragraph.
 * The phrases are intentionally common-suffix-like — false positives would only fire when a
 * legitimate result starts with one of these openings, which fix/translate/rewrite tasks
 * essentially never do.
 */
private val COMMENTARY_TRIGGERS = listOf(
    "I've corrected",
    "I have corrected",
    "I corrected",
    "I've fixed",
    "I have fixed",
    "I fixed",
    "I've improved",
    "I have improved",
    "I improved",
    "I've made",
    "I've rewritten",
    "I rewrote",
    "I've added",
    "Here's the corrected",
    "Here is the corrected",
    "Here's the fixed",
    "Here is the fixed",
    "Here's the improved",
    "The corrected text",
    "The corrected sentence",
    "The corrected version",
    "The fixed text",
    "The fixed version",
    "The edited text",
    "The improved text",
    "The improved version",
    "The rewritten text",
    "This text requires",
    "This sentence",
    "Note:",
    "Note that",
    "Explanation:",
    "Changes made",
    "Changes:",
    "Corrections:",
)

internal fun stripTrailingCommentary(raw: String): String {
    if (raw.isBlank()) return raw
    val lines = raw.lines()
    val kept = mutableListOf<String>()
    for (line in lines) {
        val head = line.trimStart()
        if (COMMENTARY_TRIGGERS.any { head.startsWith(it, ignoreCase = true) }) break
        kept += line
    }
    // If the very first line trips a trigger we'd return empty — fall back to the raw text
    // rather than discarding the model's whole reply. This protects against a customised user
    // prompt that legitimately asks for a "Here is…" style answer.
    if (kept.all { it.isBlank() }) return raw.trim()
    return kept.joinToString("\n").trimEnd()
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
