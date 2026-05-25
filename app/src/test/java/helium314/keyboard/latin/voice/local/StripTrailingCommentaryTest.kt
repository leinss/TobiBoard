// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StripTrailingCommentaryTest {

    @Test fun keepsOutputWithoutCommentary() {
        val input = "Hello! Quick brown fox jumps over dog."
        assertEquals(input, stripTrailingCommentary(input))
    }

    @Test fun stripsTrailingIveCorrectedBlock() {
        val raw = "Hello! Quick brown fox jumps over dog.\nI've corrected the typos and improved the grammar."
        assertEquals("Hello! Quick brown fox jumps over dog.", stripTrailingCommentary(raw))
    }

    @Test fun stripsHeresTheCorrectedBlock() {
        val raw = "Hello world\n\nHere's the corrected version: Hello world."
        assertEquals("Hello world", stripTrailingCommentary(raw))
    }

    @Test fun stripsTheCorrectedTextBlock() {
        val raw = "Hello world.\nThe corrected text preserves your meaning."
        assertEquals("Hello world.", stripTrailingCommentary(raw))
    }

    @Test fun stripsRunOnSummary() {
        // The real-user failure shape: model concatenates a meta-summary right after the fix.
        val raw = "Hello! Quick brown fox jumps over dog\nThis text requires immediate attention. I've corrected any errors, improved grammar and punctuation while maintaining both tone and language."
        assertEquals("Hello! Quick brown fox jumps over dog", stripTrailingCommentary(raw))
    }

    @Test fun stripsNoteAndExplanationLines() {
        val raw = "Hallo Welt\nNote: I translated the greeting to German.\nExplanation: 'world' becomes 'Welt'."
        assertEquals("Hallo Welt", stripTrailingCommentary(raw))
    }

    @Test fun caseInsensitiveTriggerMatch() {
        val raw = "Hello world\nHERE'S THE CORRECTED TEXT."
        assertEquals("Hello world", stripTrailingCommentary(raw))
    }

    @Test fun fallsBackToRawWhenFirstLineIsTrigger() {
        // If we'd strip the whole thing we'd lose the model's only reply. The user may have a
        // customised system prompt that legitimately asks for a "Here is …" style answer.
        val raw = "Here's the corrected text: Hello world"
        assertEquals(raw, stripTrailingCommentary(raw))
    }

    @Test fun blankInputReturnedAsIs() {
        assertEquals("", stripTrailingCommentary(""))
        assertEquals("   ", stripTrailingCommentary("   "))
    }

    @Test fun trimsTrailingWhitespaceFromKeptBlock() {
        val raw = "Hello world.\n\n\nI've corrected the typos."
        assertEquals("Hello world.", stripTrailingCommentary(raw))
    }
}
