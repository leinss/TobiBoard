// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the lifecycle-cancel decision that fixes the "accept does nothing" bug: when the IME window
 * hides or the input view finishes, an in-progress *on-device* transcription must be allowed to
 * finish and commit rather than being silently dropped, while live recording and cloud uploads are
 * still cancelled. The predicate is pure so it is tested without the recording/coroutine machinery
 * (which is flaky under Robolectric — see the InputLogicTest looper caveat).
 */
class VoiceInputManagerTest {

    @Test
    fun localTranscriptionInProgressIsSparedFromLifecycleCancel() {
        assertTrue(
            VoiceInputManager.letLocalTranscriptionFinish(
                VoiceInputManager.State.TRANSCRIBING,
                AiProvider.LOCAL,
            )
        )
    }

    @Test
    fun cloudTranscriptionIsStillCancelledOnLifecycleEvents() {
        // Cloud uploads keep the cancel-on-dismiss behaviour: no stale field insert seconds later.
        assertFalse(
            VoiceInputManager.letLocalTranscriptionFinish(
                VoiceInputManager.State.TRANSCRIBING,
                AiProvider.OPENROUTER,
            )
        )
        assertFalse(
            VoiceInputManager.letLocalTranscriptionFinish(
                VoiceInputManager.State.TRANSCRIBING,
                AiProvider.PAYPERQ,
            )
        )
    }

    @Test
    fun recordingOrIdleIsNeverSpared() {
        // Only an in-flight transcription is worth preserving; a live recording or idle manager is
        // cancelled normally when the keyboard goes away.
        assertFalse(
            VoiceInputManager.letLocalTranscriptionFinish(VoiceInputManager.State.RECORDING, AiProvider.LOCAL)
        )
        assertFalse(
            VoiceInputManager.letLocalTranscriptionFinish(VoiceInputManager.State.IDLE, AiProvider.LOCAL)
        )
    }

    @Test
    fun unknownProviderIsNotSpared() {
        assertFalse(
            VoiceInputManager.letLocalTranscriptionFinish(VoiceInputManager.State.TRANSCRIBING, null)
        )
    }
}
