// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The three rules an on-device request follows, shared by [TextFixManager] and [VoiceInputManager].
 *
 * Both managers grew the same preparing/timeout machinery independently and it drifted: the voice
 * timeout was missing the `cancel()` its text-fix twin had, so a timed-out decode went on to run
 * against a request the user had already been told had failed. The rules live here once instead.
 *
 * The two state enums are unrelated types, so the state helpers take the two states as arguments
 * rather than naming them.
 */
internal object LocalModelRequest {

    /**
     * Ceiling on one on-device request, model load included. It is a UI contract, not a real
     * cancel: the MediaPipe generate and the sherpa decode are native calls that ignore interrupts,
     * so this releases the user, not the CPU.
     */
    const val LOCAL_TIMEOUT_MS = 180_000L

    /**
     * The state a request starts in. Only the on-device provider has a model to load, so only it
     * gets the preparing state; a cloud request goes straight to the running one.
     *
     * Pure, so it is unit-tested.
     */
    fun <S> initialState(provider: AiProvider, preparing: S, running: S): S =
        if (provider == AiProvider.LOCAL) preparing else running

    /**
     * True when the engine's "model is loaded" callback still applies: it arrives from a background
     * thread, so a newer request may have started, and a cancel may have moved the state on.
     *
     * Pure, so it is unit-tested.
     */
    fun <S> shouldMarkRunning(activeToken: Long, requestToken: Long, state: S, preparing: S): Boolean =
        activeToken == requestToken && state == preparing

    /**
     * Runs [block] under [timeoutMs] when [isLocal], and cancels [engine] if it expires.
     *
     * The cancel is here rather than at the call site because that is the part that drifted: it
     * sets the engine's cancelled flag so a load that is still blocking does not go on to start
     * work for a request the user has already been told timed out. The
     * [TimeoutCancellationException] is rethrown, so each manager still writes its own message.
     */
    suspend fun <T> withLocalTimeout(
        isLocal: Boolean,
        engine: Cancellable,
        timeoutMs: Long = LOCAL_TIMEOUT_MS,
        block: suspend () -> T,
    ): T {
        if (!isLocal) return block()
        try {
            return withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "On-device request timed out after $timeoutMs ms")
            engine.cancel()
            throw e
        }
    }

    private const val TAG = "LocalModelRequest"
}
