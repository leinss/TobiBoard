// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDictionaryPromptTest {
    private val base = "Fix spelling and grammar."

    @Test
    fun noWordsLeavesPromptUnchanged() {
        assertEquals(base, PersonalDictionaryPrompt.augmentSystemPrompt(base, emptyList()))
    }

    @Test
    fun blankAndShortWordsAreIgnored() {
        // "" is dropped, "a" is below MIN_WORD_LENGTH, "  " trims to empty → all filtered out.
        assertEquals(base, PersonalDictionaryPrompt.augmentSystemPrompt(base, listOf("", "a", "  ")))
    }

    @Test
    fun wordsAreAppendedAsAPreservationClause() {
        val result = PersonalDictionaryPrompt.augmentSystemPrompt(base, listOf("TobiBoard", "gnosis"))
        assertTrue("keeps the original prompt", result.startsWith(base))
        assertTrue("mentions the words", result.contains("TobiBoard, gnosis"))
        assertTrue("instructs to keep them", result.contains("do not change"))
        // The clause is separated from the user prompt by a blank line.
        assertTrue(result.contains("\n\n"))
    }

    @Test
    fun blankBasePromptYieldsClauseOnly() {
        val result = PersonalDictionaryPrompt.augmentSystemPrompt("", listOf("Leinss"))
        assertTrue(result.contains("Leinss"))
        // No leading blank lines when there is no base prompt.
        assertEquals(result, result.trim())
    }
}
