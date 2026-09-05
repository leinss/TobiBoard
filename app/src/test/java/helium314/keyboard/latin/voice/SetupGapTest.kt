// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A missing setup step must name itself. Before this existed, both managers reacted by hiding the
 * keyboard and opening settings with no message, which reads as a crash.
 */
class SetupGapTest {

    @Test
    fun everyGapCarriesAMessage() {
        for (gap in SetupGap.entries) {
            assertTrue(gap.messageResId != 0, "$gap has no message")
        }
    }

    @Test
    fun modelGapsRouteToTheModelsScreenAndTheRestToTheirFeature() {
        assertEquals(VoiceInputManager.SETTINGS_LOCAL_MODELS, SetupGap.VOICE_MODEL_NOT_DOWNLOADED.settingsDestination)
        assertEquals(TextFixManager.SETTINGS_LOCAL_MODELS, SetupGap.TEXT_FIX_MODEL_NOT_DOWNLOADED.settingsDestination)
        assertEquals(VoiceInputManager.SETTINGS_VOICE, SetupGap.VOICE_NO_API_KEY.settingsDestination)
        assertEquals(TextFixManager.SETTINGS_TEXT_FIX, SetupGap.TEXT_FIX_DISABLED.settingsDestination)
    }

    @Test
    fun voiceAndTextFixDoNotShareADisabledMessage() {
        assertTrue(SetupGap.VOICE_DISABLED.messageResId != SetupGap.TEXT_FIX_DISABLED.messageResId)
    }
}
