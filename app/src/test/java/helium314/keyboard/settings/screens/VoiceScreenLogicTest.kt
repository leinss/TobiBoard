// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.supportsTextFixSlug
import helium314.keyboard.latin.voice.supportsVoiceSlug
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceScreenLogicTest {
    @Test
    fun voiceItemsHideConfigurationWhenVoiceInputIsDisabled() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = false,
            voiceModel = "mistralai/voxtral-small-24b-2507",
        )

        assertTrue(Settings.PREF_VOICE_INPUT_ENABLED in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_VOICE_MODEL in items)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in items)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in items)
        assertFalse(Settings.PREF_VOICE_EXPECTED_LANGUAGES in items)
    }

    @Test
    fun voiceItemsShowCustomModelOnlyWhenSelected() {
        val regularModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
        )
        val customModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "custom",
        )

        assertTrue(Settings.PREF_OPENROUTER_API_KEY in regularModelItems)
        assertTrue(Settings.PREF_OPENROUTER_ZDR_ENABLED in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in regularModelItems)
        assertTrue(Settings.PREF_VOICE_EXPECTED_LANGUAGES in regularModelItems)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL_CUSTOM in customModelItems)
    }

    @Test
    fun voiceItemsUsePayPerQKeyAndHideOpenRouterZdrWhenPayPerQSelected() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "nova-3",
            provider = AiProvider.PAYPERQ,
        )

        assertTrue(Settings.PREF_AI_PROVIDER in items)
        assertTrue(Settings.PREF_PAYPERQ_API_KEY in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_OPENROUTER_ZDR_ENABLED in items)
    }

    @Test
    fun defaultModelsAreValidSlugsForBothProviders() {
        // Guard against the regression where Defaults.PREF_VOICE_MODEL was a text-fix slug
        // that the voice picker didn't actually offer, leaving fresh installs and the
        // provider-switch fallback writing a value the rest of the app considered unsupported.
        for (provider in AiProvider.values()) {
            assertTrue(
                provider.supportsVoiceSlug(Defaults.PREF_VOICE_MODEL),
                "Defaults.PREF_VOICE_MODEL must be a voice slug supported by $provider"
            )
            assertTrue(
                provider.supportsTextFixSlug(Defaults.PREF_TEXT_FIX_MODEL),
                "Defaults.PREF_TEXT_FIX_MODEL must be a text-fix slug supported by $provider"
            )
        }
    }

    @Test
    fun voiceItemsShowAutoStopDurationOnlyWhenEnabled() {
        val autoStopOffItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            voiceAutoStop = false,
        )
        val autoStopOnItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            voiceAutoStop = true,
        )

        assertFalse(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOffItems)
        assertTrue(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOnItems)
    }
}
