// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UsageTrackerTest {

    @Before
    fun reset() = UsageTracker.reset()

    @Test
    fun recordsAccumulateAcrossRequests() {
        UsageTracker.record(100)
        UsageTracker.record(50)
        assertEquals(150L, UsageTracker.sessionTokens)
        assertEquals(2, UsageTracker.sessionRequests)
        assertEquals(50, UsageTracker.lastRequestTokens)
    }

    @Test
    fun ignoresZeroAndNegative() {
        UsageTracker.record(0)
        UsageTracker.record(-5)
        assertEquals(0L, UsageTracker.sessionTokens)
        assertEquals(0, UsageTracker.sessionRequests)
        assertEquals(0, UsageTracker.lastRequestTokens)
    }

    @Test
    fun resetClearsEverything() {
        UsageTracker.record(42)
        UsageTracker.reset()
        assertEquals(0L, UsageTracker.sessionTokens)
        assertEquals(0, UsageTracker.sessionRequests)
        assertEquals(0, UsageTracker.lastRequestTokens)
    }
}
