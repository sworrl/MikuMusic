package com.miku.player.bpm

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Enhanced Real-Time BPM & Tempo Onset Analyzer with Multi-Band Transients and Octave Disambiguation.
 *
 * Pipeline:
 *  1. Decodes ~20s of PCM from the track without corrupt seek points.
 *  2. Dual-band envelope extraction:
 *     - Low-Band (IIR LPF ~180Hz): isolates kick drum & 808 sub-bass transients.
 *     - High-Band (IIR HPF ~250Hz): isolates snare, clap, hi-hat, synth & vocal onsets.
 *  3. Half-Wave Rectified Spectral Flux (Novelty Curve) with baseline suppression.
 *  4. Autocorrelation across lags spanning 50–300 BPM with:
 *     - Perceptual Log-Gaussian Tempo Prior (~140 BPM center) to prevent half-tempo drops.
 *     - Octave Harmonic Reinforcement (compares tau, tau/2, and 2*tau).
 *     - Parabolic peak interpolation for sub-frame BPM accuracy.
 */
object MikuBpmAnalyzer {
    private const val TAG = "MikuBpmAnalyzer"
    private const val ANALYZE_SECONDS = 22
    private const val HOP = 512

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

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var pcmFloat = false

            // Dual-band filter state: Low-pass (~180Hz) + High-pass (~250Hz)
            val rcLpf = 1.0f / (2.0f * Math.PI.toFloat() * 180.0f)
            val rcHpf = 1.0f / (2.0f * Math.PI.toFloat() * 250.0f)
            val dt = 1.0f / sampleRate.toFloat()
            val lpfAlpha = dt / (rcLpf + dt)
            val hpfAlpha = rcHpf / (rcHpf + dt)

            var lpfPrev = 0f
            var hpfPrevInput = 0f
            var hpfPrevOutput = 0f

            val envelope = ArrayList<Float>(4096)
            var accBass = 0f
            var accHigh = 0f
            var accN = 0
            var totalFrames = 0L
            var wantedFrames = (ANALYZE_SECONDS.toLong() * sampleRate)
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var spins = 0

            while (!sawOutputEOS && spins < 30_000) {
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
                                    for (c in 0 until channels) s += fb.get(f * channels + c)
                                    val mono = s / channels
                                    // Low band
                                    lpfPrev += lpfAlpha * (mono - lpfPrev)
                                    // High band
                                    val hpfOut = hpfAlpha * (hpfPrevOutput + mono - hpfPrevInput)
                                    hpfPrevInput = mono
                                    hpfPrevOutput = hpfOut

                                    accBass += abs(lpfPrev)
                                    accHigh += abs(hpfOut)

                                    if (++accN == HOP) {
                                        envelope.add((accBass * 1.3f + accHigh * 1.0f) / HOP)
                                        accBass = 0f; accHigh = 0f; accN = 0
                                    }
                                }
                                totalFrames += frames
                            } else {
                                val sb = out.asShortBuffer()
                                val frames = info.size / 2 / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get(f * channels + c).toFloat() / 32768f
                                    val mono = s / channels
                                    // Low band
                                    lpfPrev += lpfAlpha * (mono - lpfPrev)
                                    // High band
                                    val hpfOut = hpfAlpha * (hpfPrevOutput + mono - hpfPrevInput)
                                    hpfPrevInput = mono
                                    hpfPrevOutput = hpfOut

                                    accBass += abs(lpfPrev)
                                    accHigh += abs(hpfOut)

                                    if (++accN == HOP) {
                                        envelope.add((accBass * 1.3f + accHigh * 1.0f) / HOP)
                                        accBass = 0f; accHigh = 0f; accN = 0
                                    }
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

    private fun detectFromEnvelope(env: List<Float>, envRate: Float): Float? {
        if (env.size < envRate * 6) return null

        // 1. Compute half-wave rectified onset flux
        val flux = FloatArray(env.size - 1)
        for (i in 1 until env.size) flux[i - 1] = max(0f, env[i] - env[i - 1])
        val mean = flux.average().toFloat()
        for (i in flux.indices) flux[i] = max(0f, flux[i] - mean * 0.8f)

        // 2. Lag bounds covering 50 BPM to 290 BPM
        val minLag = (envRate * 60f / 290f).roundToInt().coerceAtLeast(1)
        val maxLag = (envRate * 60f / 50f).roundToInt().coerceAtMost(flux.size / 2)
        if (maxLag <= minLag) return null

        val corr = FloatArray(maxLag + 1)
        var energy = 0f
        for (v in flux) energy += v * v
        if (energy <= 0f) return null

        for (lag in minLag..maxLag) {
            var s = 0f
            for (i in 0 until flux.size - lag) s += flux[i] * flux[i + lag]
            corr[lag] = s / energy
        }

        // 3. Human tempo prior weighting (Log-Gaussian centered at 140 BPM with 1.3 octaves spread)
        val targetCenterBpm = 140.0
        val octaveSpread = 1.3
        fun tempoPrior(bpm: Float): Float {
            if (bpm <= 0f) return 0f
            val octDiff = log2(bpm.toDouble() / targetCenterBpm)
            return exp(-0.5 * (octDiff / octaveSpread).pow(2.0)).toFloat()
        }

        // 4. Harmonic candidate scoring with octave disambiguation
        var bestLag = -1
        var bestScore = 0f

        for (lag in minLag..maxLag) {
            val bpm = 60f * envRate / lag
            val prior = tempoPrior(bpm)
            var score = corr[lag] * prior

            val halfLag = (lag / 2f).roundToInt()
            if (halfLag >= minLag) {
                // If halfLag (double BPM, e.g. 170 vs 85) has a strong peak, reinforce it
                val halfCorr = corr[halfLag]
                if (halfCorr > 0.30f * corr[lag]) {
                    score += halfCorr * tempoPrior(bpm * 2f) * 0.85f
                }
            }

            val dblLag = lag * 2
            if (dblLag <= maxLag) {
                val dblCorr = corr[dblLag]
                score += dblCorr * tempoPrior(bpm / 2f) * 0.35f
            }

            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        if (bestLag <= 0) return null

        // 5. Check if the double-tempo peak (halfLag) is the true canonical pulse (e.g. 85 -> 170)
        var finalLag = bestLag
        val fastLag = (bestLag / 2f).roundToInt()
        if (fastLag >= minLag) {
            val fastBpm = 60f * envRate / fastLag
            val slowBpm = 60f * envRate / bestLag
            if (slowBpm < 105f && fastBpm <= 220f) {
                val fastCorr = corr[fastLag]
                val slowCorr = corr[bestLag]
                // If fast tempo has significant correlation or prior favor, promote to double-time
                if (fastCorr >= 0.35f * slowCorr || (tempoPrior(fastBpm) > 1.35f * tempoPrior(slowBpm) && fastCorr > 0.25f)) {
                    finalLag = fastLag
                }
            }
        }

        // 6. Sub-sample parabolic interpolation for precise fractional BPM
        val l = finalLag
        val refined = if (l in (minLag + 1) until maxLag) {
            val y0 = corr[l - 1]; val y1 = corr[l]; val y2 = corr[l + 1]
            val denom = (y0 - 2 * y1 + y2)
            if (abs(denom) > 1e-9f) l + 0.5f * (y0 - y2) / denom else l.toFloat()
        } else l.toFloat()

        val finalBpm = 60f * envRate / refined
        return finalBpm.coerceIn(40f, 320f)
    }
}
