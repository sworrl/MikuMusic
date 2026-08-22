package com.miku.player.bpm

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real tempo detection for the BPM engine — the previous "engine" never analyzed audio at all
 * (it read a video-framerate metadata key, then keyword-guessed from the title), which is why
 * everything reported the 120 BPM default.
 *
 * Pipeline (classic energy-flux autocorrelation, all on-device, no network):
 *  1. Decode ~22s of PCM from a third of the way into the file (MediaExtractor + MediaCodec —
 *     works for every format the device can play: FLAC/MP3/AAC/WAV/ALAC…).
 *  2. Downmix to mono and reduce to an energy envelope at ~86 Hz (hop = 512 frames @44.1k).
 *  3. Onset signal = half-wave-rectified first difference of the envelope (emphasises beats).
 *  4. Autocorrelate the onset signal across lags spanning 70–185 BPM; strongest lag wins, with
 *     a bias against half/double-tempo aliases by checking harmonic support.
 *
 * Runtime is dominated by the decode (~1-2s for 22s of FLAC on the M500) — called off the main
 * thread and cached per-track by the engine, so each file is analyzed at most once.
 */
object MikuBpmAnalyzer {
    private const val TAG = "MikuBpmAnalyzer"
    private const val ANALYZE_SECONDS = 22
    private const val HOP = 512

    /** Returns detected BPM in [70,185], or null when the file can't be decoded/analyzed. */
    fun analyze(path: String): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIdx = i; format = f; break
                }
            }
            if (trackIdx < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIdx)

            // Start a third of the way in — skips intros, lands in the body of the song.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 3_000_000L) {
                extractor.seekTo(durationUs / 3, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false

            val envelope = ArrayList<Float>(4096)
            var acc = 0f
            var accN = 0
            var totalFrames = 0L
            var wantedFrames = (ANALYZE_SECONDS.toLong() * sampleRate)
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var spins = 0

            while (!sawOutputEOS && spins < 20_000) {
                spins++
                if (!sawInputEOS) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || totalFrames >= wantedFrames) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        val out = codec.getOutputBuffer(outIx)
                        if (out != null && info.size > 0) {
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset)
                            if (pcmFloat) {
                                val fb = out.asFloatBuffer()
                                val frames = info.size / 4 / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += abs(fb.get(f * channels + c))
                                    acc += s / channels
                                    if (++accN == HOP) { envelope.add(acc / HOP); acc = 0f; accN = 0 }
                                }
                                totalFrames += frames
                            } else {
                                val sb = out.asShortBuffer()
                                val frames = info.size / 2 / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += abs(sb.get(f * channels + c).toFloat() / 32768f)
                                    acc += s / channels
                                    if (++accN == HOP) { envelope.add(acc / HOP); acc = 0f; accN = 0 }
                                }
                                totalFrames += frames
                            }
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (totalFrames >= wantedFrames) sawOutputEOS = true
                    }
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        wantedFrames = (ANALYZE_SECONDS.toLong() * sampleRate)
                    }
                }
            }
            return detectFromEnvelope(envelope, sampleRate.toFloat() / HOP)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed for $path: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Envelope → onset flux → autocorrelation peak in the 70–185 BPM window. */
    private fun detectFromEnvelope(env: List<Float>, envRate: Float): Float? {
        if (env.size < envRate * 8) return null   // need ≥ ~8s of envelope

        // Onset flux: rectified difference, mean-removed.
        val flux = FloatArray(env.size - 1)
        for (i in 1 until env.size) flux[i - 1] = max(0f, env[i] - env[i - 1])
        val mean = flux.average().toFloat()
        for (i in flux.indices) flux[i] -= mean

        val minLag = (envRate * 60f / 185f).roundToInt().coerceAtLeast(1)   // fastest tempo
        val maxLag = (envRate * 60f / 70f).roundToInt().coerceAtMost(flux.size / 2) // slowest
        if (maxLag <= minLag) return null

        // Normalized autocorrelation across the tempo window.
        val corr = FloatArray(maxLag + 1)
        var energy = 0f
        for (v in flux) energy += v * v
        if (energy <= 0f) return null
        for (lag in minLag..maxLag) {
            var s = 0f
            for (i in 0 until flux.size - lag) s += flux[i] * flux[i + lag]
            corr[lag] = s / energy
        }

        // Peak pick with harmonic support: a true beat lag is reinforced at 2x its lag.
        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            var score = corr[lag]
            val dbl = lag * 2
            if (dbl <= maxLag) score += 0.5f * corr[dbl]
            val half = lag / 2
            if (half in minLag..maxLag) score += 0.25f * corr[half]
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag <= 0) return null

        // Parabolic refinement around the peak for sub-lag precision.
        val l = bestLag
        val refined = if (l in (minLag + 1) until maxLag) {
            val y0 = corr[l - 1]; val y1 = corr[l]; val y2 = corr[l + 1]
            val denom = (y0 - 2 * y1 + y2)
            if (abs(denom) > 1e-9f) l + 0.5f * (y0 - y2) / denom else l.toFloat()
        } else l.toFloat()

        val bpm = 60f * envRate / refined
        return bpm.coerceIn(70f, 185f)
    }
}
