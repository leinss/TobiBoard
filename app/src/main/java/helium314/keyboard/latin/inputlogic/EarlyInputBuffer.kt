// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

/**
 * Bounded FIFO for keystrokes that arrive while the input connection is not ready — the
 * resetCaches-failed window right after onStartInputView, during which committing is a silent
 * no-op that loses the character (the "first few keystrokes don't respond" bug).
 *
 * [helium314.keyboard.latin.LatinIME] holds an `EarlyInputBuffer<Event>`, fills it from `onEvent()`
 * when `mConnection.isConnected()` is false, and [drain]s it back through `onEvent()` once the
 * connection is re-established (see `LatinIME.replayPendingInputs()`).
 *
 * Pure (no Android dependencies) so the buffering / replay-ordering policy can be unit-tested
 * directly; see `EarlyInputBufferTest`. Not thread-safe: all input dispatch is on the IME main
 * thread.
 *
 * @param capacity the maximum number of events held before the oldest is dropped (see [add]).
 */
class EarlyInputBuffer<T>(private val capacity: Int) {
    init { require(capacity > 0) { "capacity must be > 0, was $capacity" } }

    private val items = ArrayDeque<T>()

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    /**
     * Buffer [item]. When already at [capacity] the OLDEST item is dropped first, so the most
     * recent intent always survives — a leading character matters less than the one just pressed.
     * (Flip to `removeLast()` / don't-add for a drop-newest policy.)
     */
    fun add(item: T) {
        while (items.size >= capacity) items.removeFirst()
        items.addLast(item)
    }

    /** Discard everything without returning it (e.g. the keyboard is hiding, can't replay). */
    fun clear() = items.clear()

    /**
     * Remove and return every buffered item in the order it was added (oldest first), leaving the
     * buffer empty. This is the seam the replay is built on.
     *
     * Two properties EarlyInputBufferTest pins, and why each matters:
     *  1. OLDEST-FIRST order — replay must reproduce the user's typing order ("h","i","!"). The
     *     deque is filled with addLast(), so its natural iteration order is already oldest-first.
     *  2. SNAPSHOT then empty BEFORE returning. Replay re-dispatches each event through onEvent(),
     *     which re-enters add() if the connection drops again mid-replay; toList() detaches a copy
     *     so those re-buffered events land in the now-empty deque for the next flush instead of
     *     being lost or looping over a live collection.
     */
    fun drain(): List<T> {
        val snapshot = items.toList()
        items.clear()
        return snapshot
    }
}
