package com.miku.player.bpm

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.*

/**
 * One tempo estimate with an HONEST confidence. Consumers must treat [isConfident] == false as
 * "no tempo known" and show "—" (never a placeholder number).
 */
data class TempoEstimate(
    /** Tempo in BPM (> 0). */
    val bpm: Float,
    /** 0..1. Agreement across analysis windows × strength of the periodicity. */
    val confidence: Float,
    /** Normalised autocorrelation at the chosen period (0..1) — how periodic the onsets are. */
    val periodicity: Float,
    val windowsAgreeing: Int,
    val windowsTotal: Int,
    /** Other tempo clusters some windows voted for (e.g. the octave), most-voted first. */
    val alternatives: List<Float>,
    val source: Source = Source.ANALYSIS
) {
    enum class Source { ANALYSIS, TAG, DICTIONARY, USER }

    val isConfident: Boolean get() = bpm > 0f && confidence >= MikuBpmAnalyzer.CONFIDENT_THRESHOLD
}

/**
 * Tempo analyzer with octave/harmonic disambiguation and multi-window consensus.
 *
 * Pipeline:
 *  1. Decode ~32 s of PCM, starting ~25 % into the track (skips quiet intros) when the
 *     container reports a duration and seeking works; otherwise from the start.
 *  2. Dual-band envelopes (IIR LPF ~180 Hz: kick/sub; IIR HPF ~250 Hz: snare/hats/synth/vox),
 *     summed per 256-sample hop (envelope rate ≈ 172 Hz at 44.1 kHz → ±1.7 % lag resolution
 *     at 170 BPM before parabolic refinement).
 *  3. Log-compressed, half-wave-rectified flux per band with a local-mean (adaptive) threshold.
 *  4. The span is cut into 8 s windows (4 s hop). Per window:
 *       - unbiased normalised autocorrelation r[lag] over 15–500 BPM (wide, so the harmonics
 *         and the half-period of every candidate exist);
 *       - candidate periods P in the reported range [MIN_BPM, MAX_BPM] = 60–240 BPM that are
 *         local maxima of r;
 *       - harmonic comb  comb(P) = r(P) + 0.5·r(2P) + 0.25·r(3P) + 0.25·r(4P)
 *         (a true beat period has peaks at ALL its multiples);
 *       - sub-harmonic penalty  −0.5·r(P/2)  (if the half period is just as periodic, P is
 *         a sub-harmonic of the real pulse — this is what makes 171 beat 85 for a clean
 *         350 ms onset train: comb(85)=comb(171)=2.0 but 85 is penalised by r(171)≈1);
 *       - inter-onset-interval evidence  ioi(P) = h(P) + 0.5·h(P/2) + 0.33·h(P/3) + 0.25·h(P/4)
 *         (fraction of consecutive onset gaps at P and its subdivisions — onsets every
 *         ~350 ms support 171, not 85, even when a backbeat snare makes r(2P) ≥ r(P));
 *       - a perceptual weight that is FLAT over 90–180 BPM and only rolls off gently
 *         outside it (log-Gaussian, 0.8 octave) — a tiebreak, never a cap;
 *       score(P) = (comb − 0.5·sub + 1.5·ioi) · weight, then parabolic refinement of the lag.
 *  5. Consensus: window votes are clustered (±4 %); the biggest cluster wins, its median is
 *     the tempo. Confidence = agreement (octave-related votes count half) × periodicity
 *     strength. Fewer than 3 usable windows or a low score → not confident → callers show "—".
 */
object MikuBpmAnalyzer {
    private const val TAG = "MikuBpmAnalyzer"

    /** Below this [TempoEstimate.confidence] the estimate must not be displayed. */
    const val CONFIDENT_THRESHOLD = 0.5f

    /** Reported tempo range. Fast genres (metal, D&B, hardcore, speedcore-lite) live at 160–220+. */
    const val MIN_BPM = 60f
    const val MAX_BPM = 240f

