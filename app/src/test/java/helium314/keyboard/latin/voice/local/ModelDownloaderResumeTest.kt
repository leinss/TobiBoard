// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the downloader is allowed to believe about bytes it did not fetch in this run: a `.part`
 * file left on disk, and a server's claim that its 206 continues that file. Both used to be taken
 * on trust, so a `.part` from a different revision of the model was appended to and only rejected
 * by the SHA-256 check hundreds of megabytes later.
 */
class ModelDownloaderResumeTest {

    @Test
    fun aPartialFileIsResumedFromItsLength() {
        assertEquals(30_000L, resumeOffsetFor(partLength = 30_000L, expectedTotalBytes = 65_536L))
    }

    @Test
    fun nothingOnDiskMeansStartFromTheBeginning() {
        assertEquals(0L, resumeOffsetFor(partLength = 0L, expectedTotalBytes = 65_536L))
    }

    @Test
    fun aPartFileAsLongAsTheWholeFileIsNotAPrefixOfIt() {
        // It cannot be an unfinished copy of the pinned content, so it is a leftover from another
        // revision. Resuming would splice new bytes onto stale ones.
        assertEquals(0L, resumeOffsetFor(partLength = 65_536L, expectedTotalBytes = 65_536L))
        assertEquals(0L, resumeOffsetFor(partLength = 70_000L, expectedTotalBytes = 65_536L))
    }

    @Test
    fun anUnknownExpectedSizeIsNeverResumedFrom() {
        assertEquals(0L, resumeOffsetFor(partLength = 30_000L, expectedTotalBytes = 0L))
    }

    @Test
    fun aContentRangeThatContinuesOurPartIsAccepted() {
        assertTrue(contentRangeContinues("bytes 30000-65535/65536", 30_000L, 65_536L))
    }

    @Test
    fun aContentRangeStartingElsewhereIsRejected() {
        assertFalse(contentRangeContinues("bytes 0-65535/65536", 30_000L, 65_536L))
    }

    @Test
    fun aContentRangeForADifferentlySizedFileIsRejected() {
        // The file behind the URL changed: our bytes belong to the old one.
        assertFalse(contentRangeContinues("bytes 30000-99999/100000", 30_000L, 65_536L))
    }

    @Test
    fun aMissingOrUnparseableContentRangeIsNotAConfirmation() {
        assertFalse(contentRangeContinues(null, 30_000L, 65_536L))
        assertFalse(contentRangeContinues("", 30_000L, 65_536L))
        assertFalse(contentRangeContinues("bytes */65536", 30_000L, 65_536L))
        assertFalse(contentRangeContinues("items 30000-65535/65536", 30_000L, 65_536L))
    }
}
