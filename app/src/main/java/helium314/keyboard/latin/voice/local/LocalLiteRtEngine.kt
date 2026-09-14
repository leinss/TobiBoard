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
 * the call but a late cancel still waits for the in-flight generation to finish. The handle's
 * lifetime (reference count, idle release, trim) is [SharedNativeHandle], shared with the
 * on-device speech recogniser.
 */
internal class LocalLiteRtEngine(
    private val context: Context,
    private val systemPrompt: String,
    /**
     * Fired on the background thread once the native handle exists and generation is about to
     * start. The caller uses it to move out of its "preparing" state: on a cold start the load is
     * seconds and the generation that follows is not, so one label for both made a first run look
     * exactly like a hung one.
     */
    private val onModelReady: (() -> Unit)? = null,
) : TextFixEngine {

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun fixText(userText: String): String {
        if (cancelled) return ""
        // beginUse pins the shared handle for the duration of this generation so a concurrent
        // trim/idle release can never close() it mid-call (generateResponse is uninterruptible);
        // endUse unpins and honors any release that was deferred while we were running.
        val inference = SharedLlm.beginUse(context)
            ?: throw IOException("On-device text-fix model not downloaded — open Settings → On-device models.")
        try {
            onModelReady?.invoke()
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
        } catch (oom: OutOfMemoryError) {
            // The ~1 GB handle survives this request, so the next fix would allocate against the
            // same held gigabyte and die the same way. Drop it before unwinding; the release is
            // deferred until endUse() below, because we are still inside the pinned generation.
            Log.e(TAG, "Out of memory during generation; releasing the shared LlmInference", oom)
            SharedLlm.release()
            throw oom
        } finally {
            SharedLlm.endUse()
        }
    }

    companion object {
        private const val TAG = "LocalLiteRtEngine"

        /** Release the shared handle now (deferred if a generation is in flight). */
        @JvmStatic
        fun releaseShared() = SharedLlm.release()

        /** Release off the caller's thread — freeing ~1 GB of native memory can be slow. */
        @JvmStatic
        fun releaseSharedAsync() = SharedLlm.releaseAsync()

        /**
         * Free the on-device LLM under real memory pressure so the IME process is not killed for
         * squatting on ~1 GB of native memory. Reloads lazily on the next fix. Deliberately ignores
         * TRIM_MEMORY_UI_HIDDEN (fires on every keyboard hide — far too frequent to reload for).
         */
        @JvmStatic
        fun onTrimMemory(level: Int) {
            if (shouldReleaseOnTrim(level)) releaseSharedAsync()
        }
    }
}

/**
 * Model files are present but the native LlmInference handle could not be initialised (corrupt
 * `.task` bundle, OOM, unsupported backend). Distinct from "not downloaded" so the UI can suggest
 * a re-download rather than misrouting the user to a model that already shows as Ready in Settings.
 */
internal class LocalModelLoadException(cause: Throwable) : Exception(cause)

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

    // TextFixManager caps input at 10k chars (~3k tokens); 4k tokens leaves headroom both ways.
    private const val MAX_TOKENS = 4096

    private val shared = SharedNativeHandle<LlmInference>(TAG) { it.close() }

    /** Pin the handle for a generation. Pair with [endUse] in a finally block. */
    fun beginUse(context: Context): LlmInference? {
        val model = ModelRegistry.activeTextFix(context)
        return shared.beginUse(model.id) { build(context, model) }
    }

    /** Unpin after a generation; honor a deferred release or (re)arm the idle timer. */
    fun endUse() = shared.endUse()

    /** Release now, or defer until the in-flight generation completes. */
    fun release() = shared.release()

    /** Release off the caller's thread — freeing ~1 GB of native memory can be slow. */
    fun releaseAsync() = shared.releaseAsync()

    /** Null when the model is not on disk; throws [LocalModelLoadException] when it will not load. */
    private fun build(context: Context, model: TextFixModelInfo): LlmInference? {
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
            llm
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialise LlmInference", t)
            // Distinct from the not-ready null return above: the files exist but the native handle
            // won't load. Surface it so the user is told to re-download, not "not downloaded".
            throw LocalModelLoadException(t)
        }
    }
}
