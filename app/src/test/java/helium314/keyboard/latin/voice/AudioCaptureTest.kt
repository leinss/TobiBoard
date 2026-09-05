// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioRecord
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions [AudioRecorder] makes about a capture, driven without a microphone: what counts as
 * a truncated recording, what the WAV header claims about the bytes behind it, and when a file is
 * too short to be worth keeping.
 *
 * A truncated capture is the dangerous one. The WAV is still finalized, so a transcript of a
 * recording that lost its tail reads exactly like a complete one, and the caller has nothing but
 * this flag to go on.
 */
class AudioCaptureTest {

    /**
     * A loop fed by [reads] (one entry per `read()` call, negative values are AudioRecord errors)
     * and a clock that advances [msPerRead] on every read.
     */
    private class Harness(
        private val reads: List<Int>,
        val maxDurationMs: Long = 90_000L,
        val autoStopSilenceMs: Long = 0L,
        val inputGain: Float = 1f,
        private val msPerRead: Long = 10L,
        private val sampleValue: (Int) -> Short = { 1000 },
        private val failWriteAt: Int = -1,
    ) {
        var now = 0L
            private set
        var readCount = 0
            private set
        val written = mutableListOf<Int>()
        val amplitudes = mutableListOf<Double>()
        var stopRequested = false

        private fun fill(buf: ByteArray, bytes: Int) {
            var i = 0
            while (i < bytes - 1) {
                val v = sampleValue(readCount).toInt()
                buf[i] = (v and 0xff).toByte()
                buf[i + 1] = ((v shr 8) and 0xff).toByte()
                i += 2
            }
        }

        val loop = AudioCaptureLoop(
            maxDurationMs = maxDurationMs,
            autoStopSilenceMs = autoStopSilenceMs,
            inputGain = inputGain,
            elapsedMs = { now },
            read = { buf ->
                val value = reads.getOrElse(readCount) { 0 }
                if (value > 0) fill(buf, value)
                readCount++
                now += msPerRead
                if (readCount >= reads.size) stopRequested = true
                value
            },
            write = { _, len ->
                if (written.size == failWriteAt) throw IOException("no space left on device")
                written.add(len)
            },
            onChunk = { bytes, amplitude ->
                amplitudes.add(amplitude)
                check(bytes > 0)
            },
        )

        fun run(bufferSize: Int = 320): CaptureEnd = runBlocking {
            loop.run(ByteArray(bufferSize)) { !stopRequested }
        }
    }

    @Test
    fun aStopRequestEndsTheCaptureNormally() {
        val h = Harness(reads = listOf(320, 320, 320))
        assertEquals(CaptureEnd.STOPPED, h.run())
        assertEquals(listOf(320, 320, 320), h.written)
    }

    @Test
    fun aWriteFailureTruncatesTheCapture() {
        // The disk filled up mid-recording. Everything after this point is missing from the WAV.
        val h = Harness(reads = listOf(320, 320, 320), failWriteAt = 1)
        assertEquals(CaptureEnd.TRUNCATED, h.run())
        assertEquals(listOf(320), h.written, "the loop must stop at the first failed write")
    }

    @Test
    fun aDeadAudioRecordTruncatesTheCapture() {
        // The mic was taken away mid-recording, e.g. a phone call or another app grabbing it.
        val h = Harness(reads = listOf(320, AudioRecord.ERROR_DEAD_OBJECT, 320))
        assertEquals(CaptureEnd.TRUNCATED, h.run())
        assertEquals(listOf(320), h.written)
    }

    @Test
    fun anyOtherReadErrorTruncatesTheCapture() {
        val h = Harness(reads = listOf(320, AudioRecord.ERROR_INVALID_OPERATION))
        assertEquals(CaptureEnd.TRUNCATED, h.run())
    }

    @Test
    fun anEmptyReadIsNotAnError() {
        // read() returning 0 is a normal "no frames ready yet", not a failure.
        val h = Harness(reads = listOf(0, 0, 320))
        assertEquals(CaptureEnd.STOPPED, h.run())
        assertEquals(listOf(320), h.written)
        assertEquals(1, h.amplitudes.size, "an empty read must not be counted as a chunk")
    }

    @Test
    fun theCaptureStopsItselfAtTheDurationCeiling() {
        val h = Harness(reads = List(100) { 320 }, maxDurationMs = 50L, msPerRead = 10L)
        assertEquals(CaptureEnd.MAX_DURATION, h.run())
        // The check runs before each read, so the ceiling is crossed rather than anticipated.
        assertEquals(6, h.readCount)
    }

