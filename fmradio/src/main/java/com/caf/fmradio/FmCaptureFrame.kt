package com.caf.fmradio

import android.util.Log

/**
 * The FM capture stream does not contain what it says it contains.
 *
 * `AudioRecord(RADIO_TUNER)` is opened as 48 kHz 16-bit stereo, the audio policy config declares
 * the FM Tuner device as exactly that (`AUDIO_FORMAT_PCM_16_BIT`, `samplingRates="48000"`), and
 * AudioFlinger's input thread agrees. Read as 16-bit PCM the result is full-scale noise: RMS
 * 0.41, peak 1.0, identical at every frequency, and unchanged when the tuner is muted at the
 * driver. That is not radio, and it is not silence either.
 *
 * Dumping the raw bytes shows why. The stream has a six-byte period, and at one fixed phase
 * every frame has the shape
 *
 *     [int16 sign][int16 sample][int16 marker]
 *
 * where the first short is exactly the sign extension of the second (10000 out of 10000 frames
 * in the captured sample), and the marker is 0x0000 or 0x8000 in an exact 50/50 split that
 * alternates with a duplicated sample. So each six bytes carry one 16-bit sample sitting in the
 * high half of a 32-bit word plus a channel marker, each sample appears twice, and the real
 * content is 16 kHz mono.
 *
 * Decoded that way the same capture is obviously audio: RMS 0.047 (−26.6 dBFS), crest factor
 * 3.7, mean sample-to-sample delta 0.24 of RMS. White noise sits near 1.1 on that last figure.
 *
 * Rather than hard-code a vendor quirk, [detectPhase] checks the sign-extension invariant on
 * every buffer and the decoder is only used when it holds. On a build where the capture really
 * is plain 16-bit PCM the invariant fails and [decode] passes the bytes straight through. That
 * way this file costs nothing if the HAL is ever fixed, and the spectrum stops presenting
 * mis-framed bytes as a measurement in the meantime.
 */
object FmCaptureFrame {

    private const val TAG = "FmCaptureFrame"

    /** Bytes per sample in the vendor framing. */
    const val FRAME_BYTES = 6

    /** Samples the vendor framing yields per second once the duplicate of each pair is dropped. */
    const val DECODED_RATE_HZ = 16000

    /** Phase meaning "no vendor framing here, treat the buffer as plain 16-bit PCM". */
    const val PHASE_NONE = -1

    private var loggedLayout = false

    private fun s16(b: ByteArray, i: Int): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        return if (v and 0x8000 != 0) v - 65536 else v
    }

    /**
     * Find the six-byte phase at which the sign-extension invariant holds, or [PHASE_NONE].
     *
     * Cheap enough for the audio thread: at most 6 x 256 pairs regardless of buffer size.
     */
    fun detectPhase(buf: ByteArray, bytes: Int): Int {
        if (bytes < FRAME_BYTES * 16) return PHASE_NONE
        val limit = minOf(bytes - 4, FRAME_BYTES * 256)
        for (phase in 0 until FRAME_BYTES) {
            var ok = 0
            var total = 0
            var i = phase
            while (i + 4 <= limit) {
                val sign = s16(buf, i)
                val value = s16(buf, i + 2)
                total++
                if ((sign == 0 && value >= 0) || (sign == -1 && value < 0)) ok++
                i += FRAME_BYTES
            }
            // A real 16-bit stream will satisfy this by chance for a few frames, never for all
            // of several hundred.
            if (total >= 32 && ok == total) {
                if (!loggedLayout) {
                    loggedLayout = true
                    Log.i(TAG, "vendor FM capture framing detected at phase $phase: " +
                        "6 bytes/sample, 16-bit payload in the high half, ${DECODED_RATE_HZ} Hz mono")
                }
                return phase
            }
        }
        return PHASE_NONE
    }

    /**
     * Decode [bytes] of [buf] into mono 16-bit samples in [out], returning how many were written.
     *
     * With [phase] == [PHASE_NONE] the buffer is copied through as interleaved stereo PCM folded
     * to mono, which is what the old code assumed all along.
     */
    fun decode(buf: ByteArray, bytes: Int, phase: Int, out: ShortArray): Int {
        if (phase == PHASE_NONE) {
            var n = 0
            var i = 0
            while (i + 4 <= bytes && n < out.size) {
                val l = s16(buf, i)
                val r = s16(buf, i + 2)
                out[n++] = ((l + r) shr 1).toShort()
                i += 4
            }
            return n
        }
        // Vendor framing: one sample per 6 bytes, each sample present twice (the pair's marker
        // distinguishes the copies), so only every second frame is kept.
        var n = 0
        var i = phase
        var keep = true
        while (i + 4 <= bytes && n < out.size) {
            if (keep) out[n++] = s16(buf, i + 2).toShort()
            keep = !keep
            i += FRAME_BYTES
        }
        return n
    }

    /** Sample rate of what [decode] produced, for anything that needs to know (the spectrum does). */
    fun decodedRate(phase: Int, nominalRate: Int): Int =
        if (phase == PHASE_NONE) nominalRate else DECODED_RATE_HZ
}
