// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-use on-device model load used to be indistinguishable from a warm run: one state, one
 * static "Fixing…" label. These pin the two decisions that separate them, plus the "the model gave
 * the text back unchanged" rule that used to reach the user as a Replace offer with an empty diff.
 */
class TextFixStateMachineTest {

    @Test
    fun onDeviceRequestsStartInPreparing() {
        assertEquals(TextFixManager.State.PREPARING, TextFixManager.initialWorkingState(AiProvider.LOCAL))
    }

    @Test
    fun cloudRequestsSkipPreparingBecauseThereIsNothingToLoad() {
        assertEquals(TextFixManager.State.WORKING, TextFixManager.initialWorkingState(AiProvider.OPENROUTER))
        assertEquals(TextFixManager.State.WORKING, TextFixManager.initialWorkingState(AiProvider.PAYPERQ))
    }

    @Test
    fun preparingAndWorkingAreDistinctStatesAndNeitherIsIdle() {
        val states = TextFixManager.State.entries.toSet()
        assertTrue(TextFixManager.State.PREPARING in states)
        assertTrue(TextFixManager.State.WORKING in states)
        assertEquals(3, states.size)
    }

    @Test
    fun theLocalPathHasACeiling() {
        assertTrue(TextFixManager.LOCAL_TIMEOUT_MS > 0L)
    }

    @Test
    fun anEchoedInputIsNoChange() {
        assertTrue(TextFixManager.isNoChange("the quick brown fox", "the quick brown fox"))
        assertTrue(TextFixManager.isNoChange("  hello  ", "hello"))
    }

    @Test
    fun aRealEditIsNotNoChange() {
        assertFalse(TextFixManager.isNoChange("teh quick brown fox", "the quick brown fox"))
        // Whitespace inside the text is a change: re-spacing is a fix the user asked for.
        assertFalse(TextFixManager.isNoChange("hello  world", "hello world"))
    }

    @Test
    fun anOutOfMemoryFailureHandsTheOnDeviceModelBack() {
        // The ~1 GB handle survives the failed request, so a retry would allocate against the same
        // held gigabyte. The wrapped form is what a native load failure actually arrives as.
        assertTrue(TextFixManager.shouldReleaseLocalModel(OutOfMemoryError()))
        assertTrue(TextFixManager.shouldReleaseLocalModel(RuntimeException(OutOfMemoryError())))
    }

    @Test
    fun anOrdinaryFailureKeepsTheOnDeviceModelLoaded() {
        assertFalse(TextFixManager.shouldReleaseLocalModel(java.io.IOException("no network")))
    }
}
