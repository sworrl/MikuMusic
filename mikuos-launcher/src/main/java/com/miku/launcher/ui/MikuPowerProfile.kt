package com.miku.launcher.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Miku Music's power governor publishes Settings.Global `miku_power_profile`
 * ("perf" | "balanced" | "audio_only" | "idle"). In the two low states the launcher pauses every
 * infinite transition, shortens springs/transitions to ~120 ms and throttles badge/status-bar
 * refresh to ≥ 5 s; "perf"/"balanced" restore instantly (observer-driven, no polling).
 */
object MikuPowerProfile {
    const val KEY = "miku_power_profile"
    private val _profile = MutableStateFlow("balanced")
    val profile: StateFlow<String> = _profile.asStateFlow()
    private val _lowPower = MutableStateFlow(false)
    val lowPower: StateFlow<Boolean> = _lowPower.asStateFlow()
    @Volatile private var attached = false

    /** Cheap non-compose read for modifiers / animation specs. */
    val isLowPower: Boolean get() = _lowPower.value

    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        val app = ctx.applicationContext
        fun read() {
            val p = try { Settings.Global.getString(app.contentResolver, KEY)?.trim()?.lowercase() } catch (_: Throwable) { null }
            val prof = if (p.isNullOrEmpty()) "balanced" else p
            _profile.value = prof
            _lowPower.value = prof == "audio_only" || prof == "idle"
        }
        read()
        try {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = read() }
            )
        } catch (_: Throwable) {}
    }

    fun glyph(profile: String): String = when (profile) {
        "perf" -> "⚡"
        "audio_only" -> "♪"
        "idle" -> "☾"
        else -> ""
    }

    /** Opens Miku Music's Power Governor page. */
    fun openGovernor(ctx: Context) {
        try {
            ctx.startActivity(Intent().apply {
                setClassName("com.miku.player", "com.miku.player.HardwareSettingsActivity")
                putExtra("section", "power_governor")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {}
    }

    /** Badge/status refresh cadence: normal [normalMs], ≥ 5 s in the low states. */
    fun refreshMs(normalMs: Long): Long = if (isLowPower) maxOf(normalMs, 5000L) else normalMs

    // ---- Launcher visibility gate: pollers park while the launcher is not on screen ----
    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    fun setLauncherVisible(v: Boolean) { _visible.value = v }
    /** Suspends until the launcher activity is STARTED (no polling / no work while another app is in front). */
    suspend fun awaitVisible() { if (!_visible.value) _visible.first { it } }
}

/**
 * Infinite-transition values that FREEZE in the low-power profiles: identical call shape to
 * [InfiniteTransition.animateFloat] / [animateColor] plus the gate flag, so a site becomes
 * `t.gatedFloat(lowPowerGate, ...)`. Frozen = a plain remembered state at [initialValue]
 * (no frame-clock subscription at all, so no work per frame).
 */
@Composable
fun InfiniteTransition.gatedFloat(
    low: Boolean,
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String = "gatedFloat"
): State<Float> = if (low) remember(initialValue) { mutableStateOf(initialValue) }
    else animateFloat(initialValue = initialValue, targetValue = targetValue, animationSpec = animationSpec, label = label)

@Composable
fun InfiniteTransition.gatedColor(
    low: Boolean,
    initialValue: androidx.compose.ui.graphics.Color,
    targetValue: androidx.compose.ui.graphics.Color,
    animationSpec: InfiniteRepeatableSpec<androidx.compose.ui.graphics.Color>,
    label: String = "gatedColor"
): State<androidx.compose.ui.graphics.Color> = if (low) remember(initialValue) { mutableStateOf(initialValue) }
    else animateColor(initialValue = initialValue, targetValue = targetValue, animationSpec = animationSpec, label = label)

@Composable
fun rememberLowPower(): State<Boolean> {
    val ctx = LocalContext.current
    MikuPowerProfile.attach(ctx)
    return MikuPowerProfile.lowPower.collectAsState()
}

@Composable
fun rememberPowerProfile(): State<String> {
    val ctx = LocalContext.current
    MikuPowerProfile.attach(ctx)
    return MikuPowerProfile.profile.collectAsState()
}
