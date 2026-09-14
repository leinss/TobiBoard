// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EarlyInputBuffer] — the bounded FIFO behind LatinIME's early-keystroke replay (the
 * "first few keystrokes don't respond" fix). Pins the add/capacity (drop-oldest) and drain
 * (FIFO + snapshot-then-clear) behaviour the replay relies on, plus capacity validation.
 *
 * Pure (no Robolectric) — mirrors the TextReplaceGuardTest pattern for pure input helpers.
 */
class EarlyInputBufferTest {

    // ── add + capacity (drop-oldest) ──────────────────────────────────────────────────────────

    @Test fun addsUnderCapacityKeepsAll() {
        val b = EarlyInputBuffer<String>(3)
        b.add("a")
        b.add("b")
        assertEquals(2, b.size)
        assertFalse(b.isEmpty())
    }

    @Test fun freshBufferIsEmpty() {
        assertTrue(EarlyInputBuffer<String>(8).isEmpty())
    }

    @Test fun overCapacityKeepsOnlyCapacityItems() {
        val b = EarlyInputBuffer<String>(2)
        b.add("a")
        b.add("b")
        b.add("c") // "a" is the oldest and should be dropped
        assertEquals(2, b.size)
    }

    @Test fun clearDiscardsWithoutReturning() {
        val b = EarlyInputBuffer<String>(8)
        b.add("a")
        b.clear()
        assertTrue(b.isEmpty())
    }

    @Test fun capacityOneKeepsOnlyNewest() {
        val b = EarlyInputBuffer<String>(1)
        b.add("a")
        b.add("b")
        assertEquals(listOf("b"), b.drain())
    }

    @Test fun nonPositiveCapacityIsRejectedAtConstruction() {
        // A zero/negative cap would make add()'s drop loop call removeFirst() on an empty deque;
        // fail fast at the construction site instead of deferring a confusing crash to first use.
        assertThrows(IllegalArgumentException::class.java) { EarlyInputBuffer<String>(0) }
        assertThrows(IllegalArgumentException::class.java) { EarlyInputBuffer<String>(-1) }
    }

    // ── drain (FIFO order + empties + snapshot) ───────────────────────────────────────────────

    @Test fun drainReturnsItemsInFifoOrder() {
        val b = EarlyInputBuffer<String>(8)
        b.add("h")
        b.add("i")
        b.add("!")
        // Replay must reproduce the user's typing order, oldest first.
        assertEquals(listOf("h", "i", "!"), b.drain())
    }

    @Test fun overCapacityDropsOldestThenDrainsRest() {
        val b = EarlyInputBuffer<String>(2)
        b.add("a")
        b.add("b")
        b.add("c") // "a" dropped, "b","c" remain in order
        assertEquals(listOf("b", "c"), b.drain())
    }

    @Test fun drainEmptiesTheBuffer() {
        val b = EarlyInputBuffer<String>(8)
        b.add("x")
        b.drain()
        assertTrue(b.isEmpty())
        assertEquals(emptyList<String>(), b.drain())
    }

    @Test fun drainReturnsSnapshotSoReentrantAddSurvives() {
        // Simulates the connection dropping mid-replay: re-dispatch re-enters add(). The drained
        // snapshot must already be detached, and the re-added event must remain buffered for the
        // next flush — not appear in the current drain result, not be discarded.
        val b = EarlyInputBuffer<String>(8)
        b.add("a")
        b.add("b")
        val drained = b.drain()
        b.add("c") // re-buffered "mid-replay"
        assertEquals(listOf("a", "b"), drained)
        assertEquals(listOf("c"), b.drain())
    }
}