    // Autocorrelation is evaluated wider than the report range so every candidate's 2P/3P/4P
    // (down to 15 BPM for P = 60) and P/2 (up to 480 BPM for P = 240) exist.
    private const val CORR_MIN_BPM = 15f
    private const val CORR_MAX_BPM = 500f

    private const val ANALYZE_SECONDS = 32
    private const val WINDOW_SECONDS = 8f
    private const val WINDOW_HOP_SECONDS = 4f
    private const val HOP = 256
    private const val MIN_VALID_WINDOWS = 3
    private const val CLUSTER_TOL = 0.04f
    private const val PEAK_TOL = 0.03f
    private const val IOI_TOL = 0.08f

    /**
     * Backward-compatible entry point: the tempo ONLY when the analysis is confident, else null.
     * Never returns a guess.
     */
    fun analyze(path: String): Float? = analyzeDetailed(path)?.takeIf { it.isConfident }?.bpm

    /**
     * The file's own BPM tag (ID3 TBPM / Vorbis BPM / MP4 tmpo) via jaudiotagger — real metadata
     * written by whoever tagged the file, not a measurement. Null when absent or implausible.
     */
    fun readTaggedBpm(path: String): Float? = runCatching {
        val f = File(path)
        if (!f.isFile) return null
        val tag = org.jaudiotagger.audio.AudioFileIO.read(f).tag ?: return null
        val raw = tag.getFirst(org.jaudiotagger.tag.FieldKey.BPM)?.trim().orEmpty()
        val v = raw.replace(',', '.').toFloatOrNull() ?: return null
        if (v in 40f..300f) v else null
    }.getOrNull()

    /** Full analysis of the file. Null when it cannot be decoded or has no usable onsets. */
    fun analyzeDetailed(path: String): TempoEstimate? {
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

            // Skip the intro: start ~25 % in when the track is long enough for that plus the
            // analysis span. Best effort — a failed seek just analyses from the start.
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val spanUs = ANALYZE_SECONDS * 1_000_000L
            if (durUs > spanUs * 2) {
                val startUs = min(durUs / 4, durUs - spanUs - 2_000_000L).coerceAtLeast(0L)
                runCatching { extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var pcmFloat = false

            // Dual-band one-pole filters: low-pass ~180 Hz, high-pass ~250 Hz.
            var dt = 1.0f / sampleRate.toFloat()
            val rcLpf = 1.0f / (2.0f * Math.PI.toFloat() * 180.0f)
            val rcHpf = 1.0f / (2.0f * Math.PI.toFloat() * 250.0f)
            var lpfAlpha = dt / (rcLpf + dt)
            var hpfAlpha = rcHpf / (rcHpf + dt)

            var lpfPrev = 0f
            var hpfPrevInput = 0f
            var hpfPrevOutput = 0f

            val bassEnv = ArrayList<Float>(8192)
            val highEnv = ArrayList<Float>(8192)
            var accBass = 0f
            var accHigh = 0f
            var accN = 0
            var totalFrames = 0L
            var wantedFrames = ANALYZE_SECONDS.toLong() * sampleRate
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var spins = 0

            fun push(mono: Float) {
                lpfPrev += lpfAlpha * (mono - lpfPrev)
                val hpfOut = hpfAlpha * (hpfPrevOutput + mono - hpfPrevInput)
                hpfPrevInput = mono
                hpfPrevOutput = hpfOut
                accBass += abs(lpfPrev)
                accHigh += abs(hpfOut)
                if (++accN == HOP) {
                    bassEnv.add(accBass / HOP)
                    highEnv.add(accHigh / HOP)
                    accBass = 0f; accHigh = 0f; accN = 0
                }
            }

            while (!sawOutputEOS && spins < 40_000) {
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
                                    push(s / channels)
                                }
                                totalFrames += frames
                            } else {
                                val sb = out.asShortBuffer()
                                val frames = info.size / 2 / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get(f * channels + c).toFloat() / 32768f
                                    push(s / channels)
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
                        wantedFrames = ANALYZE_SECONDS.toLong() * sampleRate
                        dt = 1.0f / sampleRate.toFloat()
                        lpfAlpha = dt / (rcLpf + dt)
                        hpfAlpha = rcHpf / (rcHpf + dt)
                    }
                }
            }
            return estimateFromEnvelopes(bassEnv.toFloatArray(), highEnv.toFloatArray(), sampleRate.toFloat() / HOP)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed for $path: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pure DSP below — no Android dependencies, so it can be exercised with synthetic envelopes.
    // ---------------------------------------------------------------------------------------

