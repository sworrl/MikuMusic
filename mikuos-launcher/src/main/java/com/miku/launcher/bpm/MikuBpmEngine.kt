package com.miku.launcher.bpm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OS-Level Real-Time BPM Client for MikuOS SystemUI & Launcher.
 * Collects live tempo, beat pulses, and dominant album colors from MikuMusic.
 */
object MikuBpmEngine {
    private const val TAG = "MikuBpmEngine"
    const val ACTION_BPM_UPDATE = "com.miku.action.BPM_UPDATE"
    const val ACTION_BPM_PULSE = "com.miku.action.BPM_PULSE"
    const val EXTRA_BPM = "bpm"
    const val EXTRA_BEAT_INTERVAL_MS = "beat_interval_ms"
    const val EXTRA_IS_PLAYING = "is_playing"
    const val EXTRA_DOMINANT_COLOR = "dominant_color"

    // Every value that enters BpmState is clamped to these — the receiver is necessarily
    // RECEIVER_EXPORTED (pulses come from com.miku.player), so ANY app can broadcast these
    // actions. A bogus sender putting tempo in the wrong unit (e.g. beats-per-second ≈ 0.5,
    // or a raw beat interval) must never reach UI readouts as if it were BPM.
    private const val MIN_BPM = 20f
    private const val MAX_BPM = 999f
    private const val MIN_INTERVAL_MS = 60L
    private const val MAX_INTERVAL_MS = 4000L

    data class BpmState(
        val bpm: Float = 120f,
        val beatIntervalMs: Long = 500L,
        val isPlaying: Boolean = false,
        val dominantColor: Int = 0xFF00E5FF.toInt(),
        val lastPulseEpochMs: Long = 0L
    )

    private val _state = MutableStateFlow(BpmState())
    val state: StateFlow<BpmState> = _state.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /** Finite + in-range tempo, else the previous known-good value. */
    private fun sanitizeBpm(candidate: Float, fallback: Float): Float =
        if (candidate.isFinite() && candidate in MIN_BPM..MAX_BPM) candidate else fallback

    /** Positive, sane beat interval, else derived from the (already sanitized) tempo. */
    private fun sanitizeInterval(candidate: Long, bpm: Float): Long =
        if (candidate in MIN_INTERVAL_MS..MAX_INTERVAL_MS) candidate
        else (60_000f / bpm).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /** Live output-mix detector reports a beat at [bpm] — authoritative "audio is playing". */
    fun pushLivePulse(bpm: Float) {
        val prev = _state.value
        val b = sanitizeBpm(bpm, prev.bpm)
        _state.value = prev.copy(
            bpm = b,
            beatIntervalMs = sanitizeInterval((60_000f / b).toLong(), b),
            isPlaying = true,
            lastPulseEpochMs = System.currentTimeMillis()
        )
    }

    /** Live detector reports whether the DAC is actually outputting audio (gates "ALSA Standby"). */
    fun pushLivePlaying(playing: Boolean) {
        val prev = _state.value
        if (prev.isPlaying != playing) _state.value = prev.copy(isPlaying = playing)
    }

    fun startListening(context: Context) {
        if (receiver != null) return
        val cr = context.contentResolver

        // Initial state from Settings.Global — reads sanitized like broadcasts (stale keys
        // from older builds may hold values in the wrong unit).
        val initBpm = sanitizeBpm(
            try { Settings.Global.getFloat(cr, "miku_live_bpm", 120f) } catch (_: Throwable) { 120f },
            120f
        )
        val initInterval = sanitizeInterval(
            try { Settings.Global.getInt(cr, "miku_beat_interval_ms", 500).toLong() } catch (_: Throwable) { 500L },
            initBpm
        )
        val initPlaying = try { Settings.Global.getInt(cr, "miku_is_playing", 0) == 1 } catch (_: Throwable) { false }
        val initColor = try { Settings.Global.getInt(cr, "miku_album_dominant_color", 0xFF00E5FF.toInt()) } catch (_: Throwable) { 0xFF00E5FF.toInt() }

        _state.value = BpmState(
            bpm = initBpm,
            beatIntervalMs = initInterval,
            isPlaying = initPlaying,
            dominantColor = initColor
        )

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Nothing in here may throw: an exported receiver that throws crashes the
                // whole launcher process on a hostile/malformed broadcast.
                try {
                    when (intent.action) {
                        ACTION_BPM_UPDATE -> {
                            val prev = _state.value
                            val bpm = sanitizeBpm(intent.getFloatExtra(EXTRA_BPM, prev.bpm), prev.bpm)
                            val interval = sanitizeInterval(
                                intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, prev.beatIntervalMs), bpm
                            )
                            val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, prev.isPlaying)
                            val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, prev.dominantColor)
                            _state.value = prev.copy(
                                bpm = bpm,
                                beatIntervalMs = interval,
                                isPlaying = playing,
                                dominantColor = color
                            )
                        }
                        ACTION_BPM_PULSE -> {
                            val prev = _state.value
                            val bpm = sanitizeBpm(intent.getFloatExtra(EXTRA_BPM, prev.bpm), prev.bpm)
                            val interval = sanitizeInterval(
                                intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, prev.beatIntervalMs), bpm
                            )
                            val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                            val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, prev.dominantColor)
                            _state.value = prev.copy(
                                bpm = bpm,
                                beatIntervalMs = interval,
                                isPlaying = playing,
                                dominantColor = color,
                                lastPulseEpochMs = System.currentTimeMillis()
                            )
                        }
                    }
                } catch (t: Throwable) {
                    // Malformed extras from a foreign sender — keep last good state, say so once
                    // per occurrence instead of dying or going silent.
                    Log.w(TAG, "Dropped malformed BPM broadcast (${intent.action})", t)
                }
                // Keep the placed BPM widgets live. pushUpdate throttles itself (pulses arrive
                // every beat) and no-ops with none placed; never let it take down the receiver.
                try {
                    val app = ctx.applicationContext
                    com.miku.launcher.widget.MikuBpmWidget.pushUpdate(app)
                    com.miku.launcher.widget.MikuBpmComboWidget.pushUpdate(app)
                } catch (_: Throwable) {}
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_BPM_UPDATE)
            addAction(ACTION_BPM_PULSE)
        }
        try {
            // RECEIVER_EXPORTED is REQUIRED on Android 13+ for cross-app broadcasts (the pulses
            // come from com.miku.player). The old flag-less register threw SecurityException on
            // Android 14 — swallowed by this catch — so the receiver never attached and the badge
            // sat frozen at the 120 BPM default forever. That was the whole "BPM locked" bug.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
        } catch (t: Throwable) {
            // Never crash the launcher over this, but never hide it either — an unregistered
            // receiver means the whole BPM UI silently freezes at defaults (seen once already,
            // see the comment above). One warning per attempt; startListening retries on next call.
            Log.w(TAG, "BPM receiver registration failed — live tempo UI will run on defaults", t)
        }

        // Live output-mix beat detection so the counter "sees ALSA" for ANY source (Spotify etc),
        // not only Miku's offline file analysis. Idempotent; fails soft without RECORD_AUDIO.
        try { MikuLiveBeatDetector.start(context.applicationContext) } catch (_: Throwable) {}
    }
}
