// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the consent wording to the provider that will actually run. The cloud strings describe an
 * HTTPS upload paid for with an API key the user supplies; the default provider is on-device, where
 * neither happens, and every one of these dialogs used to show the cloud text unconditionally.
 */
class ConsentCopyTest {

    @Test
    fun onDeviceProviderNeverGetsTheUploadWording() {
        assertEquals(R.string.voice_mic_rationale_message_local, ConsentCopy.micRationale(AiProvider.LOCAL))
        assertEquals(R.string.voice_enable_privacy_message_local, ConsentCopy.voiceEnable(AiProvider.LOCAL))
        assertEquals(R.string.text_fix_enable_privacy_message_local, ConsentCopy.textFixEnable(AiProvider.LOCAL))
    }

    @Test
    fun cloudProvidersKeepTheUploadWording() {
        for (provider in listOf(AiProvider.OPENROUTER, AiProvider.PAYPERQ)) {
            assertEquals(R.string.voice_mic_rationale_message, ConsentCopy.micRationale(provider))
            assertEquals(R.string.voice_enable_privacy_message, ConsentCopy.voiceEnable(provider))
            assertEquals(R.string.text_fix_enable_privacy_message, ConsentCopy.textFixEnable(provider))
        }
    }

    @Test
    fun theTwoVariantsAreDifferentStrings() {
        assertNotEquals(
            ConsentCopy.micRationale(AiProvider.LOCAL),
            ConsentCopy.micRationale(AiProvider.OPENROUTER),
        )
    }
}