    /** Log-compressed, half-wave-rectified flux of one band with a local-mean threshold. */
    private fun novelty(env: FloatArray, envRate: Float): FloatArray {
        val n = env.size
        val out = FloatArray(n)
        if (n < 2) return out
        var mean = 0.0
        for (v in env) mean += v
        mean /= n
        if (mean <= 1e-9) return out
        val g = (20.0 / mean).toFloat()
        var prev = ln(1f + g * env[0])
        for (i in 1 until n) {
            val cur = ln(1f + g * env[i])
            out[i] = max(0f, cur - prev)
            prev = cur
        }
        // Adaptive threshold: subtract the local mean over ±0.35 s so sustained loudness
        // ramps do not read as onsets.
        val w = max(1, (0.35f * envRate).toInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + out[i]
        val res = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - w); val b = min(n, i + w + 1)
            val local = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            res[i] = max(0f, out[i] - local)
        }
        return res
    }

    /** Public for tests: full consensus estimate from two band envelopes at [envRate] frames/s. */
    fun estimateFromEnvelopes(bassEnv: FloatArray, highEnv: FloatArray, envRate: Float): TempoEstimate? {
        val n = min(bassEnv.size, highEnv.size)
        if (n < (envRate * 6f).toInt()) return null
        val nb = novelty(bassEnv.copyOf(n), envRate)
        val nh = novelty(highEnv.copyOf(n), envRate)
        val nov = FloatArray(n) { nb[it] + nh[it] }
        return estimateFromNovelty(nov, envRate)
    }

    private class WindowVote(val bpm: Float, val score: Float, val periodicity: Float)

    /** Public for tests: consensus estimate from an onset-strength (novelty) curve. */
    fun estimateFromNovelty(nov: FloatArray, envRate: Float): TempoEstimate? {
        val n = nov.size
        val winLen = (WINDOW_SECONDS * envRate).toInt()
        val winHop = max(1, (WINDOW_HOP_SECONDS * envRate).toInt())
        if (n < winLen) return null

        val votes = ArrayList<WindowVote>()
        var start = 0
        while (start + winLen <= n) {
            analyzeWindow(nov, start, winLen, envRate)?.let { votes += it }
            start += winHop
        }
        if (votes.isEmpty()) return null
        return consensus(votes)
    }

    private fun lagFor(bpm: Float, envRate: Float): Int = (envRate * 60f / bpm).roundToInt()

    /** Max of r within ±[PEAK_TOL] of a (possibly fractional) lag; 0 when outside the array. */
    private fun peakNear(r: FloatArray, minLag: Int, maxLag: Int, center: Float): Float {
        val c = center.roundToInt()
        val tol = max(1, (center * PEAK_TOL).roundToInt())
        val a = max(minLag, c - tol); val b = min(maxLag, c + tol)
        if (a > b) return 0f
        var m = 0f
        for (l in a..b) if (r[l] > m) m = r[l]
        return m
    }

    /**
     * Perceptual weight centred on the ONSET-DENSITY-derived [centreBpm] rather than a fixed
     * 90-180 window. This is what stops a fast track losing to its own half-tempo: a dense onset
     * stream pushes the centre up (toward 185), so 175 BPM sits at full weight while its 87 BPM
     * sub-harmonic is rolled off - without ever hard-capping the search range.
     * Flat within ~1/3 octave of the centre, log-Gaussian (0.8 octave) beyond.
     */
    private fun perceptualWeight(bpm: Float, centreBpm: Float): Float {
        if (bpm <= 0f || centreBpm <= 0f) return 0f
        val lo = centreBpm / 1.26f
        val hi = centreBpm * 1.26f
        return when {
            bpm < lo -> exp(-0.5 * (log2(bpm / lo.toDouble()) / 0.8).pow(2.0)).toFloat()
            bpm > hi -> exp(-0.5 * (log2(bpm / hi.toDouble()) / 0.8).pow(2.0)).toFloat()
            else -> 1f
        }
    }

    /** Hermite smoothstep: 0 at/below [e0], 1 at/above [e1], smooth between. */
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x >= e1) 1f else 0f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Fraction of consecutive inter-onset intervals within ±[IOI_TOL] of [lag] frames. */
    private fun ioiMass(iois: IntArray, lag: Float): Float {
        if (iois.isEmpty() || lag <= 0f) return 0f
        val lo = lag * (1f - IOI_TOL); val hi = lag * (1f + IOI_TOL)
        var c = 0
        for (v in iois) if (v >= lo && v <= hi) c++
        return c.toFloat() / iois.size
    }

    /** Onset positions: local peaks of the novelty above 30 % of the local (±1 s) maximum. */
    private fun onsetsIn(nov: FloatArray, start: Int, len: Int, envRate: Float): IntArray {
        val end = start + len
        val half = max(1, envRate.toInt())
        val minGap = max(2, (0.06f * envRate).toInt())
        val out = ArrayList<Int>()
        var last = -minGap
        for (i in start + 1 until end - 1) {
            val v = nov[i]
            if (v <= 0f || v < nov[i - 1] || v < nov[i + 1]) continue
            var localMax = 0f
            val a = max(start, i - half); val b = min(end, i + half + 1)
            for (k in a until b) if (nov[k] > localMax) localMax = nov[k]
            if (v < 0.3f * localMax) continue
            if (i - last < minGap) continue
            out += i; last = i
        }
        return out.toIntArray()
    }

    private fun analyzeWindow(nov: FloatArray, start: Int, len: Int, envRate: Float): WindowVote? {
        var energy = 0.0
        for (i in start until start + len) energy += nov[i].toDouble() * nov[i]
        if (energy <= 1e-9 || energy / len < 1e-6) return null // silence / no onsets

        val minLag = max(1, lagFor(CORR_MAX_BPM, envRate))
        val maxLag = min(lagFor(CORR_MIN_BPM, envRate), len / 2)
        if (maxLag <= minLag + 2) return null

        // Unbiased normalised autocorrelation: r[lag] ≈ 1 for a perfectly periodic train at
        // every multiple of its period, independent of how much of the window the lag eats.
        val r = FloatArray(maxLag + 1)
        val norm = (energy / len).toFloat()
        for (lag in minLag..maxLag) {
            var s = 0f
            val count = len - lag
            for (i in start until start + count) s += nov[i] * nov[i + lag]
            r[lag] = (s / count) / norm
        }

        // Inter-onset intervals for the density evidence.
        val onsets = onsetsIn(nov, start, len, envRate)
        val iois = if (onsets.size >= 2) IntArray(onsets.size - 1) { onsets[it + 1] - onsets[it] } else IntArray(0)

        val candMin = max(minLag + 1, lagFor(MAX_BPM, envRate))
        val candMax = min(maxLag - 1, lagFor(MIN_BPM, envRate))
        if (candMax <= candMin) return null

        // Onset density (events per second) sets the perceptual centre: denser onsets are
        // heard as a faster pulse (Moelants/McKinney), which is exactly what separates a
        // 175 BPM double-kick track from an 87 BPM one with the same accent pattern.
        val density = onsets.size / (len / envRate)
        val centreBpm = (95f + 17f * density).coerceIn(110f, 185f)

        var bestLag = -1
        var bestScore = 0f
        for (lag in candMin..candMax) {
            val base = r[lag]
            if (base <= 0.05f) continue
            if (base < r[lag - 1] || base < r[lag + 1]) continue // only true peaks of r
            val p = lag.toFloat()
            val comb = base +
                0.5f * peakNear(r, minLag, maxLag, 2f * p) +
                0.25f * peakNear(r, minLag, maxLag, 3f * p) +
                0.25f * peakNear(r, minLag, maxLag, 4f * p)
            // Sub-harmonic test: if the HALF period is (nearly) as periodic as P itself, P is
            // a sub-harmonic of the real pulse and is penalised. A clearly weaker half period
            // is a subdivision (hats), which is evidence FOR P, so it costs nothing.
            val sub = peakNear(r, minLag, maxLag, p / 2f)
            val ratio = sub / base
            val subPenalty = 0.5f * sub * smoothstep(0.75f, 0.95f, ratio)
            // Density evidence: the beat is the dominant inter-onset gap or a small multiple.
            val ioi = 0.5f * ioiMass(iois, p) + 0.5f * ioiMass(iois, p / 2f) +
                0.3f * ioiMass(iois, p / 3f) + 0.25f * ioiMass(iois, p / 4f)
            val bpm = 60f * envRate / p
            val score = (comb - subPenalty + 0.5f * ioi) * perceptualWeight(bpm, centreBpm)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag < 0) return null

        // Parabolic refinement of the peak for fractional-lag (sub-frame) accuracy.
        val l = bestLag
        val y0 = r[l - 1]; val y1 = r[l]; val y2 = r[l + 1]
        val denom = y0 - 2f * y1 + y2
        val refined = if (abs(denom) > 1e-9f) (l + 0.5f * (y0 - y2) / denom).coerceIn(l - 1f, l + 1f) else l.toFloat()
        return WindowVote(60f * envRate / refined, bestScore, r[l].coerceIn(0f, 1f))
    }

    private fun sameTempo(a: Float, b: Float): Boolean = abs(a - b) <= CLUSTER_TOL * max(a, b)

    private fun octaveRelated(a: Float, b: Float): Boolean {
        val ratio = max(a, b) / min(a, b)
        return abs(ratio - 2f) <= 0.08f || abs(ratio - 3f) <= 0.1f || abs(ratio - 1.5f) <= 0.06f
    }

    private fun consensus(votes: List<WindowVote>): TempoEstimate? {
        // Cluster votes within ±4 %.
        val clusters = ArrayList<MutableList<WindowVote>>()
        for (v in votes.sortedBy { it.bpm }) {
            val c = clusters.lastOrNull()
            if (c != null && sameTempo(c.map { it.bpm }.average().toFloat(), v.bpm)) c += v else clusters += mutableListOf(v)
        }
        val ranked = clusters.sortedWith(compareByDescending<List<WindowVote>> { it.size }.thenByDescending { c -> c.map { it.score }.average() })
        val winner = ranked.first()
        val sorted = winner.map { it.bpm }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        if (median <= 0f) return null

        val total = votes.size
        var weighted = winner.size.toFloat()
        for (c in ranked.drop(1)) {
            val cb = c.map { it.bpm }.average().toFloat()
            if (octaveRelated(median, cb)) weighted += 0.5f * c.size
        }
        val agreement = (weighted / total).coerceIn(0f, 1f)
        val strength = winner.map { it.periodicity }.average().toFloat()
        val strengthScore = ((strength - 0.1f) / 0.4f).coerceIn(0f, 1f)
        var confidence = agreement * (0.5f + 0.5f * strengthScore)
        if (total < MIN_VALID_WINDOWS) confidence *= total.toFloat() / MIN_VALID_WINDOWS

        return TempoEstimate(
            bpm = median,
            confidence = confidence.coerceIn(0f, 1f),
            periodicity = strength,
            windowsAgreeing = winner.size,
            windowsTotal = total,
            alternatives = ranked.drop(1).map { c -> c.map { it.bpm }.average().toFloat() }
        )
    }
}
