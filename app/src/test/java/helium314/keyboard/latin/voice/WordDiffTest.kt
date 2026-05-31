// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {

    /** The defining invariant: COMMON+REMOVED reconstructs the original; COMMON+ADDED the proposed. */
    private fun assertReconstructs(original: String, proposed: String) {
        val segments = WordDiff.diff(original, proposed)
        val rebuiltOriginal = segments
            .filter { it.op != WordDiff.Op.ADDED }
            .joinToString("") { it.text }
        val rebuiltProposed = segments
            .filter { it.op != WordDiff.Op.REMOVED }
            .joinToString("") { it.text }
        assertEquals("original reconstruction", original, rebuiltOriginal)
        assertEquals("proposed reconstruction", proposed, rebuiltProposed)
    }

    @Test
    fun identicalText_isAllCommon() {
        val segments = WordDiff.diff("hello world", "hello world")
        assertEquals(listOf(WordDiff.Segment("hello world", WordDiff.Op.COMMON)), segments)
    }

    @Test
    fun emptyInputs() {
        assertEquals(emptyList<WordDiff.Segment>(), WordDiff.diff("", ""))
        assertReconstructs("", "added text")
        assertReconstructs("removed text", "")
    }

    @Test
    fun pureInsertion_marksAddedWords() {
        val segments = WordDiff.diff("hello world", "hello brave world")
        assertTrue(segments.any { it.op == WordDiff.Op.ADDED && it.text.contains("brave") })
        assertTrue(segments.none { it.op == WordDiff.Op.REMOVED })
        assertReconstructs("hello world", "hello brave world")
    }

    @Test
    fun replacement_marksRemovedAndAdded() {
        val segments = WordDiff.diff("i can has cheezburger", "I can have a cheeseburger")
        assertTrue(segments.any { it.op == WordDiff.Op.ADDED })
        assertTrue(segments.any { it.op == WordDiff.Op.REMOVED })
        assertReconstructs("i can has cheezburger", "I can have a cheeseburger")
    }

    @Test
    fun punctuationAndSpacingPreserved() {
        assertReconstructs("hey,there.how are u", "Hey, there. How are you?")
    }

    @Test
    fun veryLargeInput_fallsBackToPlainProposed() {
        val original = (1..1000).joinToString(" ") { "word$it" }
        val proposed = (1..1000).joinToString(" ") { "term$it" }
        val segments = WordDiff.diff(original, proposed)
        // Over the token ceiling we don't diff; we just present the proposed text as one segment.
        assertEquals(listOf(WordDiff.Segment(proposed, WordDiff.Op.COMMON)), segments)
    }
}
