package com.miku.systemui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One haptic vocabulary for every SystemUI surface (Pixel grammar):
 *   tick     light  — gesture claimed / step / un-commit / hold-ring quarter
 *   confirm  medium — commit (back, home, tile tap, quick switch)
 *   pop      strong — hold-to-recents, destructive arm, clear-all
 *   reject   double tick — a gesture that could not be honoured
 * Views route through performHapticFeedback (respects the system haptic setting); contexts
 * without a View (power menu actions) use [buzz].
 */
object MikuHaptics {
    fun tick(v: View?) = perform(v, HapticFeedbackConstants.CLOCK_TICK)
    fun confirm(v: View?) = perform(v, HapticFeedbackConstants.CONTEXT_CLICK)
    fun pop(v: View?) = perform(v, HapticFeedbackConstants.LONG_PRESS)
    fun reject(v: View?) { tick(v); v?.postDelayed({ tick(v) }, 60L) }

    private fun perform(v: View?, constant: Int) {
        runCatching { v?.performHapticFeedback(constant) }
    }

    /** Direct vibration for contexts without a View. */
    fun buzz(ctx: Context, ms: Long = 20L, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        runCatching {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vib.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        }
    }
}
