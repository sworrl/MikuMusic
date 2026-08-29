package com.m500.hardware

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies an AmbientCamera reading to screen brightness.
 *
 * Use case this exists for: the device comes out of a pocket into direct sun
 * while the screen is still set to an indoor level, and the display is too dim
 * to see well enough to raise it manually.
 *
 * Design rules that follow from that:
 *
 *  1. SAMPLE ON WAKE, NOT CONTINUOUSLY. In a pocket the lens is covered and
 *     reads near-black. A continuous loop would drive brightness DOWN right
 *     before you pull it out - the exact opposite of what is wanted. We sample
 *     when the screen turns on, which is when the reading is both meaningful
 *     and needed.
 *
 *  2. RAISING IS EAGER, LOWERING IS RELUCTANT. A wrong bright reading costs a
 *     little battery. A wrong dark reading costs an unreadable screen, which is
 *     the failure we are fixing. Lowering requires the reading to be trusted
 *     (derived from exposure, not fallback luma) and is clamped to a floor.
 *
 *  3. NEVER TRAP THE USER. A manual brightness change disables auto-apply until
 *     the next wake, so this can never fight the user.
 */
object AmbientBrightnessManager {
    private const val TAG = "AmbientBrightness"
    private const val PREFS_NAME = "m500_hardware_prefs"

    const val KEY_ENABLED = "m500_ambient_auto_brightness"
    const val KEY_LAST_LUX = "m500_ambient_last_lux"

    /** Below this we will not auto-lower - keeps the screen readable. */
    private const val MIN_AUTO_BRIGHTNESS = 40      // of 255
    private const val MAX_BRIGHTNESS = 255
    /** Ignore changes smaller than this to avoid visible hunting. */
    private const val HYSTERESIS = 18

    fun isEnabled(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val g = try { Settings.Global.getInt(cr, KEY_ENABLED, -1) } catch (_: Throwable) { -1 }
        if (g >= 0) return g == 1
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    suspend fun setEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        try {
            Settings.Global.putInt(ctx.contentResolver, KEY_ENABLED, if (enabled) 1 else 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not write to Settings.Global", e)
        }
    }

    /**
     * Map estimated lux to a 0-255 brightness value.
     *
     * Perception of brightness is roughly logarithmic, and the useful range
     * spans ~5 lux (dim room) to ~50000 lux (direct sun) - four orders of
     * magnitude - so a linear map would spend almost its entire range on
     * outdoor light and leave indoor levels indistinguishable.
     */
    fun luxToBrightness(lux: Float): Int {
        val l = max(1.0f, lux)
        // ln(1)=0 .. ln(50000)~10.8 -> normalise onto 0..1
        val norm = (ln(l) / ln(50000.0f)).coerceIn(0f, 1f)
        // Floor at MIN so an auto result is always readable.
        val span = MAX_BRIGHTNESS - MIN_AUTO_BRIGHTNESS
        return (MIN_AUTO_BRIGHTNESS + norm * span).roundToInt().coerceIn(MIN_AUTO_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    fun currentBrightness(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    } catch (_: Throwable) { 128 }

    private fun applyBrightness(ctx: Context, value: Int): Boolean = try {
        // Adaptive brightness is off on this device (no ALS), but clear it
        // anyway so our manual value is not overridden.
        Settings.System.putInt(ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, MAX_BRIGHTNESS))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "cannot set brightness (needs WRITE_SETTINGS)", t); false
    }

    /**
     * Take one reading and apply it. Safe to call on every screen-on.
     * @return the brightness applied, or null if nothing was changed.
     */
    suspend fun sampleAndApply(ctx: Context): Int? = withContext(Dispatchers.IO) {
        if (!isEnabled(ctx)) return@withContext null

        // Do not sample while the Fn slider has the device locked - it is in a
        // pocket or bag, the lens is covered, and the reading is meaningless.
        if (PocketLockManager.isFnSwitchEngaged(ctx)) {
            Log.d(TAG, "skip: Fn lock engaged"); return@withContext null
        }

        val reading = AmbientCamera.sample(ctx) ?: run {
            Log.d(TAG, "skip: no reading (no camera / denied / timeout)")
            return@withContext null
        }

        val target = luxToBrightness(reading.lux)
        val current = currentBrightness(ctx)
        val delta = target - current

        // Rule 2: eager to raise, reluctant to lower.
        val shouldApply = when {
            delta > HYSTERESIS -> true
            delta < -HYSTERESIS -> reading.fromExposure && target >= MIN_AUTO_BRIGHTNESS
            else -> false
        }

        Log.d(TAG, "lux=%.0f ev100=%.1f luma=%d trusted=%b cur=%d -> tgt=%d apply=%b".format(
            reading.lux, reading.ev100, reading.meanLuma, reading.fromExposure,
            current, target, shouldApply))

        try {
            Settings.Global.putString(ctx.contentResolver, KEY_LAST_LUX, "%.0f".format(reading.lux))
        } catch (_: Throwable) { }

        if (!shouldApply) return@withContext null
        if (!applyBrightness(ctx, target)) return@withContext null
        target
    }

    /**
     * Deterministic escape hatch - no camera, no sampling, no latency.
     * Bound to a hardware gesture so it works when the screen is unreadable.
     */
    fun boostToMax(ctx: Context): Boolean = applyBrightness(ctx, MAX_BRIGHTNESS)

    /** Diagnostic helper: report what the camera thinks without changing anything. */
    suspend fun probe(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!AmbientCamera.isAvailable(ctx)) return@withContext "no camera enumerable on this device"
        if (!AmbientCamera.hasPermission(ctx)) return@withContext "CAMERA permission not granted"
        val r = AmbientCamera.sample(ctx) ?: return@withContext "sample failed/timed out"
        "lux~%.0f ev100=%.1f luma=%d trusted=%b -> brightness %d".format(
            r.lux, r.ev100, r.meanLuma, r.fromExposure, luxToBrightness(r.lux))
    }
}
