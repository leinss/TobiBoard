// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.utils.Log

/**
 * Makes the AI text-fix aware of the user's personal dictionary: the custom words they added
 * (names, brands, handles, jargon) are appended to the text-fix system prompt as a
 * "keep these words as-is" instruction, so the model does not "correct" them into something else.
 *
 * Applies to both the on-device model and cloud providers (per the user's chosen scope). Cloud
 * requests therefore carry the personal-dictionary words — that is the accepted trade-off for
 * cloud text-fix; the on-device path keeps everything local.
 */
object PersonalDictionaryPrompt {
    private const val TAG = "PersonalDictionaryPrompt"

    /** Cap the injected list so the prompt stays bounded for the small on-device model. */
    const val MAX_WORDS = 80

    /** Skip 1-char entries — never useful to protect and they dilute the instruction. */
    private const val MIN_WORD_LENGTH = 2

    /**
     * Read the user's personal-dictionary words (all locales), most-frequent first, de-duplicated
     * case-insensitively and capped at [MAX_WORDS]. Performs a ContentResolver query, so call it
     * off the main thread. Returns an empty list on any failure (missing provider, permission, etc.)
     * — text-fix must still work without the enrichment.
     */
    fun readWords(context: Context): List<String> {
        return try {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY),
                null, null,
                UserDictionary.Words.FREQUENCY + " DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return emptyList()
                val wordIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
                val seen = LinkedHashSet<String>()
                while (!cursor.isAfterLast && seen.size < MAX_WORDS) {
                    val word = cursor.getString(wordIndex)?.trim().orEmpty()
                    // De-dup on a lowercased key but keep the original casing for the prompt.
                    if (word.length >= MIN_WORD_LENGTH && seen.none { it.equals(word, ignoreCase = true) }) {
                        seen.add(word)
                    }
                    cursor.moveToNext()
                }
                seen.toList()
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read personal dictionary for text-fix", t)
            emptyList()
        }
    }

    /**
     * Append a "keep these words unchanged" clause built from [words] to [systemPrompt]. Pure and
     * side-effect-free so it is unit-testable. Returns [systemPrompt] unchanged when there are no
     * words, so the no-dictionary case is a genuine no-op (identical prompt/caching behavior).
     */
    fun augmentSystemPrompt(systemPrompt: String, words: List<String>): String {
        val filtered = words.map { it.trim() }.filter { it.length >= MIN_WORD_LENGTH }
        if (filtered.isEmpty()) return systemPrompt
        val clause = "Keep these words spelled exactly as written and do not change or remove them: " +
                filtered.joinToString(", ") + "."
        return if (systemPrompt.isBlank()) clause else "${systemPrompt.trimEnd()}\n\n$clause"
    }

    /** Convenience: read the dictionary and augment in one call (does the ContentResolver read). */
    fun augment(context: Context, systemPrompt: String): String =
        augmentSystemPrompt(systemPrompt, readWords(context))
}
