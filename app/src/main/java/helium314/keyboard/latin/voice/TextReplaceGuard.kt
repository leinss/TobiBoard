// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Shared "is it still safe to mutate the field?" decision for the two AI field-mutation guards in
 * [helium314.keyboard.latin.LatinIME]:
 *  - `commitTextFixReplacement` — before replacing the field/selection with the model's proposal.
 *  - `performUndo` — before deleting an AI insertion that the user asked to undo.
 *
 * Both re-read the live field after a model/UI round-trip and may only proceed if it is unchanged.
 * Between reading and acting the user can type, delete, or move the cursor, and the input connection
 * can go away (returning null). Applying a stale mutation would clobber the user's current text — the
 * exact bug class these guards exist to prevent.
 *
 * Pure (no Android dependencies) so it can be unit-tested directly; see TextReplaceGuardTest.
 */
object TextReplaceGuard {
    /**
     * @param expected the text we read earlier and intend to act on (never null at either call site —
     *                 both bail on a null pending value before reaching the guard).
     * @param live     the field content read back just now, or null if the input connection is gone.
     * @return true only if it is still safe to apply the mutation.
     */
    @JvmStatic
    fun liveTextStillMatches(expected: String, live: CharSequence?): Boolean =
        live != null && expected.contentEquals(live)
}
