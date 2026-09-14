// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Process-wide, in-memory tally of token usage for voice / text-fix requests in the current session.
 *
 * Tokens are the honest unit to surface: the model catalog carries only coarse pricing *tiers*, not
 * per-token prices, so a real currency estimate would be invented. The IME service and the settings
 * Activity share one process, so this object is visible to both — settings reads it to show a meter.
 * Not persisted: a "session" is the lifetime of the app process; [reset] clears it on demand.
 */
internal object UsageTracker {
    @Volatile var sessionTokens: Long = 0L
        private set
    @Volatile var sessionRequests: Int = 0
        private set
    @Volatile var lastRequestTokens: Int = 0
        private set

    @Synchronized
    fun record(tokens: Int) {
        if (tokens <= 0) return
        lastRequestTokens = tokens
        sessionTokens += tokens
        sessionRequests += 1
    }

    @Synchronized
    fun reset() {
        sessionTokens = 0L
        sessionRequests = 0
        lastRequestTokens = 0
    }
}
