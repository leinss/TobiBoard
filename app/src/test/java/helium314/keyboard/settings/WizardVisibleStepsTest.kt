// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.settings.WizardStepResolver.API_KEY
import helium314.keyboard.settings.WizardStepResolver.DONE
import helium314.keyboard.settings.WizardStepResolver.ENABLE
import helium314.keyboard.settings.WizardStepResolver.LANGUAGE
import helium314.keyboard.settings.WizardStepResolver.MODEL
import helium314.keyboard.settings.WizardStepResolver.PROVIDER
import helium314.keyboard.settings.WizardStepResolver.SWITCH
import helium314.keyboard.settings.WizardStepResolver.VOICE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The indicator used to draw a static 1..8 row, so it skipped a number in the middle: with the
 * on-device provider the API-key step never runs and the row jumped from 6 to 8. It now numbers
 * [wizardVisibleSteps] consecutively, so these two lists are what the user sees.
 */
class WizardVisibleStepsTest {

    @Test
    fun onDeviceProviderGetsTheModelDownloadAndNoKeyStep() {
        val steps = wizardVisibleSteps(providerIsCloud = false)
        assertEquals(listOf(ENABLE, SWITCH, PROVIDER, MODEL, LANGUAGE, VOICE, DONE), steps)
        assertFalse(API_KEY in steps)
    }

    @Test
    fun cloudProviderGetsTheKeyStepAndNoModelDownload() {
        val steps = wizardVisibleSteps(providerIsCloud = true)
        assertEquals(listOf(ENABLE, SWITCH, PROVIDER, API_KEY, LANGUAGE, VOICE, DONE), steps)
        assertFalse(MODEL in steps)
    }

    @Test
    fun bothBranchesHaveTheSameLength() {
        assertEquals(wizardVisibleSteps(false).size, wizardVisibleSteps(true).size)
    }

    @Test
    fun everyStepTheWizardCanShowIsNumbered() {
        // Any step the resolver can land on inside the flow must have a number, otherwise the
        // indicator highlights nothing while that step is on screen.
        val onDevice = wizardVisibleSteps(providerIsCloud = false)
        val cloud = wizardVisibleSteps(providerIsCloud = true)
        listOf(ENABLE, SWITCH, PROVIDER, LANGUAGE, VOICE, DONE).forEach {
            assertEquals(true, it in onDevice && it in cloud)
        }
    }
}
