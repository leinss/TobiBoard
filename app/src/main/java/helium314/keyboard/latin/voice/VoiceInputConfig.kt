// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.voice.local.LocalModelLoadException
import java.text.BreakIterator
import java.util.Locale

private val SANITIZE_OUTPUT_REGEX =
    Regex("[[\\p{Cc}&&[^\\n\\r\\t]]\\u200B\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")

/**
 * Tag characters (U+E0000-U+E007F) are invisible, so they can hide text in the output (SEC-012).
 * Group 1 is a subdivision flag (U+1F3F4, a code of 3 to 6 tag letters or digits, U+E007F), which
 * stays; each other tag character is removed.
 */
private val TAG_CHARACTER_REGEX =
    Regex("(\\x{1F3F4}[\\x{E0030}-\\x{E0039}\\x{E0061}-\\x{E007A}]{3,6}\\x{E007F})|[\\x{E0000}-\\x{E007F}]")

/**
 * Strip control characters (Cc), invisible format marks (bidi marks, U+2060-U+2064, line and
 * paragraph separators, tag characters outside a flag) that corrupt host editors or hide text,
 * but intentionally preserve U+200D (ZWJ) so emoji sequences survive, U+200C (ZWNJ), which is part
 * of the spelling of Persian and of Indic scripts (R-03), and newline/carriage-return/tab
 * so the model's requested line breaks and spacing survive (otherwise "end.\nNext" collapses to
 * "end.Next", losing both the line break and the space after punctuation).
 */
internal fun cleanModelOutput(raw: String): String = raw.replace(SANITIZE_OUTPUT_REGEX, "")
    .replace(TAG_CHARACTER_REGEX) { it.groupValues[1] }
    .trim()

/** [cleanModelOutput], then cut to [maxLength] at a grapheme boundary. */
internal fun sanitizeModelOutput(raw: String, maxLength: Int): String {
    val cleaned = cleanModelOutput(raw)
    if (cleaned.length <= maxLength) return cleaned
    // Truncate at a grapheme cluster boundary so we never split a surrogate pair, a ZWJ emoji
    // sequence, or a base+combining-mark cluster - any of which would render as broken glyphs.
    // BreakIterator handles surrogate/combining boundaries, but Java/Android implementations can
    // still stop between emoji that are joined by U+200D, so we repair that boundary explicitly.
    val breaker = BreakIterator.getCharacterInstance()
    breaker.setText(cleaned)
    val boundary = breaker.preceding(maxLength + 1)
    val end = avoidPartialZwjSequence(cleaned, if (boundary == BreakIterator.DONE) 0 else boundary)
    return cleaned.substring(0, end.coerceAtMost(maxLength))
}

private fun avoidPartialZwjSequence(text: String, end: Int): Int {
    if (end <= 0 || end >= text.length) return end
    return when {
        text[end] == '\u200D' -> startOfZwjSequenceBefore(text, end)
        text[end - 1] == '\u200D' -> startOfZwjSequenceBefore(text, end - 1)
        else -> end
    }
}

private fun startOfZwjSequenceBefore(text: String, zwjIndex: Int): Int {
    var start = Character.offsetByCodePoints(text, zwjIndex, -1)
    while (start >= 2 && text[start - 1] == '\u200D') {
        start = Character.offsetByCodePoints(text, start - 1, -1)
    }
    return start
}

/**
 * Prepends a space when the cursor sits immediately after a letter/digit, and appends one
 * when the next char is a letter/digit. Keeps `"hello".world` from becoming `"hello"world`.
 * Accepts a [VoiceInputManager.SpacingContext] whose fields are Unicode code points (surrogate-pair safe),
 * or null to leave the text unchanged.
 */
internal fun applySpacing(text: String, ctx: VoiceInputManager.SpacingContext?): String {
    if (text.isEmpty() || ctx == null) return text
    val leadingCp = Character.codePointAt(text, 0)
    val trailingCp = Character.codePointBefore(text, text.length)
    val leadingIsWhitespace = Character.isWhitespace(leadingCp)
    val trailingIsWhitespace = Character.isWhitespace(trailingCp)
    // No space next to a script that does not use spaces (CJK, kana), as in joinSegments (R-17).
    val needsLeading = ctx.charBefore?.let {
        Character.isLetterOrDigit(it) && !leadingIsWhitespace && !isSpacelessScript(it) && !isSpacelessScript(leadingCp)
    } == true
    val needsTrailing = ctx.charAfter?.let {
        Character.isLetterOrDigit(it) && !trailingIsWhitespace && !isSpacelessScript(it) && !isSpacelessScript(trailingCp)
    } == true
    val prefix = if (needsLeading) " " else ""
    val suffix = if (needsTrailing) " " else ""
    return prefix + text + suffix
}

private val SENSITIVE_USER_FACING_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("(?i)Bearer\\s+\\S+") to "Bearer ***",
    Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+") to "$1***",
)

/**
 * Resolves a user-facing error message from a transcription/text-fix exception. A
 * [LocalModelLoadException] maps to the re-download hint; an [OpenRouterException] surfaces its
 * message after scrubbing tokens; everything else falls back to [fallbackResId] so unrelated
 * stack traces never leak.
 */
