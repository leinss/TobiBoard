// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [TextReplaceGuard.liveTextStillMatches] — the shared decision behind
 * LatinIME's text-fix-commit and undo guards. These pin the behaviour that protects the user's
 * live text from being clobbered by a stale mutation.
 */
class TextReplaceGuardTest {

    // ── match cases ───────────────────────────────────────────────────────────────────────────
    // These two pin why the guard must use contentEquals, not equals/== (see the StringBuilder case).

    @Test fun exactStringMatchAllows() {
        assertTrue(TextReplaceGuard.liveTextStillMatches("hello", "hello"))
    }

    @Test fun nonStringCharSequenceWithEqualContentAllows() {
        // The live text from RichInputConnection is a CharSequence (a StringBuilder / ExtractedText
        // .text), not a String. This case is the whole reason the guard uses contentEquals: a
        // String.equals(StringBuilder) check would be false here and silently disable the guard.
        assertTrue(TextReplaceGuard.liveTextStillMatches("hello", StringBuilder("hello")))
    }

    // ── reject cases ──────────────────────────────────────────────────────────────────────────

    @Test fun userAppendedCharRejects() {
        assertFalse(TextReplaceGuard.liveTextStillMatches("hello", "hello!"))
    }

    @Test fun sameLengthDifferentContentRejects() {
        assertFalse(TextReplaceGuard.liveTextStillMatches("hello", "hallo"))
    }

    @Test fun connectionGoneRejects() {
        // getTextBeforeCursor / getSelectedText / getWholeFieldText return null when disconnected.
        assertFalse(TextReplaceGuard.liveTextStillMatches("hello", null))
    }

    @Test fun trailingWhitespaceDifferenceRejects() {
        assertFalse(TextReplaceGuard.liveTextStillMatches("hello", "hello "))
    }

    @Test fun caseOnlyDifferenceRejects() {
        assertFalse(TextReplaceGuard.liveTextStillMatches("Hello", "hello"))
    }

    @Test fun equalContentWithSurrogatePairAllows() {
        // U+1F600 GRINNING FACE — surrogate-pair safety is a recurring concern in this codebase.
        val grinning = "hi 😀 there"
        assertTrue(TextReplaceGuard.liveTextStillMatches(grinning, StringBuilder(grinning)))
    }

    @Test fun bothEmptyAllows() {
        assertTrue(TextReplaceGuard.liveTextStillMatches("", ""))
    }
}
