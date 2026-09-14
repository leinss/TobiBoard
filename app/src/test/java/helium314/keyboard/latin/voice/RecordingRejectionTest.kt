// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A recording the microphone or the disk cut short used to be transcribed as if it were whole, so
 * the user got a transcript missing the end of what they said and nothing said so.
 */
class RecordingRejectionTest {

    @Test
    fun aGoodRecordingIsAccepted() {
        assertNull(VoiceInputManager.rejectionFor(hasAudioFile = true, truncated = false, durationMs = 4_000L, meanAmplitude = 900.0))
    }

    @Test
    fun aTruncatedRecordingIsRefusedEvenWhenItLooksUsable() {
        assertEquals(
            VoiceInputManager.RecordingRejection.TRUNCATED,
            VoiceInputManager.rejectionFor(hasAudioFile = true, truncated = true, durationMs = 4_000L, meanAmplitude = 900.0),
        )
    }

    @Test
    fun missingAudioBeatsEverythingElse() {
        assertEquals(
            VoiceInputManager.RecordingRejection.NO_AUDIO,
            VoiceInputManager.rejectionFor(hasAudioFile = false, truncated = true, durationMs = 0L, meanAmplitude = 0.0),
        )
    }

    @Test
    fun silenceAndTooShortStillHaveTheirOwnMessages() {
        assertEquals(
            VoiceInputManager.RecordingRejection.TOO_SHORT,
            VoiceInputManager.rejectionFor(hasAudioFile = true, truncated = false, durationMs = 100L, meanAmplitude = 900.0),
        )
        assertEquals(
            VoiceInputManager.RecordingRejection.NO_SPEECH,
            VoiceInputManager.rejectionFor(hasAudioFile = true, truncated = false, durationMs = 20_000L, meanAmplitude = 2.76),
        )
    }

    @Test
    fun everyRejectionCarriesADistinctMessage() {
        val ids = VoiceInputManager.RecordingRejection.entries.map { it.messageResId }
        assertEquals(ids.size, ids.toSet().size)
    }
}
