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

    // --- Known gaps (pin current behavior before M1-3 makes the stripper language-agnostic) ---
    //
    // The default text-fix prompt preserves the input language (Defaults.PREF_TEXT_FIX_SYSTEM_PROMPT),
    // so a 1B model fed German/Spanish/French input narrates its edits in that language. The current
    // COMMENTARY_TRIGGERS list is English-only, so non-English commentary is NOT stripped today and
    // leaks into the committed replacement. These fixtures capture real-shape outputs and assert the
    // current (leaky) behavior; when M1-3 lands a structural/multi-language stripper, flip the
    // expected value to the cleaned text and drop the "gap_" prefix.

    @Test fun gap_germanCommentaryNotStrippedToday() {
        val raw = "Hallo Welt.\nIch habe die Tippfehler korrigiert und die Grammatik verbessert."
        // TODO(M1-3): should become "Hallo Welt."
        assertEquals(raw, stripTrailingCommentary(raw))
    }

    @Test fun gap_spanishCommentaryNotStrippedToday() {
        val raw = "Hola mundo.\nHe corregido los errores y mejorado la gramática."
        // TODO(M1-3): should become "Hola mundo."
        assertEquals(raw, stripTrailingCommentary(raw))
    }

    @Test fun gap_frenchCommentaryNotStrippedToday() {
        val raw = "Bonjour le monde.\nVoici le texte corrigé : Bonjour le monde."
        // TODO(M1-3): should become "Bonjour le monde."
        assertEquals(raw, stripTrailingCommentary(raw))
    }

    @Test fun gap_chatTemplateMarkersNotStrippedToday() {
        // Should the MediaPipe runtime ever surface chat-template tokens, they currently survive.
        val raw = "Hello world.<end_of_turn>"
        // TODO(M1-3): should become "Hello world."
        assertEquals(raw, stripTrailingCommentary(raw))
    }

    @Test fun gap_inputEchoNotDetectedToday() {
        // Strict-envelope failure shape: model echoes the prompt+input instead of just the fix.
        val raw = "Fix this text:\nHello world."
        // TODO(M1-3): structural input-echo detection should return just "Hello world."
        assertEquals(raw, stripTrailingCommentary(raw))
    }
}
