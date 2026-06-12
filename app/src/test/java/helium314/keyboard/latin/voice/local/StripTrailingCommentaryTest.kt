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

    // --- Language-agnostic cleanup (M1-3) ---
    //
    // The default text-fix prompt preserves the input language, so a 1B model fed German/Spanish/
    // French input narrates its edits in that language. Trigger-phrase backstops plus a structural
    // rule (short, low-overlap trailing paragraph) now strip that commentary.

    @Test fun stripsGermanCommentaryViaTrigger() {
        val raw = "Hallo Welt.\nIch habe die Tippfehler korrigiert und die Grammatik verbessert."
        assertEquals("Hallo Welt.", stripTrailingCommentary(raw))
    }

    @Test fun stripsSpanishCommentaryViaTrigger() {
        val raw = "Hola mundo.\nHe corregido los errores y mejorado la gramática."
        assertEquals("Hola mundo.", stripTrailingCommentary(raw))
    }

    @Test fun stripsFrenchCommentaryViaTrigger() {
        val raw = "Bonjour le monde.\nVoici le texte corrigé : Bonjour le monde."
        assertEquals("Bonjour le monde.", stripTrailingCommentary(raw))
    }

    @Test fun stripsChatTemplateMarkers() {
        assertEquals("Hello world.", stripTrailingCommentary("Hello world.<end_of_turn>"))
        assertEquals("Hello world.", stripTrailingCommentary("<start_of_turn>Hello world.<end_of_turn>"))
        assertEquals("Hello world.", stripTrailingCommentary("Hello world.<|im_end|>"))
    }

    @Test fun detectsExactInputEcho() {
        // Model echoed the input verbatim (over-cautious "fix") — return the input unchanged.
        val input = "Hello world."
        assertEquals(input, stripTrailingCommentary("Hello world.", input))
    }

    @Test fun echoWithPunctuationAndCasingCorrectionSurvives() {
        // TB1: punctuation/casing-only correction must NOT be discarded as echo.
        // input "hello world" normalizes to "hello world"; model "Hello world." also normalizes
        // to "hello world" — they are equal token-wise, so echo-detection fires. The fix is to
        // return markerStripped.trim() (the model's corrected form), not input.trim().
        val input = "hello world"
        val result = stripTrailingCommentary("Hello world.", input)
        assertEquals("Hello world.", result)
    }

    @Test fun detectsInputEchoAfterPromptLine() {
        // Strict-envelope failure shape: a prompt-echo line, then the input as the actual reply.
        val input = "Hello world."
        val raw = "Fix this text:\n\nHello world."
        assertEquals("Hello world.", stripTrailingCommentary(raw, input))
    }

    @Test fun fallbackUsesMarkerStrippedNotRawWhenFirstLineIsTriggerWithLeadingMarker() {
        // TB12: <end_of_turn> marker followed immediately by a trigger-first-line.
        // After marker stripping: "Here's the corrected text: Hello world." (trigger on line 0).
        // Kept list is all-blank → fallback. Must return markerStripped, not raw (which would
        // still contain "<end_of_turn>").
        val raw = "<end_of_turn>Here's the corrected text: Hello world."
        val result = stripTrailingCommentary(raw)
        assertEquals("Here's the corrected text: Hello world.", result)
    }

    @Test fun structuralRuleDropsShortLowOverlapTrailingParagraph() {
        // Non-English commentary with no trigger phrase, caught structurally.
        val input = "ein langer deutscher Satz der korrigiert werden soll mit vielen Wörtern hier drin"
        val raw = "Ein langer deutscher Satz der korrigiert werden soll mit vielen Wörtern hier drin.\n\n" +
            "Fertig erledigt."
        assertEquals(
            "Ein langer deutscher Satz der korrigiert werden soll mit vielen Wörtern hier drin.",
            stripTrailingCommentary(raw, input),
        )
    }

    @Test fun structuralRuleKeepsLegitimateMultiParagraphResult() {
        // Two real paragraphs whose second is long and overlaps the input heavily must survive.
        val input = "frist paragraph abuot cats\n\nsecnd paragraph abuot cats and dogs togethr now"
        val raw = "First paragraph about cats.\n\nSecond paragraph about cats and dogs together now."
        assertEquals(raw, stripTrailingCommentary(raw, input))
    }
}
