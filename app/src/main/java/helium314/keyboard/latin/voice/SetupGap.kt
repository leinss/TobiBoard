// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/**
 * A setup step that is missing, so voice input or text fix cannot run.
 *
 * Each case pairs the sentence the user is shown with the settings screen that fixes it. Before
 * this existed both managers reacted to a missing precondition by hiding the keyboard and opening
 * settings with no explanation, which is indistinguishable from a crash: the strings below already
 * existed and were referenced by nothing.
 *
 * Pure, and unit-tested in `SetupGapTest`.
 */
enum class SetupGap(
    @param:StringRes val messageResId: Int,
    val settingsDestination: String,
) {
    VOICE_DISABLED(R.string.voice_error_not_enabled, VoiceInputManager.SETTINGS_VOICE),
    VOICE_NO_SECURE_STORAGE(R.string.voice_error_secure_storage_unavailable, VoiceInputManager.SETTINGS_VOICE),
    VOICE_NO_API_KEY(R.string.voice_error_no_api_key, VoiceInputManager.SETTINGS_VOICE),
    VOICE_MODEL_NOT_DOWNLOADED(R.string.voice_error_local_not_ready, VoiceInputManager.SETTINGS_LOCAL_MODELS),
    VOICE_STT_NEEDS_OPENROUTER(R.string.voice_error_stt_openrouter_only, VoiceInputManager.SETTINGS_VOICE),

    TEXT_FIX_DISABLED(R.string.text_fix_error_not_enabled, TextFixManager.SETTINGS_TEXT_FIX),
    TEXT_FIX_NO_SECURE_STORAGE(R.string.voice_error_secure_storage_unavailable, TextFixManager.SETTINGS_TEXT_FIX),
    TEXT_FIX_NO_API_KEY(R.string.voice_error_no_api_key, TextFixManager.SETTINGS_TEXT_FIX),

    /** Takes the model's display name as a format argument, so the Qwen default is not called Gemma. */
    TEXT_FIX_MODEL_NOT_DOWNLOADED(R.string.text_fix_error_local_not_ready, TextFixManager.SETTINGS_LOCAL_MODELS),
    TEXT_FIX_NO_MODEL_SELECTED(R.string.voice_error_no_model, TextFixManager.SETTINGS_TEXT_FIX),
}
