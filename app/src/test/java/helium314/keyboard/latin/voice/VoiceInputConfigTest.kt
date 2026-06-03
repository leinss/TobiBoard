// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputConfigTest {
    @Test
    fun parseVoiceDictionaryTermsSplitsAndDeduplicates() {
        assertEquals(
            listOf("OpenRouter", "TobiBoard", "gRPC"),
            parseVoiceDictionaryTerms("OpenRouter, TobiBoard\nopenrouter; gRPC"),
        )
    }

    @Test
    fun parseExpectedLanguagesSplitsAndDeduplicates() {
        assertEquals(
            listOf("English", "Italian", "Deutsch"),
            parseExpectedLanguages("English, Italian\nenglish; Deutsch"),
        )
    }

    @Test
    fun resolveVoicePromptAppendsSingleExpectedLanguage() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            expectedLanguagesRaw = "English",
        )

        assertTrue(prompt.systemPrompt.contains("The speaker is expected to speak English"))
        assertTrue(prompt.systemPrompt.contains("do not translate", ignoreCase = true))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptAppendsDictionaryToCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            transcriptionDictionaryRaw = "OpenRouter, TobiBoard, gRPC",
        )

        assertTrue(prompt.systemPrompt.contains("Strict dictionary"))
        assertTrue(prompt.systemPrompt.contains("MUST output the exact spelling"))
        assertTrue(prompt.systemPrompt.contains("OpenRouter, TobiBoard, gRPC"))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptKeepsLocaleHintOutOfCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            localeHint = Locale.forLanguageTag("it-IT"),
            transcriptionDictionaryRaw = "OpenRouter",
            expectedLanguagesRaw = "English, Italian",
        )

        assertTrue(prompt.systemPrompt.contains("OpenRouter"))
        assertTrue(prompt.systemPrompt.contains("English, Italian"))
        assertTrue(prompt.systemPrompt.contains("translate", ignoreCase = true))
        assertFalse(prompt.systemPrompt.contains("it-IT", ignoreCase = true))
        assertEquals("Expected spoken language: Italian (Italy) [it-IT].", prompt.runtimeInstruction)
    }

    @Test
    fun modelCatalogReportsCacheSupportForCatalogedModels() {
        // Cache support is now driven by ModelCatalog, not a substring heuristic, so the
        // request pipeline only attaches `cache_control` for slugs we've verified support
        // prompt caching against OpenRouter's per-model endpoints data.
        assertTrue(ModelCatalog.openRouterSupportsCache("~google/gemini-flash-latest"))
        assertTrue(ModelCatalog.openRouterSupportsCache("~anthropic/claude-haiku-latest"))
        assertTrue(ModelCatalog.openRouterSupportsCache("deepseek/deepseek-v4-flash"))
        assertFalse(ModelCatalog.openRouterSupportsCache("openai/whisper-1"))
        assertFalse(ModelCatalog.openRouterSupportsCache("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"))
    }
}
