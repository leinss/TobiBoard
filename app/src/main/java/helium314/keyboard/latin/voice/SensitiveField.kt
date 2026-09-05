// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * The one place that decides whether the focused field is too sensitive for an AI feature to touch.
 *
 * Text fix and voice input both mutate or read the field and both send its content to a model, so
 * they must refuse in the same places. They used not to: text fix checked the field, voice did not,
 * and voice recorded inside password fields. Every caller now routes through [isSensitive] so the
 * two cannot drift apart again:
 *  - [TextFixManager.getBlockedErrorResId] before starting a fix,
 *  - [VoiceInputManager.startRecording] before opening the microphone,
 *  - the long-press-Return action popup, which drops the voice entries so the mic is not offered.
 *
 * Pure (no Android object graph, only constants) so it is unit-tested directly; see
 * SensitiveFieldTest.
 */
object SensitiveField {
    /**
     * @param inputType             `EditorInfo.inputType`, after any app workaround adjustment.
     * @param isPasswordField       `InputAttributes.mIsPasswordField`.
     * @param noLearning            `InputAttributes.mNoLearning`.
     * @param incognitoModeEnabled  `SettingsValues.mIncognitoModeEnabled` (the user's own toggle,
     *                              which is a deliberate "do not process this" signal).
     * @param imeOptions            `EditorInfo.imeOptions`. Redundant with [noLearning] from API 26
     *                              on, and the only source of the flag below it.
     */
    @JvmStatic
    fun isSensitive(
        inputType: Int,
        isPasswordField: Boolean,
        noLearning: Boolean,
        incognitoModeEnabled: Boolean,
        imeOptions: Int,
    ): Boolean {
        if (isPasswordField || noLearning || incognitoModeEnabled) return true
        if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        // A numeric PIN field is not caught by isPasswordField on every framework version, so check
        // the variation directly.
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
