package com.miku.launcher.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * The ONE launcher haptics vocabulary (Pixel-style semantics, Miku textures):
 *  - [tick]    light ratchet — press, hover, list step
 *  - [confirm] firm double — an action committed (drop, toggle on, unlock)
 *  - [pop]     heavier single — long-press pick-up / menu appear
 *  - [reject]  downward stepped rumble — a gesture that was refused
 * Every call honours the system "Touch feedback" toggle (Settings.System.HAPTIC_FEEDBACK_ENABLED)
 * and the launcher's own `haptics_enabled` pref, so there is exactly one place to mute.
 */
object MikuHaptics {
    private const val PREFS = "miku_launcher_prefs"
    private const val KEY = "haptics_enabled"

    fun enabled(ctx: Context): Boolean = try {
        val sys = Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        sys && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: Throwable) { true }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    private fun vib(ctx: Context): Vibrator? =
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }

    private fun predefined(ctx: Context, id: Int, fallbackMs: Long) {
        val v = vib(ctx) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(id))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
            try { v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Throwable) {}
        }
    }

    fun tick(ctx: Context) { if (enabled(ctx)) MikuTactileHaptics.playRatchetTick(ctx) }

    fun confirm(ctx: Context) {
        if (!enabled(ctx)) return
        val v = vib(ctx) ?: return
        try {
            val t = longArrayOf(0, 14, 40, 22)
            val a = intArrayOf(0, 170, 0, 255)
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(t, a, -1))
            else v.vibrate(VibrationEffect.createWaveform(t, -1))
        } catch (_: Throwable) { predefined(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK, 30L) }
    }

    fun pop(ctx: Context) { if (enabled(ctx)) predefined(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 24L) }

    fun reject(ctx: Context) { if (enabled(ctx)) MikuTactileHaptics.playBumpyDislikeTexture(ctx) }

    /** The signature "bumpy but good" like-heart texture (kept for the lockscreen heart). */
    fun like(ctx: Context) { if (enabled(ctx)) MikuTactileHaptics.playBumpyLikeTexture(ctx) }
}
