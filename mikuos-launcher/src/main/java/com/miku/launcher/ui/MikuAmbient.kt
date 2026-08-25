package com.miku.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Home-screen "attention" gate for ambient eye-candy (dock aura, plasma clock colour sweep,
 * capsule shimmers, telemetry glows, weather bob…).
 *
 * The 2026-08-25 pass-C profile showed the idle home rendering 60 fps forever (1800 frames /
 * 30 s, p50 29 ms) purely from these infinite transitions. A Pixel launcher is *still* when
 * nobody is looking at it, so ambient animation now runs only while there is attention:
 *  - the user touched the launcher within [ATTENTION_MS], or
 *  - music is playing (the hearts pulse on the beat and the EQ badge want company), or
 *  - the launcher is being shown fresh (first [ATTENTION_MS] after resume),
 * and never while a drawer/modal covers home, while the launcher is stopped, or in the
 * low-power profiles. Outside that window every gated animator freezes at its rest value and
 * stops subscribing to the frame clock, so the only periodic redraw left is the 1 Hz clock /
 * hearts pulse.
 */
object MikuAmbient {
    const val ATTENTION_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _attention = MutableStateFlow(true)
    private val _playing = MutableStateFlow(false)
    private val _covered = MutableStateFlow(0)
    private var expiry: Job? = null

    /** True while the user is (recently) interacting. */
    val attention: StateFlow<Boolean> = _attention.asStateFlow()
    /** True while Miku Music reports playback (beat-synced badges stay lively). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    /** > 0 while the drawer or a modal covers the home surface. */
    val covered: StateFlow<Int> = _covered.asStateFlow()

    /** Call from the activity's touch dispatch / resume: (re)arms the attention window. */
    fun touch() {
        _attention.value = true
        expiry?.cancel()
        expiry = scope.launch {
            delay(ATTENTION_MS)
            _attention.value = false
        }
    }

    fun setPlaying(on: Boolean) { if (_playing.value != on) _playing.value = on }

    fun pushCovered() { _covered.value = _covered.value + 1 }
    fun popCovered() { _covered.value = (_covered.value - 1).coerceAtLeast(0) }

    /** Non-compose read (modifiers, draw lambdas). */
    val isActive: Boolean
        get() = (_attention.value || _playing.value) && _covered.value == 0 &&
            MikuPowerProfile.visible.value && !MikuPowerProfile.isLowPower
}

/**
 * Gate flag for [InfiniteTransition.gatedFloat] / [gatedColor]: TRUE = freeze. Combines the
 * low-power profile with the ambient attention window, so a call site changes from
 * `rememberLowPower()` to `rememberAmbientGate()` and nothing else.
 */
@Composable
fun rememberAmbientGate(): State<Boolean> {
    val low by rememberLowPower()
    val attention by MikuAmbient.attention.collectAsState()
    val playing by MikuAmbient.playing.collectAsState()
    val covered by MikuAmbient.covered.collectAsState()
    val visible by MikuPowerProfile.visible.collectAsState()
    return remember {
        derivedStateOf { low || covered > 0 || !visible || !(attention || playing) }
    }
}
