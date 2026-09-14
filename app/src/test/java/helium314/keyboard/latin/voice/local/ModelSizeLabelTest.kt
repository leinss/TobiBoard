// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard used to divide by 1_048_576 and the models screen by 1_000_000, so the same Parakeet
 * download was announced as "~639 MB" on one screen and "670 MB" on the other. Both now read
 * [ModelInfo.sizeLabel], and this pins the number that label produces.
 */
class ModelSizeLabelTest {

    @Test
    fun parakeetIsTheSumOfTheFilesTheDownloaderVerifies() {
        assertEquals(670_478_772L, SttModelInfo.ParakeetTdt06b.totalBytes)
        assertEquals("670 MB", SttModelInfo.ParakeetTdt06b.sizeLabel)
    }

    @Test
    fun sizesUseDecimalUnits() {
        assertEquals("1 MB", formatModelSize(1_000_000L))
        assertEquals("547 MB", formatModelSize(546_660_344L))
        assertEquals("94 KB", formatModelSize(93_939L))
        // The decimal separator follows the device locale, so only the unit is pinned here.
        assertTrue(formatModelSize(1_597_913_616L)!!.endsWith(" GB"))
    }

    @Test
    fun unknownSizeHasNoLabel() {
        assertNull(formatModelSize(0L))
        assertNull(formatModelSize(-1L))
    }
}
