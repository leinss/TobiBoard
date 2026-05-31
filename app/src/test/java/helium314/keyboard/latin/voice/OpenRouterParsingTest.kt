// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the response-parsing layer of the transcription pipeline — the step between
 * "transcribe" and "insert". Runs under Robolectric so the real org.json is available.
 */
@RunWith(RobolectricTestRunner::class)
class OpenRouterParsingTest {

    private val client = OpenRouterClient(
        apiKey = "unused",
        model = "unused/unused",
        systemPrompt = "unused",
        runtimeInstruction = null,
    )

    @Test
    fun parsesTotalTokens() {
        assertEquals(1234, client.parseTotalTokens(JSONObject("""{"usage":{"total_tokens":1234}}""")))
    }

    @Test
    fun fallsBackToPromptPlusCompletionTokens() {
        assertEquals(15, client.parseTotalTokens(JSONObject("""{"usage":{"prompt_tokens":10,"completion_tokens":5}}""")))
    }

    @Test
    fun missingUsageIsZeroTokens() {
        assertEquals(0, client.parseTotalTokens(JSONObject("{}")))
    }

    @Test
    fun parseContentReturnsMessageAndRecordsTokens() {
        val body = """{"usage":{"total_tokens":42},"choices":[{"message":{"content":"Hello there"}}]}"""
        assertEquals("Hello there", client.parseContent(body))
        assertEquals(42, client.lastResponseTokens)
    }

    @Test
    fun extractMessageTextJoinsArrayContentParts() {
        val message = JSONObject("""{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")
        assertEquals("a\nb", client.extractMessageText(message))
    }

    @Test
    fun extractMessageTextPrefersAudioTranscript() {
        val message = JSONObject("""{"audio":{"transcript":"spoken words"}}""")
        assertEquals("spoken words", client.extractMessageText(message))
    }

    @Test(expected = OpenRouterException::class)
    fun malformedResponseThrows() {
        client.parseContent("not valid json")
    }

    @Test(expected = OpenRouterException::class)
    fun responseWithoutChoicesThrows() {
        client.parseContent("""{"usage":{"total_tokens":1}}""")
    }
}
