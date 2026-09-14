// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class EngineInterfaceTest {

    private fun newClient() = OpenRouterClient(
        apiKey = "dummy",
        model = "openai/gpt-4o-mini",
        systemPrompt = "system",
        runtimeInstruction = null,
        provider = AiProvider.OPENROUTER,
        useZeroDataRetention = false,
    )

    @Test fun openRouterClient_isAnSttEngine() {
        assertTrue(newClient() is SttEngine)
    }

    @Test fun openRouterClient_isATextFixEngine() {
        assertTrue(newClient() is TextFixEngine)
    }

    @Test fun bothEnginesAreCancellable() {
        val client = newClient()
        assertTrue(client is Cancellable)
        // No exception on cancel before any request has been issued.
        client.cancel()
    }
}
