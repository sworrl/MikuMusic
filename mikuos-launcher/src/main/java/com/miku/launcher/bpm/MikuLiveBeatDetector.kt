package com.miku.launcher.bpm

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.util.Log
import com.miku.launcher.ui.MikuPowerProfile
import kotlinx.coroutines.*

/**
 * Live audio-output beat/tempo detector — the "sees ALSA" fix. Taps the global output mix via
 * a Visualizer on audio session 0 and runs an energy-onset beat detector on the low (bass/kick)
 * FFT bins, so the BPM counter/game reflects whatever is ACTUALLY playing out the DAC — Spotify,
 * Tidal, YouTube, our own player — instead of only Miku's offline file analysis. Feeds the same
 * [MikuBpmEngine] state the UI already renders.
 *
 * Needs RECORD_AUDIO + MODIFY_AUDIO_SETTINGS (+ CAPTURE_AUDIO_OUTPUT is signature, held via the
 * platform key). Fails soft: if a Visualizer can't attach (e.g. a bit-perfect DIRECT/DTA stream
 * that bypasses the mixer, or perms denied) it just reports standby and the file-based broadcast
 * path still drives BPM for our own player.
 */
object MikuLiveBeatDetector {
    private const val TAG = "MikuLiveBeat"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gateJob: Job? = null
    @Volatile private var vis: Visualizer? = null
    @Volatile private var running = false

    // Onset-detection state (energy algorithm on bass bins).
    private val energyHist = ArrayDeque<Float>()
    private val beatIntervals = ArrayDeque<Long>()
    @Volatile private var lastBeatMs = 0L
    // 0 = no tempo measured yet. It was 120f, and the FIRST onset (which has no interval to measure
    // from) pushed that literal out as a detected tempo — the badge, widgets and status bar then
    // showed a confident "120" that nothing had measured.
    @Volatile private var liveBpm = 0f

    fun start(context: Context) {
        if (gateJob?.isActive == true) return
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Gate loop: only capture while audio is actually coming out; tear down when silent so
        // the counter shows "standby" honestly and we don't hold a Visualizer for nothing.
        gateJob = scope.launch {
            while (isActive) {
                // VISIBILITY GATE (added 2026-09-13). Everything this detector feeds — the BPM badge,
                // widget and observatory — is launcher UI. Capturing the output mix at max FFT rate
                // while the launcher is BACKGROUNDED cost ~4% of a core continuously for something
                // nobody could see, and over a long listening session that is what got the launcher
                // killed by the platform: `am_kill com.miku.launcher, excessive cpu 158760 during
                // 300077 limit=25` (~53% sustained). The lockscreen's BPM is unaffected: it reads
                // Settings.Global miku_now_playing_bpm, published by Miku Music, not from here.
                if (!MikuPowerProfile.visible.value) {
                    if (vis != null) { detach(); MikuBpmEngine.pushLivePlaying(false) }
                    MikuPowerProfile.awaitVisible()   // suspends — no polling at all while hidden
                    continue
                }
                val musicOut = try { am?.isMusicActive == true } catch (_: Throwable) { false }
                if (musicOut && vis == null) attach(app)
                if (!musicOut && vis != null) {
                    detach()
                    MikuBpmEngine.pushLivePlaying(false)
                    // Output stopped: whatever comes back may be a different song, so the held
                    // tempo is dropped rather than carried across the gap.
                    MikuTempoLock.onPlaybackStopped()
                }
                // A track change is not an outlier to be argued down over six onsets — drop the
                // lock at once and re-acquire. onTrackChanged() no-ops when the key is unchanged.
                if (musicOut) {
                    val key = try {
                        val t = android.provider.Settings.Global.getString(app.contentResolver, "miku_now_playing_title")
                        val a2 = android.provider.Settings.Global.getString(app.contentResolver, "miku_now_playing_artist")
                        if (t.isNullOrBlank() && a2.isNullOrBlank()) null else "$a2|$t"
                    } catch (_: Throwable) { null }
                    MikuTempoLock.onTrackChanged(key)
                }
                // If capturing but no onset for >2.5s while music is out, keep isPlaying true but
                // let BPM coast on the last value (steady-state / ambient track).
                delay(600L)
            }
        }
    }

    fun stop() { gateJob?.cancel(); gateJob = null; detach() }

    private fun attach(app: Context) {
        try {
            val v = Visualizer(0).apply {           // session 0 = global output mix
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, wf: ByteArray?, sr: Int) {}
                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft != null) onFft(app, fft)
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)   // fft only, max rate
                enabled = true
            }
            vis = v; running = true
            energyHist.clear(); beatIntervals.clear(); lastBeatMs = 0L
            Log.i(TAG, "attached Visualizer to output mix (session 0)")
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer attach failed (perms or DIRECT/DTA bypass): $t")
            vis = null
        }
    }

    private fun detach() {
        try { vis?.enabled = false; vis?.release() } catch (_: Throwable) {}
        vis = null; running = false
    }

    private fun onFft(app: Context, fft: ByteArray) {
        // Bass energy from the lowest FFT bins (kick/beat lives ~40-150Hz).
        var e = 0f; var n = 0; var i = 2
        val lastBin = 2 + 16 * 2   // ~8 complex bins
        while (i + 1 < fft.size && i < lastBin) {
            val re = fft[i].toFloat(); val im = fft[i + 1].toFloat()
            e += re * re + im * im; i += 2; n++
        }
        e = if (n > 0) e / n else 0f
        energyHist.addLast(e); while (energyHist.size > 43) energyHist.removeFirst()  // ~1s window
        val avg = if (energyHist.isNotEmpty()) energyHist.sum() / energyHist.size else e
        val now = System.currentTimeMillis()
        val refractoryMs = 260L                        // cap ~230 BPM, reject double-triggers
        val isOnset = e > avg * 1.45f && e > 800f && (now - lastBeatMs) > refractoryMs
        if (isOnset) {
            if (lastBeatMs != 0L) {
                val interval = now - lastBeatMs
                if (interval in 260L..2000L) {
                    beatIntervals.addLast(interval); while (beatIntervals.size > 8) beatIntervals.removeFirst()
                    val sorted = beatIntervals.sorted()
                    val med = sorted[sorted.size / 2]
                    liveBpm = (60000f / med).coerceIn(40f, 220f)
                }
            }
            lastBeatMs = now
            // Audio IS out, so report playing; but only publish a tempo once at least two intervals
            // have produced a real median. Before that there is nothing measured to publish.
            //
            // And never publish the raw per-onset estimate: it moves by a few BPM on every vocal
            // transient, cymbal or fill, which made the readout flicker while a track played at
            // one constant tempo. MikuTempoLock ACQUIRES a tempo from agreeing estimates, then
            // HOLDS exactly that value (octave-tolerantly) until sustained evidence of a real
            // tempo change, so what reaches the UI is solid. While it is still acquiring it
            // returns 0 and we publish no tempo at all — "listening…", not a confident guess.
            val published = if (liveBpm > 0f && beatIntervals.size >= 2) MikuTempoLock.onEstimate(liveBpm) else 0f
            if (published > 0f) {
                MikuBpmEngine.pushLivePulse(published)
            } else {
                MikuBpmEngine.pushLivePlaying(true)
            }
        }
    }
}
