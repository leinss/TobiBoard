// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * A backend that rewrites or polishes text per the configured system prompt.
 *
 * One instance per request. Implementations must be callable from a background
 * thread.
 */
internal interface TextFixEngine : Cancellable {
    fun fixText(userText: String): String
}
