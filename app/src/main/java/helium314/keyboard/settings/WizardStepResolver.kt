// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * Pure state machine for the [WelcomeWizard] step sequence.
 *
 * The wizard mixes two kinds of steps:
 *  - *gating* steps that are fully derived from live system state — enable the IME, switch to it;
 *  - an *in-flow* AI-setup sub-sequence the user advances by hand — pick provider, then either
 *    download the on-device model or enter a cloud API key, set expected languages, enable voice.
 *
 * Only the gating steps can be re-derived from system flags. The in-flow steps cannot: nothing in
 * the system tells you the user is "halfway through picking a language". So when the wizard
 * reconciles its position on every Activity `ON_RESUME` (to auto-advance after the user returns
 * from the system IME-settings screen), it must NOT recompute the in-flow position from scratch —
 * doing so snaps the user back to [PROVIDER] and discards their progress. The most visible victim
 * is the default LOCAL provider: the model download takes long enough that users background the app
 * to wait, and the resume would yank them out of the download step.
 *
 * [reconcile] therefore preserves the current in-flow position and only moves when the gating
 * state genuinely requires it. [seed] is the one-time initial determination with no prior position.
 */
object WizardStepResolver {
    const val WELCOME = 0
    const val ENABLE = 1
    const val SWITCH = 2
    const val PROVIDER = 3
    const val MODEL = 4    // LOCAL only: download the on-device STT model
    const val API_KEY = 5  // cloud only: enter the provider API key
    const val LANGUAGE = 6
    const val VOICE = 7
    const val DONE = 8

    /** Steps at or below this index are fully re-derivable from system state on every resume. */
    private const val LAST_GATING_STEP = SWITCH

    /**
     * Re-evaluate the wizard position against live system state without throwing away manual
     * in-flow progress.
     *
     * @param current the step currently shown to the user.
     * @param imeEnabled this IME is enabled in system settings.
     * @param imeCurrent this IME is the selected/default IME.
     * @param aiReady the chosen provider is fully configured (credential/model + voice enabled + mic).
     * @param aiSkipped the user chose to skip AI setup.
     */
    fun reconcile(
        current: Int,
        imeEnabled: Boolean,
        imeCurrent: Boolean,
        aiReady: Boolean,
        aiSkipped: Boolean,
    ): Int = when {
        // Gating regressions always win: if the user disabled/changed the IME, go back to fix it.
        !imeEnabled -> WELCOME
        !imeCurrent -> SWITCH
        // IME is enabled and selected. If we are still sitting on a gating step, advance into the
        // AI sub-flow now (or straight to DONE when AI is already configured or was skipped).
        current <= LAST_GATING_STEP -> if (aiReady || aiSkipped) DONE else PROVIDER
        // Already inside the AI sub-flow (or on DONE): keep the user's manual position. This is the
        // line that fixes the resume-during-download regression.
        else -> current
    }

    /**
     * Initial determination when the wizard first mounts and has no saved position. Equivalent to
     * reconciling from the very first gating step.
     */
    fun seed(
        imeEnabled: Boolean,
        imeCurrent: Boolean,
        aiReady: Boolean,
        aiSkipped: Boolean,
    ): Int = reconcile(WELCOME, imeEnabled, imeCurrent, aiReady, aiSkipped)
}
