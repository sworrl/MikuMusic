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
    // "" = Miku Music's governor has not published a profile (key absent / unreadable). It used to
    // default to "balanced", which the thermal modal and the POWER MODE tile then printed as a
    // "live:" governor reading that no governor had ever produced.
    private val _profile = MutableStateFlow("")
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
            val prof = p ?: ""   // absent stays "" (unknown), never a presumed "balanced"
            _profile.value = prof
            _lowPower.value = prof == "audio_only" || prof == "idle"
        }
        read()
        readMode(app)
        try {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(MODE_KEY), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = readMode(app) }
            )
        } catch (_: Throwable) {}
        try {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = read() }
            )
        } catch (_: Throwable) {}
    }

    // ---- User power MODE (Settings.Global miku_power_mode: "auto" | "perf" | "save"; missing = auto).
    // Miku Music's governor honours it; the launcher only cycles/displays it. ----
    const val MODE_KEY = "miku_power_mode"
    private val _mode = MutableStateFlow("auto")
    val mode: StateFlow<String> = _mode.asStateFlow()
    private fun readMode(app: Context) {
        val m = try { Settings.Global.getString(app.contentResolver, MODE_KEY)?.trim()?.lowercase() } catch (_: Throwable) { null }
        _mode.value = if (m == "perf" || m == "save") m else "auto"
    }
    fun nextMode(current: String): String = when (current) { "auto" -> "perf"; "perf" -> "save"; else -> "auto" }
    /**
     * Sets the user power MODE directly ("auto" | "perf" | "save") and persists it.
     *
     * Returns the mode the SYSTEM actually holds after the write — not the one that was asked for.
     * Both of these used to set `_mode.value = m` optimistically before writing, and then swallow
     * every failure, so a refused write still lit the Performance chip and made the thermal
     * observatory's header read "miku_power_mode · live: PERFORMANCE" for a value the system never
     * accepted and Miku Music's governor would never see.
     */
    fun setMode(ctx: Context, mode: String): String {
        val m = if (mode == "perf" || mode == "save") mode else "auto"
        val app = ctx.applicationContext
        try { Settings.Global.putString(app.contentResolver, MODE_KEY, m) } catch (_: Throwable) {}
        // Read back: the ContentObserver handles the success path too, but a caller that uses the
        // return value must never be handed an unverified mode.
        readMode(app)
        return _mode.value
    }
    /** Cycles auto → perf → save → auto, persists it, and returns the VERIFIED resulting mode. */
    fun cycleMode(ctx: Context): String {
        val next = nextMode(_mode.value)
        val app = ctx.applicationContext
        try { Settings.Global.putString(app.contentResolver, MODE_KEY, next) } catch (_: Throwable) {}
        readMode(app)
        return _mode.value
    }
    /** Kawaii glyph for the MODE (perf ⚡ / save ☾) or, in auto, for the live profile. */
    fun modeGlyph(mode: String, profile: String): String = when (mode) {
        "perf" -> "⚡"
        "save" -> "☾"
        else -> glyph(profile).ifEmpty { "✦" }
    }
    fun modeLabel(mode: String, profile: String): String = when (mode) {
        "perf" -> "PERFORMANCE"
        "save" -> "BATTERY SAVER"
        // A blank profile means nothing was published — say so instead of claiming "BALANCED".
        else -> "AUTO · " + when (profile) {
            "perf" -> "PERF"
            "audio_only" -> "AUDIO"
            "idle" -> "IDLE"
            "balanced" -> "BALANCED"
            else -> "profile not published"
        }
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
fun rememberPowerMode(): State<String> {
    val ctx = LocalContext.current
    MikuPowerProfile.attach(ctx)
    return MikuPowerProfile.mode.collectAsState()
}
@Composable
fun rememberPowerProfile(): State<String> {
    val ctx = LocalContext.current
    MikuPowerProfile.attach(ctx)
    return MikuPowerProfile.profile.collectAsState()
}
