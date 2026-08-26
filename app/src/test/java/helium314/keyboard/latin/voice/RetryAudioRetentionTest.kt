// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the retention policy behind the "spoke for 30 seconds, tapped accept, it crashed and the
 * audio was gone" report. Two properties are in tension and both are load-bearing: a failed clip has
 * to SURVIVE so Retry can use it, and it has to be DELETED as soon as it stops being useful, because
 * it is recorded speech sitting in a cache directory.
 */
class RetryAudioRetentionTest {

    private lateinit var dir: File
    private val retention = RetryAudioRetention()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("retry-audio").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun clip(name: String): File = File(dir, name).apply { writeText("fake wav") }

    @Test
    fun retainedClipSurvivesSoRetryHasSomethingToWorkWith() {
        val wav = clip("rec_1.wav")

        retention.retain(wav, useDedicatedStt = false)

        assertTrue(wav.exists())
        assertTrue(retention.hasRetainable())
        assertEquals(wav, retention.retainedFile)
    }

    @Test
    fun retainingASecondClipDeletesTheFirst() {
        // A run of failures must not pile up recordings in the cache directory.
        val first = clip("rec_1.wav")
        val second = clip("rec_2.wav")

        retention.retain(first, useDedicatedStt = false)
        retention.retain(second, useDedicatedStt = false)

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(second, retention.retainedFile)
    }

    @Test
    fun purgeDeletesTheClipAndForgetsIt() {
        val wav = clip("rec_1.wav")
        retention.retain(wav, useDedicatedStt = false)

        retention.purge()

        assertFalse(wav.exists())
        assertFalse(retention.hasRetainable())
        assertNull(retention.retainedFile)
    }

    @Test
    fun purgeOnAnEmptyRetentionIsHarmless() {
        retention.purge()
        retention.purge()

        assertFalse(retention.hasRetainable())
    }

    @Test
    fun consumeHandsOverTheFileWithoutDeletingIt() {
        // The retry attempt takes ownership: it deletes on success, or re-retains on another
        // failure. Deleting here would destroy the very audio the retry is about to transcribe.
        val wav = clip("rec_1.wav")
        retention.retain(wav, useDedicatedStt = true)

        val consumed = retention.consume()

        assertEquals(wav, consumed?.file)
        assertEquals(true, consumed?.useDedicatedStt)
        assertTrue(wav.exists())
        assertFalse(retention.hasRetainable())
    }

    @Test
    fun consumeReturnsNullWhenNothingIsHeld() {
        assertNull(retention.consume())
    }

    @Test
    fun aClipDeletedUnderneathUsCountsAsGone() {
        // The orphan sweep, a cache clear, or the OS reclaiming cache space can remove the file
        // while we still hold the reference. Retry must not offer to replay a file that isn't there.
        val wav = clip("rec_1.wav")
        retention.retain(wav, useDedicatedStt = false)

        wav.delete()

        assertFalse(retention.hasRetainable())
        assertNull(retention.retainedFile)
        assertNull(retention.consume())
    }

    @Test
    fun retainingANonExistentFileArmsNothing() {
        retention.retain(File(dir, "never_written.wav"), useDedicatedStt = false)

        assertFalse(retention.hasRetainable())
    }

    @Test
    fun isRetainedShieldsOnlyTheHeldClipFromTheAgeSweep() {
        // VoiceInputManager.sweepOrphanRecordings deletes cache recordings by age; without this
        // exclusion it would delete the retained clip out from under a pending Retry.
        val retained = clip("rec_keep.wav")
        val other = clip("rec_other.wav")
        retention.retain(retained, useDedicatedStt = false)

        assertTrue(retention.isRetained(retained))
        assertFalse(retention.isRetained(other))
    }

    @Test
    fun isRetainedIsFalseOnceConsumed() {
        // After consume() the transcription path owns the file and deletes it on success, so the
        // sweep no longer needs to shield it.
        val wav = clip("rec_1.wav")
        retention.retain(wav, useDedicatedStt = false)
        retention.consume()

        assertFalse(retention.isRetained(wav))
    }

    @Test
    fun sttModeSurvivesTheRoundTripSoRetryUsesTheSameEngine() {
        val wav = clip("rec_1.wav")
        retention.retain(wav, useDedicatedStt = true)

        assertEquals(true, retention.consume()?.useDedicatedStt)

        val second = clip("rec_2.wav")
        retention.retain(second, useDedicatedStt = false)
        assertEquals(false, retention.consume()?.useDedicatedStt)
    }
}
