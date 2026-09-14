// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioRecord
import helium314.keyboard.latin.utils.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The parts of [AudioRecorder] that decide things, with the microphone, the clock and the output
 * file behind function parameters. Split out because the decisions that matter (when a capture is
 * truncated, what the WAV header says, whether there is enough audio to keep) are otherwise only
 * reachable by opening a real microphone.
 */

internal const val WAV_HEADER_SIZE = 44
internal const val AUDIO_SAMPLE_RATE = 16000
private const val BYTES_PER_SAMPLE = 2
private const val TAG = "AudioCapture"

/** Why the capture loop ended. */
internal enum class CaptureEnd {
    /** The caller asked it to stop, or the coroutine was cancelled. */
    STOPPED,

    /** [AudioCaptureLoop.maxDurationMs] was reached. */
    MAX_DURATION,

    /** The silence auto-stop fired after the user had spoken. */
    SILENCE,

    /**
     * The microphone or the disk failed. The WAV is still finalized but is missing the tail of
     * what the user said, and a transcript of it is indistinguishable from a complete one, so the
     * caller must refuse to transcribe it.
     */
    TRUNCATED,
}

/**
 * Reads chunks from [read], writes them through [write], and decides when to stop.
 *
 * @param elapsedMs milliseconds since the recording started.
 * @param onChunk gets every chunk that reached disk, with its mean absolute amplitude (0..32767).
 */
internal class AudioCaptureLoop(
    private val maxDurationMs: Long,
    private val autoStopSilenceMs: Long,
    private val inputGain: Float,
    private val elapsedMs: () -> Long,
    private val read: (ByteArray) -> Int,
    private val write: (ByteArray, Int) -> Unit,
    private val onChunk: (bytes: Int, amplitude: Double) -> Unit,
) {
    suspend fun run(buffer: ByteArray, isRecording: () -> Boolean): CaptureEnd {
        var hasSpoken = false
        var silenceRunStartMs = 0L
        while (isRecording() && currentCoroutineContext().isActive) {
            if (elapsedMs() > maxDurationMs) return CaptureEnd.MAX_DURATION
            val bytes = read(buffer)
            when {
                bytes > 0 -> {
                    if (inputGain != 1f) applyGain(buffer, bytes, inputGain)
                    try {
                        write(buffer, bytes)
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to write PCM chunk", e)
                        return CaptureEnd.TRUNCATED
                    }
                    val amp = chunkMeanAmplitude(buffer, bytes)
                    onChunk(bytes, amp)
                    if (autoStopSilenceMs > 0L) {
                        val now = elapsedMs()
                        if (amp >= SPEECH_AMPLITUDE_THRESHOLD) {
                            hasSpoken = true
                            silenceRunStartMs = 0L
                        } else if (hasSpoken) {
                            if (silenceRunStartMs == 0L) silenceRunStartMs = now
                            else if (now - silenceRunStartMs >= autoStopSilenceMs) return CaptureEnd.SILENCE
                        }
                    }
                }
                bytes == 0 -> Unit
                bytes == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "AudioRecord dead object, recording aborted")
                    return CaptureEnd.TRUNCATED
                }
                else -> {
                    Log.e(TAG, "AudioRecord read error: $bytes")
                    return CaptureEnd.TRUNCATED
                }
            }
        }
        return CaptureEnd.STOPPED
    }

    companion object {
        /** Amplitude threshold (0..32767) separating silence from speech for the auto-stop. */
        const val SPEECH_AMPLITUDE_THRESHOLD = 300.0
    }
}

/**
 * The 44-byte canonical RIFF/WAVE header for [pcmSize] bytes of mono 16-bit PCM. Written twice per
 * recording: as a placeholder up front, and again once the sample count is known.
 */
internal fun wavHeaderBytes(pcmSize: Int, sampleRate: Int = AUDIO_SAMPLE_RATE): ByteArray {
    val byteRate = sampleRate * 1 * 16 / 8
    return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(pcmSize + 36)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sampleRate)
        putInt(byteRate)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(pcmSize)
    }.array()
}

/** Playback duration of [pcmBytes] bytes of mono 16-bit PCM. 0 when there is not one whole sample. */
internal fun pcmDurationMs(pcmBytes: Long, sampleRate: Int = AUDIO_SAMPLE_RATE): Long =
    if (hasUsableAudio(pcmBytes)) (pcmBytes * 1000L) / (sampleRate.toLong() * BYTES_PER_SAMPLE) else 0L

/**
 * False when the file holds less than one 16-bit sample. Such a file gets deleted rather than
 * finalized: a WAV with no samples is not something any engine can transcribe.
 */
internal fun hasUsableAudio(pcmBytes: Long): Boolean = pcmBytes >= BYTES_PER_SAMPLE

/** Mean of the per-chunk amplitudes accumulated during a recording, 0 when nothing was captured. */
internal fun meanAmplitude(sum: Long, count: Long): Double =
    if (count > 0) sum.toDouble() / count else 0.0

/** Scales each 16-bit little-endian sample in place by [gain], clipping to the PCM16 range. */
internal fun applyGain(buf: ByteArray, length: Int, gain: Float) {
    var i = 0
    val end = length - 1
    while (i < end) {
        val lo = buf[i].toInt() and 0xff
        val hi = buf[i + 1].toInt()
        val sample = ((hi shl 8) or lo).toShort().toInt()
        val boosted = (sample * gain).toInt().coerceIn(-32768, 32767)
        buf[i] = (boosted and 0xff).toByte()
        buf[i + 1] = ((boosted shr 8) and 0xff).toByte()
        i += 2
    }
}

/** Mean absolute sample amplitude (0..32767) of the first [length] bytes of [buf]. */
internal fun chunkMeanAmplitude(buf: ByteArray, length: Int): Double {
    if (length < BYTES_PER_SAMPLE) return 0.0
    var sum = 0L
    var count = 0
    var i = 0
    val end = length - 1
    while (i < end) {
        val lo = buf[i].toInt() and 0xff
        val hi = buf[i + 1].toInt()
        val signed = ((hi shl 8) or lo).toShort().toInt()
        sum += if (signed < 0) -signed else signed
        count++
        i += 2
    }
    return if (count > 0) sum.toDouble() / count else 0.0
}
