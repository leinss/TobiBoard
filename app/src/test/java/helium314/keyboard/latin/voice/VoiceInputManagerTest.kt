// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aLocalModelStillLoadingIsAlsoSparedFromLifecycleCancel() {
        // PREPARING is the on-device load that precedes the decode. Cancelling it would throw away
        // the utterance for the same reason TRANSCRIBING is spared, and the load does not stop anyway.
        assertTrue(
            VoiceInputManager.letLocalTranscriptionFinish(
                VoiceInputManager.State.PREPARING,
                AiProvider.LOCAL,
            )
        )
    }

    @Test
    fun onDeviceTranscriptionStartsInPreparingAndCloudDoesNot() {
        assertEquals(
            VoiceInputManager.State.PREPARING,
            VoiceInputManager.initialTranscribingState(AiProvider.LOCAL),
        )
        assertEquals(
            VoiceInputManager.State.TRANSCRIBING,
            VoiceInputManager.initialTranscribingState(AiProvider.OPENROUTER),
        )
    }

    @Test
    fun theOnDeviceTranscriptionPathHasACeiling() {
        assertTrue(VoiceInputManager.LOCAL_TIMEOUT_MS > 0L)
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

    // --- transcriptionMayCommit: an on-device decode outlives the input view (above), so the
    // result must be checked against the editor it was started in before it is typed anywhere.

    @Test
    fun resultCommitsWhenTheUserIsStillInTheSameField() {
        assertTrue(VoiceInputManager.transcriptionMayCommit("com.example/17/1", "com.example/17/1"))
    }

    @Test
    fun resultIsDroppedWhenTheUserMovedToAnotherField() {
        assertFalse(VoiceInputManager.transcriptionMayCommit("com.example/17/1", "com.example/42/1"))
    }

    @Test
    fun resultIsDroppedWhenTheUserMovedToAnotherApp() {
        assertFalse(VoiceInputManager.transcriptionMayCommit("com.example/17/1", "com.other/17/1"))
    }

    @Test
    fun resultIsDroppedWhenTheFieldTypeChanged() {
        // Same view id reused for a password box: the input type is what separates them.
        assertFalse(VoiceInputManager.transcriptionMayCommit("com.example/17/1", "com.example/17/129"))
    }

    @Test
    fun resultCommitsWhenNoEditorIsFocused() {
        // The ordinary end of an input session, which letLocalTranscriptionFinish exists to let a
        // decode outlive. Only a DIFFERENT editor blocks the commit; with no editor at all the
        // commit is a no-op anyway, and dropping here would undo that feature.
        assertTrue(VoiceInputManager.transcriptionMayCommit("com.example/17/1", null))
    }

    @Test
    fun resultCommitsWhenTheStartingEditorWasUnknown() {
        // Nothing to compare against, so do not throw away a legitimate transcription.
        assertTrue(VoiceInputManager.transcriptionMayCommit(null, "com.example/17/1"))
        assertTrue(VoiceInputManager.transcriptionMayCommit(null, null))
    }
}
