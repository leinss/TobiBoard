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
import helium314.keyboard.settings.WizardStepResolver.WELCOME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression + path coverage for the wizard step state machine.
 *
 * The headline bug ([resumeDuringLocalModelDownloadKeepsTheUserOnTheModelStep]): with the default
 * LOCAL provider the model download is large enough that users background the app to wait; on
 * resume the wizard used to recompute its step from coarse system flags and snap them back to the
 * provider step, discarding their in-flow progress.
 */
class WizardStepResolverTest {

    // ---- seed(): the one-time initial determination, no prior position ----

    @Test
    fun seedSendsFreshInstallToWelcomeWhenImeNotEnabled() {
        assertEquals(
            WELCOME,
            WizardStepResolver.seed(imeEnabled = false, imeCurrent = false, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun seedSendsEnabledButUnselectedImeToSwitch() {
        assertEquals(
            SWITCH,
            WizardStepResolver.seed(imeEnabled = true, imeCurrent = false, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun seedEntersProviderFlowWhenImeReadyAndAiUnconfigured() {
        assertEquals(
            PROVIDER,
            WizardStepResolver.seed(imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun seedJumpsToDoneWhenAiAlreadyConfigured() {
        assertEquals(
            DONE,
            WizardStepResolver.seed(imeEnabled = true, imeCurrent = true, aiReady = true, aiSkipped = false)
        )
    }

    @Test
    fun seedJumpsToDoneWhenAiWasSkipped() {
        assertEquals(
            DONE,
            WizardStepResolver.seed(imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = true)
        )
    }

    @Test
    fun seedNeverLandsOnAnInFlowStep() {
        // seed has no manual position to honor, so it must only ever return a gating step,
        // PROVIDER, or DONE — never one of the in-flow steps 4-7.
        for (enabled in listOf(false, true))
            for (current in listOf(false, true))
                for (ready in listOf(false, true))
                    for (skipped in listOf(false, true)) {
                        val seeded = WizardStepResolver.seed(enabled, current, ready, skipped)
                        assertTrue(
                            seeded in setOf(WELCOME, SWITCH, PROVIDER, DONE),
                            "seed($enabled,$current,$ready,$skipped) returned in-flow step $seeded"
                        )
                    }
    }

    // ---- reconcile(): gating regressions always win ----

    @Test
    fun reconcileRegressesToWelcomeWhenImeGetsDisabledMidFlow() {
        // User was deep in the AI flow, then revoked the IME in system settings.
        assertEquals(
            WELCOME,
            WizardStepResolver.reconcile(VOICE, imeEnabled = false, imeCurrent = false, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun reconcileRegressesToSwitchWhenImeNoLongerSelectedMidFlow() {
        assertEquals(
            SWITCH,
            WizardStepResolver.reconcile(MODEL, imeEnabled = true, imeCurrent = false, aiReady = false, aiSkipped = false)
        )
    }

    // ---- reconcile(): the regression — in-flow position is preserved on resume ----

    @Test
    fun resumeDuringLocalModelDownloadKeepsTheUserOnTheModelStep() {
        // The exact reported bug: LOCAL provider, model still downloading (not ready), voice not yet
        // enabled. A resume must NOT bounce the user back to PROVIDER.
        assertEquals(
            MODEL,
            WizardStepResolver.reconcile(MODEL, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnProviderStepIsPreserved() {
        // PROVIDER is the boundary just above LAST_GATING_STEP; an off-by-one in the cutoff would
        // re-derive it on every resume. Pin it explicitly.
        assertEquals(
            PROVIDER,
            WizardStepResolver.reconcile(PROVIDER, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnDoneStepIsPreservedEvenWhenAiBecameUnready() {
        // Reaching DONE then losing a precondition (e.g. mic permission revoked) keeps the user on
        // DONE rather than bouncing them back into the AI sub-flow — the in-flow position wins.
        assertEquals(
            DONE,
            WizardStepResolver.reconcile(DONE, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun gatingRegressionWinsOverAiReadiness() {
        // The IME guards must take precedence over the AI-ready short-circuit: a disabled IME sends
        // the user to WELCOME even if AI is fully configured.
        assertEquals(
            WELCOME,
            WizardStepResolver.reconcile(VOICE, imeEnabled = false, imeCurrent = false, aiReady = true, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnApiKeyStepIsPreserved() {
        assertEquals(
            API_KEY,
            WizardStepResolver.reconcile(API_KEY, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnLanguageStepIsPreserved() {
        assertEquals(
            LANGUAGE,
            WizardStepResolver.reconcile(LANGUAGE, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnVoiceStepIsPreserved() {
        assertEquals(
            VOICE,
            WizardStepResolver.reconcile(VOICE, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun resumeOnDoneStepIsPreserved() {
        assertEquals(
            DONE,
            WizardStepResolver.reconcile(DONE, imeEnabled = true, imeCurrent = true, aiReady = true, aiSkipped = false)
        )
    }

    @Test
    fun reconcileDoesNotAutoAdvanceWhenAiBecomesReadyMidFlow() {
        // Even if the provider becomes fully configured while the user is parked on the model step,
        // we keep them where they are rather than teleporting them forward.
        assertEquals(
            MODEL,
            WizardStepResolver.reconcile(MODEL, imeEnabled = true, imeCurrent = true, aiReady = true, aiSkipped = false)
        )
    }

    // ---- reconcile(): advancing out of the gating steps once the IME is set up ----

    @Test
    fun reconcileAdvancesFromSwitchIntoProviderOnceImeSelected() {
        assertEquals(
            PROVIDER,
            WizardStepResolver.reconcile(SWITCH, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    @Test
    fun reconcileAdvancesFromSwitchStraightToDoneWhenAiAlreadyReady() {
        assertEquals(
            DONE,
            WizardStepResolver.reconcile(SWITCH, imeEnabled = true, imeCurrent = true, aiReady = true, aiSkipped = false)
        )
    }

    @Test
    fun reconcileAdvancesFromEnableToProviderWhenImeFullySetUp() {
        assertEquals(
            PROVIDER,
            WizardStepResolver.reconcile(ENABLE, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        )
    }

    // ---- full-path scenarios across multiple resume events ("more options selected") ----

    @Test
    fun localProviderPathSurvivesRepeatedBackgrounding() {
        // 1. Fresh enabled IME, nothing configured → enter provider choice.
        var step = WizardStepResolver.seed(imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        assertEquals(PROVIDER, step)
        // 2. User picks LOCAL; the composable moves to the model step. Background + resume mid-download.
        step = MODEL
        step = resume(step, aiReady = false)
        assertEquals(MODEL, step, "resume mid-download must stay on MODEL")
        // 3. Download finishes (model ready) but voice still off → resume keeps MODEL until user continues.
        step = resume(step, aiReady = false)
        assertEquals(MODEL, step)
        // 4. User continues to language, then voice; background between each.
        step = LANGUAGE
        step = resume(step, aiReady = false)
        assertEquals(LANGUAGE, step)
        step = VOICE
        step = resume(step, aiReady = false)
        assertEquals(VOICE, step)
        // 5. User enables voice; everything ready; composable lands on DONE; resume holds.
        step = DONE
        step = resume(step, aiReady = true)
        assertEquals(DONE, step)
    }

    @Test
    fun cloudProviderPathPreservesApiKeyEntryAcrossResume() {
        var step = WizardStepResolver.seed(imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = false)
        assertEquals(PROVIDER, step)
        // User picks OpenRouter/PayPerQ; composable routes to the API-key step. Leaving the app to
        // copy a key from a browser and returning must not reset the flow.
        step = API_KEY
        step = resume(step, aiReady = false)
        assertEquals(API_KEY, step, "leaving to fetch an API key must not reset the wizard")
        step = LANGUAGE
        step = resume(step, aiReady = false)
        assertEquals(LANGUAGE, step)
    }

    @Test
    fun skipPathLandsOnDoneAndStaysThere() {
        // User skips AI setup entirely; the composable sets aiSkipped and jumps to DONE. Any later
        // resume keeps DONE rather than re-entering the provider flow.
        var step = DONE
        step = WizardStepResolver.reconcile(step, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = true)
        assertEquals(DONE, step)
        // And a resume from a gating step with skip set short-circuits to DONE too.
        assertEquals(
            DONE,
            WizardStepResolver.reconcile(SWITCH, imeEnabled = true, imeCurrent = true, aiReady = false, aiSkipped = true)
        )
    }

    /** Helper: a resume event with the IME fully set up, AI not skipped. */
    private fun resume(step: Int, aiReady: Boolean): Int =
        WizardStepResolver.reconcile(step, imeEnabled = true, imeCurrent = true, aiReady = aiReady, aiSkipped = false)
}