    @Test
    fun silenceAfterSpeechStopsTheCapture() {
        val h = Harness(
            reads = List(20) { 320 },
            autoStopSilenceMs = 30L,
            msPerRead = 10L,
            // Loud for two chunks, then below the speech threshold for the rest.
            sampleValue = { i -> if (i < 2) 5000 else 10 },
        )
        assertEquals(CaptureEnd.SILENCE, h.run())
        assertTrue(h.readCount < 20, "the loop should have stopped long before the reads ran out")
    }

    @Test
    fun silenceBeforeAnySpeechNeverStopsTheCapture() {
        // Someone opens the mic and thinks about what to say. Stopping here would cut them off
        // before they started.
        val h = Harness(
            reads = List(10) { 320 },
            autoStopSilenceMs = 30L,
            msPerRead = 10L,
            sampleValue = { 10 },
        )
        assertEquals(CaptureEnd.STOPPED, h.run())
        assertEquals(10, h.readCount)
    }

    @Test
    fun silenceIsIgnoredWhenTheAutoStopIsOff() {
        val h = Harness(
            reads = List(10) { 320 },
            autoStopSilenceMs = 0L,
            sampleValue = { i -> if (i < 2) 5000 else 10 },
        )
        assertEquals(CaptureEnd.STOPPED, h.run())
        assertEquals(10, h.readCount)
    }

    @Test
    fun theWavHeaderMatchesTheCanonicalRiffHeaderByteForByte() {
        // 32000 bytes of mono 16-bit PCM at 16 kHz: exactly one second.
        val expected = hex(
            "52494646" + "247d0000" + "57415645" + // "RIFF", 32036, "WAVE"
                "666d7420" + "10000000" + "0100" + "0100" + // "fmt ", 16, PCM, mono
                "803e0000" + "007d0000" + "0200" + "1000" + // 16000 Hz, 32000 B/s, align 2, 16 bit
                "64617461" + "007d0000" // "data", 32000
        )
        assertContentEquals(expected, wavHeaderBytes(32_000))
    }

    @Test
    fun theHeaderIsAlwaysTheFortyFourBytesTheRecorderReservesUpFront() {
        assertEquals(WAV_HEADER_SIZE, wavHeaderBytes(0).size)
        assertEquals(WAV_HEADER_SIZE, wavHeaderBytes(1_000_000).size)
    }

    @Test
    fun theHeaderReportsTheDataSizeAndTheRiffSizeThirtySixBytesAbove() {
        val header = ByteBuffer.wrap(wavHeaderBytes(12_345)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12_345 + 36, header.getInt(4))
        assertEquals(12_345, header.getInt(40))
    }

    @Test
    fun durationComesFromTheByteCountAtTwoBytesPerSample() {
        assertEquals(1_000L, pcmDurationMs(32_000L))
        assertEquals(500L, pcmDurationMs(16_000L))
        assertEquals(0L, pcmDurationMs(0L))
    }

    @Test
    fun aFileWithLessThanOneWholeSampleHasNoDurationAndNoUsableAudio() {
        assertFalse(hasUsableAudio(0L))
        assertFalse(hasUsableAudio(1L))
        assertTrue(hasUsableAudio(2L))
        assertEquals(0L, pcmDurationMs(1L))
    }

    @Test
    fun meanAmplitudeOfNothingIsZeroRatherThanADivisionByZero() {
        assertEquals(0.0, meanAmplitude(sum = 0L, count = 0L), EPSILON)
        assertEquals(0.0, meanAmplitude(sum = 5_000L, count = 0L), EPSILON)
        assertEquals(2.5, meanAmplitude(sum = 10L, count = 4L), EPSILON)
    }

    @Test
    fun amplitudeIsTheMeanOfTheAbsoluteSampleValues() {
        val buf = pcm(shortArrayOf(-100, 100, -300, 300))
        assertEquals(200.0, chunkMeanAmplitude(buf, buf.size), EPSILON)
        assertEquals(0.0, chunkMeanAmplitude(buf, 1), EPSILON, "half a sample is no sample")
    }

    @Test
    fun gainBoostsQuietSamplesAndClipsAtThePcmRange() {
        val buf = pcm(shortArrayOf(1_000, -1_000, 20_000, -20_000))
        applyGain(buf, buf.size, 2f)
        assertContentEquals(shortArrayOf(2_000, -2_000, 32_767, -32_768), samples(buf))
    }

    private fun pcm(values: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putShort(it) }
        return buf.array()
    }

    private fun samples(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.getShort(it * 2) }
    }

    private companion object {
        const val EPSILON = 1e-9
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
