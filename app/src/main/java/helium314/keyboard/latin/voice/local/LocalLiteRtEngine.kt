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
            ?: throw IOException("On-device text-fix model not downloaded — open Settings → On-device models.")
        val prompt = formatGemmaChat(systemPrompt, userText)
        if (cancelled) return ""
        val started = System.currentTimeMillis()
        val raw = inference.generateResponse(prompt)
        val cleaned = stripTrailingCommentary(raw, userText)
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
    // German backstop (owner language). The structural rule below catches most non-English
    // commentary regardless of language; these pin the common German shapes deterministically.
    "Ich habe",
    "Hier ist der korrigierte",
    "Hier ist die korrigierte",
    "Der korrigierte Text",
    "Die korrigierte",
    "Anmerkung:",
    "Hinweis:",
    "Erklärung:",
    "Änderungen:",
    // Spanish / French common openings (backstop; structural rule is the primary guard).
    "He corregido",
    "Aquí está el texto corregido",
    "El texto corregido",
    "J'ai corrigé",
    "Voici le texte corrigé",
    "Le texte corrigé",
)

/**
 * Chat-template / control tokens the LiteRT runtime should consume internally, but which
 * occasionally leak into `generateResponse` output. Stripped verbatim before any other rule.
 */
private val CHAT_TEMPLATE_MARKERS = listOf(
    "<start_of_turn>", "<end_of_turn>", "<eos>", "<bos>", "</s>", "<s>", "<pad>", "<unk>",
)
private val CHAT_TEMPLATE_MARKER_REGEX = Regex("<\\|[^>]*\\|>")

private fun stripChatTemplateMarkers(text: String): String {
    var out = text
    for (marker in CHAT_TEMPLATE_MARKERS) out = out.replace(marker, "")
    return CHAT_TEMPLATE_MARKER_REGEX.replace(out, "")
}

/** Tokenize to lowercase word tokens for overlap/echo comparison. */
private fun tokenize(text: String): List<String> =
    Regex("\\p{L}+").findAll(text.lowercase()).map { it.value }.toList()

private fun normalizeForEcho(text: String): String =
    tokenize(text).joinToString(" ")

/**
 * Deterministically removes a small model's trailing meta-commentary, language-agnostically.
 *
 * Pipeline (each step is a no-op when it does not apply):
 *  1. strip chat-template control tokens (`<end_of_turn>` etc.);
 *  2. input-echo: if the whole reply normalizes to the input (optionally with a leading
 *     prompt-echo line), return the input unchanged so the manager's no-op path handles it;
 *  3. English/German/Spanish/French trigger phrases cut the reply at the first matching line;
 *  4. structural rule: a trailing paragraph separated by a blank line that is much shorter than
 *     the kept body and shares little vocabulary with the input is dropped as commentary.
 *
 * Never returns empty when the model produced something: if every rule would discard the whole
 * reply, the raw (marker-stripped) text is returned so a customised user prompt that legitimately
 * asks for a "Here is…" answer is preserved. [input] is optional; pass the user's original text to
 * enable echo detection and overlap scoring.
 */
internal fun stripTrailingCommentary(raw: String, input: String = ""): String {
    if (raw.isBlank()) return raw
    val markerStripped = stripChatTemplateMarkers(raw).trim()
    if (markerStripped.isBlank()) return markerStripped

    // 2. Input-echo: the reply (or its body after a one-line prompt echo) is just the input.
    if (input.isNotBlank()) {
        val normInput = normalizeForEcho(input)
        if (normInput.isNotEmpty()) {
            if (normalizeForEcho(markerStripped) == normInput) return markerStripped.trim()
            val paras = markerStripped.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            if (paras.size >= 2 && normalizeForEcho(paras.drop(1).joinToString("\n")) == normInput) {
                return paras.drop(1).joinToString("\n").trim()
            }
        }
    }

    // 3. Trigger-phrase cut.
    val lines = markerStripped.lines()
    val kept = mutableListOf<String>()
    for (line in lines) {
        val head = line.trimStart()
        if (COMMENTARY_TRIGGERS.any { head.startsWith(it, ignoreCase = true) }) break
        kept += line
    }
    var body = if (kept.all { it.isBlank() }) markerStripped else kept.joinToString("\n").trimEnd()

    // 4. Structural rule: drop a short, low-overlap trailing paragraph as commentary.
    body = dropTrailingCommentaryParagraph(body, input)

    if (body.isBlank()) return markerStripped
    return body.trimEnd()
}

/**
 * If [body] has a trailing paragraph (separated by a blank line) that is comparatively short and
 * shares little vocabulary with [input], treat it as model commentary and drop it. Conservative:
 * only fires when an explicit blank-line paragraph break exists, so single-paragraph fixes are
 * never touched.
 */
private fun dropTrailingCommentaryParagraph(body: String, input: String): String {
    if (input.isBlank()) return body
    val paras = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.size < 2) return body
    val head = paras.dropLast(1).joinToString("\n\n")
    val tail = paras.last()
    val headLen = head.length
    val tailLen = tail.length
    if (headLen == 0) return body
    // Tail must be clearly shorter than the kept body (commentary, not the actual result).
    if (tailLen.toDouble() / headLen >= 0.4) return body
    val inputTokens = tokenize(input).toSet()
    if (inputTokens.isEmpty()) return body
    val tailTokens = tokenize(tail)
    if (tailTokens.isEmpty()) return body
    val overlap = tailTokens.count { it in inputTokens }.toDouble() / tailTokens.size
    if (overlap < 0.3) {
        Log.w("LocalLiteRtEngine", "dropTrailingCommentaryParagraph: dropped trailing paragraph (tailLen=$tailLen headLen=$headLen overlap=${"%.2f".format(overlap)})")
        return head
    }
    return body
}

private object SharedLlm {
    private const val TAG = "LocalLiteRtEngine"

    @Volatile private var inference: LlmInference? = null
    @Volatile private var loadedModelId: String? = null
    private val initLock = Any()

    fun acquire(context: Context): LlmInference? {
        val model = ModelRegistry.activeTextFix(context)
        inference?.let { if (loadedModelId == model.id) return it }
        synchronized(initLock) {
            inference?.let {
                if (loadedModelId == model.id) return it
                // The user switched the active text-fix model: drop the stale handle and reload.
                it.close()
                inference = null
                loadedModelId = null
            }
            if (!ModelStorage.isReady(context, model)) return null
            val modelFile = ModelStorage.fileFor(context, model, model.files.first())
            return try {
                val started = System.currentTimeMillis()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                val llm = LlmInference.createFromOptions(context, options)
                Log.i(TAG, "Initialised LlmInference (${model.id}) in ${System.currentTimeMillis() - started} ms")
                inference = llm
                loadedModelId = model.id
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
            loadedModelId = null
        }
    }

    // TextFixManager caps input at 10k chars (~3k tokens); 4k tokens leaves headroom both ways.
    private const val MAX_TOKENS = 4096
}
