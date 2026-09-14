// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File

/**
 * Holds the single voice clip kept after a failed transcription so the user can tap Retry instead of
 * losing the dictation. Before this existed the transcription job deleted the WAV in a `finally`
 * whether it had succeeded or not, so any failure cost the user their recording.
 *
 * The policy this encodes, and why each part matters:
 *  - **At most one clip.** [retain] deletes whatever it replaces, so a run of failures cannot pile
 *    up recordings in the cache directory.
 *  - **Deleted as soon as it is useless.** [consume] hands ownership to a retry (which deletes it on
 *    success), and [purge] is called on a successful transcription, a new recording, a cancel, and
 *    manager teardown. Audio is the most sensitive thing the app touches, so it does not linger.
 *  - **[isRetained] guards the orphan sweep**, which deletes cache recordings by age and would
 *    otherwise pull the clip out from under a Retry the user is about to tap.
 *
 * Pure (java.io only) so the lifecycle can be unit-tested against real temp files without the
 * recording or coroutine machinery. Expiry is driven externally: [VoiceInputManager] arms a Handler
 * that calls [purge] after its retention window. Not thread-safe; callers synchronize.
 */
class RetryAudioRetention {

    private var file: File? = null
    private var useDedicatedStt: Boolean = false

    /** The retained clip, or null when nothing is held or it vanished from disk. */
    val retainedFile: File?
        get() = file?.takeIf { it.exists() }

    /** True when a Retry would have audio to work with. */
    fun hasRetainable(): Boolean = retainedFile != null

    /** True when [candidate] is the clip being held, so age-based sweeps must skip it. */
    fun isRetained(candidate: File): Boolean = file == candidate

    /**
     * Keeps [wavFile] for one Retry, deleting any previously retained clip first. A non-existent
     * file is ignored so a failure that never produced audio does not arm an empty retry.
     */
    fun retain(wavFile: File, useDedicatedStt: Boolean) {
        if (!wavFile.exists()) return
        file?.takeIf { it != wavFile && it.exists() }?.delete()
        file = wavFile
        this.useDedicatedStt = useDedicatedStt
    }

    /**
     * Hands the clip to a retry attempt and stops tracking it, WITHOUT deleting: the caller now owns
     * the file and will either delete it on success or hand it back via [retain] on another failure.
     * Returns null when there is nothing to retry.
     */
    fun consume(): Consumed? {
        val f = retainedFile ?: run { clear(); return null }
        val consumed = Consumed(f, useDedicatedStt)
        clear()
        return consumed
    }

    /** Deletes the retained clip now. Safe to call when nothing is held. */
    fun purge() {
        file?.takeIf { it.exists() }?.delete()
        clear()
    }

    private fun clear() {
        file = null
        useDedicatedStt = false
    }

    /** A clip handed back for one more attempt, with the STT mode the original attempt used. */
    data class Consumed(val file: File, val useDedicatedStt: Boolean)
}
