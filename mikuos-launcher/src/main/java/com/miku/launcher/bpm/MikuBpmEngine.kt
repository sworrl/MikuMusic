package com.miku.launcher.bpm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OS-Level Real-Time BPM Client for MikuOS SystemUI & Launcher.
 * Collects live tempo, beat pulses, and dominant album colors from MikuMusic.
 */
object MikuBpmEngine {
    const val ACTION_BPM_UPDATE = "com.miku.action.BPM_UPDATE"
    const val ACTION_BPM_PULSE = "com.miku.action.BPM_PULSE"
    const val EXTRA_BPM = "bpm"
    const val EXTRA_BEAT_INTERVAL_MS = "beat_interval_ms"
    const val EXTRA_IS_PLAYING = "is_playing"
    const val EXTRA_DOMINANT_COLOR = "dominant_color"

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

    fun startListening(context: Context) {
        if (receiver != null) return
        val cr = context.contentResolver
        
        // Initial state from Settings.Global
        val initBpm = try { Settings.Global.getFloat(cr, "miku_live_bpm", 120f) } catch (_: Throwable) { 120f }
        val initInterval = try { Settings.Global.getInt(cr, "miku_beat_interval_ms", 500).toLong() } catch (_: Throwable) { 500L }
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
                when (intent.action) {
                    ACTION_BPM_UPDATE -> {
                        val bpm = intent.getFloatExtra(EXTRA_BPM, _state.value.bpm)
                        val interval = intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, _state.value.beatIntervalMs)
                        val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, _state.value.isPlaying)
                        val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, _state.value.dominantColor)
                        _state.value = _state.value.copy(
                            bpm = bpm,
                            beatIntervalMs = interval,
                            isPlaying = playing,
                            dominantColor = color
                        )
                    }
                    ACTION_BPM_PULSE -> {
                        val bpm = intent.getFloatExtra(EXTRA_BPM, _state.value.bpm)
                        val interval = intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, _state.value.beatIntervalMs)
                        val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, _state.value.dominantColor)
                        _state.value = _state.value.copy(
                            bpm = bpm,
                            beatIntervalMs = interval,
                            dominantColor = color,
                            lastPulseEpochMs = System.currentTimeMillis()
                        )
                    }
                }
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
        } catch (_: Throwable) {}
    }
}
