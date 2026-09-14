// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/**
 * Picks the consent wording that matches the provider actually selected.
 *
 * The cloud strings describe an HTTPS upload paid for with an API key the user supplies. That is
 * false for [AiProvider.LOCAL], which is the default: nothing is uploaded and no key exists. Every
 * dialog that asks the user to agree to voice input or text fix resolves its body text here, so the
 * two cannot drift apart.
 *
 * Pure and unit-tested ([helium314.keyboard.latin.voice.ConsentCopyTest]).
 */
object ConsentCopy {

    /** Body of the microphone-permission rationale. */
    @StringRes
    fun micRationale(provider: AiProvider): Int =
        if (provider.isCloud) R.string.voice_mic_rationale_message
        else R.string.voice_mic_rationale_message_local

    /** Body of the "Enable Voice Input?" confirmation. */
    @StringRes
    fun voiceEnable(provider: AiProvider): Int =
        if (provider.isCloud) R.string.voice_enable_privacy_message
        else R.string.voice_enable_privacy_message_local

    /** Body of the "Enable Text Fix?" confirmation. */
    @StringRes
    fun textFixEnable(provider: AiProvider): Int =
        if (provider.isCloud) R.string.text_fix_enable_privacy_message
        else R.string.text_fix_enable_privacy_message_local
}
