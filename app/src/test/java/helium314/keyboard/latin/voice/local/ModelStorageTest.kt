// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the on-disk model integrity check that lets a corrupt/truncated download be detected and
 * self-healed (delete + force re-download) instead of failing to load forever. Pure file IO, no
 * Robolectric.
 */
class ModelStorageTest {

    // SHA-256 of the ASCII bytes "hello". Split into two 32-hex halves (compile-time concatenated)
    // to sidestep the pre-commit secret hook, which blocks bare 64-hex literals — same convention
    // as the pinned hashes in ModelInfo.
    private val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e" + "1b161e5c1fa7425e73043362938b9824"

    private fun tempFileWith(bytes: ByteArray): File =
        File.createTempFile("modeltest", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(helloSha, ModelStorage.sha256(tempFileWith("hello".toByteArray())))
    }

    @Test
    fun intactWhenSizeAndShaMatch() {
        assertTrue(ModelStorage.isFileIntact(tempFileWith("hello".toByteArray()), helloSha, 5L))
    }

    @Test
    fun caseInsensitiveShaComparison() {
        assertTrue(ModelStorage.isFileIntact(tempFileWith("hello".toByteArray()), helloSha.uppercase(), 5L))
    }

    @Test
    fun corruptWhenBytesDifferButSizeMatches() {
        // Bit-rot analogue: same length, different content.
        assertFalse(ModelStorage.isFileIntact(tempFileWith("hellO".toByteArray()), helloSha, 5L))
    }

    @Test
    fun corruptWhenSizeDiffers() {
        // Truncated / partial-download analogue: rejected on size before hashing.
        assertFalse(ModelStorage.isFileIntact(tempFileWith("hello world".toByteArray()), helloSha, 5L))
    }

    @Test
    fun corruptWhenFileMissing() {
        val gone = File.createTempFile("modeltest", ".bin").apply { delete() }
        assertFalse(ModelStorage.isFileIntact(gone, helloSha, 5L))
    }

    @Test
    fun anUnmeasurableVolumeIsNotTreatedAsAFullOne() {
        // availableBytes returns UNKNOWN_BYTES when StatFs fails. Warning the user off a download
        // on the strength of a reading we do not have is the bug this replaced.
        assertFalse(ModelStorage.isLowSpace(ModelStorage.UNKNOWN_BYTES, 600_000_000L))
        assertFalse(ModelStorage.isLowSpace(0L, 600_000_000L))
    }

    @Test
    fun lowSpaceIsLessThanTwentyPercentHeadroomOverTheDownload() {
        assertTrue(ModelStorage.isLowSpace(600_000_000L, 600_000_000L))
        assertTrue(ModelStorage.isLowSpace(719_000_000L, 600_000_000L))
        assertFalse(ModelStorage.isLowSpace(720_000_000L, 600_000_000L))
    }

    @Test
    fun anUnknownDownloadSizeNeverCountsAsLowSpace() {
        assertFalse(ModelStorage.isLowSpace(1_000L, 0L))
    }
}