internal fun safeUserFacingError(context: Context, e: Throwable, @StringRes fallbackResId: Int): String {
    if (e is LocalModelLoadException) return context.getString(R.string.text_fix_error_local_load_failed)
    if (e is OpenRouterException) {
        if (e.statusCode == 429 || e.statusCode == 503) return context.getString(R.string.voice_error_rate_limited)
        val raw = e.message?.takeIf { it.isNotBlank() }
        if (raw != null) {
            return SENSITIVE_USER_FACING_PATTERNS.fold(raw) { acc, (regex, replacement) ->
                acc.replace(regex, replacement)
            }
        }
    }
    return context.getString(fallbackResId)
}

internal fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Suppress("DEPRECATION")
    val activeNetwork = cm.activeNetworkInfo ?: return false
    @Suppress("DEPRECATION")
    return activeNetwork.isConnected
}

internal data class ResolvedVoicePrompt(
    val systemPrompt: String,
    val runtimeInstruction: String?,
)

enum class VoiceTranscriptionMode {
    CHAT_AUDIO,
    OPENROUTER_STT,
}

/**
 * Levels of post-transcription polishing. NATURAL bypasses the polish pass entirely; the rest map
 * to a graded system prompt that the polish LLM uses to clean up the raw transcription.
 */
enum class PolishLevel(val prefValue: String) {
    NATURAL("natural"),
    LIGHT("light"),
    FIXED("fixed"),
    REPHRASED("rephrased"),
    CORRECTED("corrected"),
    POLISHED("polished");

    companion object {
        fun fromPref(value: String?): PolishLevel =
            entries.firstOrNull { it.prefValue == value } ?: FIXED
    }
}

/** Returns the system prompt for [level], or null when no polish pass should run. */
internal fun polishPromptForLevel(level: PolishLevel): String? = when (level) {
    PolishLevel.NATURAL -> null
    PolishLevel.LIGHT -> Defaults.PREF_VOICE_POLISH_PROMPT_LIGHT
    PolishLevel.FIXED -> Defaults.PREF_VOICE_POLISH_PROMPT_FIXED
    PolishLevel.REPHRASED -> Defaults.PREF_VOICE_POLISH_PROMPT_REPHRASED
    PolishLevel.CORRECTED -> Defaults.PREF_VOICE_POLISH_PROMPT_CORRECTED
    PolishLevel.POLISHED -> Defaults.PREF_VOICE_POLISH_PROMPT_POLISHED
}

internal fun resolveVoiceModel(selectedModel: String, customModel: String): String? {
    if (selectedModel != "custom") {
        return selectedModel.trim().takeIf { it.isNotEmpty() }
    }
    return customModel.trim().takeIf { it.isNotEmpty() }
}

internal fun resolveVoiceSttModel(selectedModel: String, customModel: String): String? =
    resolveVoiceModel(selectedModel, customModel)

internal fun Locale.toOpenRouterSttLanguage(): String? =
    language.takeIf { it.length == 2 && it.all { ch -> ch.isLetter() } }?.lowercase(Locale.ROOT)

internal fun resolveVoicePrompt(
    savedPrompt: String,
    localeHint: Locale? = null,
    transcriptionDictionaryRaw: String = "",
    expectedLanguagesRaw: String = "",
): ResolvedVoicePrompt {
    val base = savedPrompt.trim().takeIf { it.isNotEmpty() } ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
    val dictionaryTerms = parseVoiceDictionaryTerms(transcriptionDictionaryRaw)
    val expectedLanguages = parseExpectedLanguages(expectedLanguagesRaw)
    val systemPrompt = listOfNotNull(
        base,
        dictionaryInstruction(dictionaryTerms),
        expectedLanguagesInstruction(expectedLanguages),
    )
        .joinToString("\n")
        .trim()
    return ResolvedVoicePrompt(
        systemPrompt = systemPrompt,
        runtimeInstruction = localeHintInstruction(systemPrompt, localeHint),
    )
}

internal fun parseExpectedLanguages(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

internal fun parseVoiceDictionaryTerms(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

private fun expectedLanguagesInstruction(languages: List<String>): String? {
    if (languages.isEmpty()) return null
    val joined = languages.joinToString(", ")
    return if (languages.size == 1) {
        "The speaker is expected to speak $joined. Transcribe in the spoken language only; do not translate and do not output multiple versions."
    } else {
        "The speaker may use any of these languages: $joined. Detect which one is actually spoken and transcribe in that language only. Do not translate and do not output the transcript in more than one language."
    }
}

private fun dictionaryInstruction(terms: List<String>): String? {
    if (terms.isEmpty()) return null
    return buildString {
        append("Strict dictionary — these are the canonical spellings for names, brands, acronyms, and technical terms in this transcript. ")
        append("Whenever the audio matches one of these terms phonetically, even loosely, you MUST output the exact spelling, casing, and punctuation shown here, without exception: ")
        append(terms.joinToString(", "))
        append(". Apply this to every occurrence in the audio, not just the first one. Never substitute a homophone, common spelling, or translation. ")
        append("Only ignore an entry when the audio unambiguously says a completely different word.")
    }
}

private fun localeHintInstruction(systemPrompt: String, localeHint: Locale?): String? {
    if (localeHint == null) return null
    val tag = localeHint.toLanguageTag()
    if (tag.isBlank() || tag.equals("und", ignoreCase = true)) return null
    if (systemPrompt.contains(tag, ignoreCase = true)) return null
    val display = localeHint.getDisplayName(Locale.ENGLISH).ifBlank { tag }
    return "Expected spoken language: $display [$tag]."
}
