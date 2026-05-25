// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File

/**
 * Any engine whose work can be aborted from another thread. The cancel call
 * must be safe to invoke at any point in the engine's lifecycle, including
 * before work has started and after it has completed.
 */
internal interface Cancellable {
    fun cancel()
}

/**
 * A backend that turns a recorded WAV into text.
 *
 * One instance per recording. The caller owns the WAV file and must delete it.
 * Implementations must be callable from a background thread.
 */
internal interface SttEngine : Cancellable {
    fun transcribe(audioFile: File): String
}
