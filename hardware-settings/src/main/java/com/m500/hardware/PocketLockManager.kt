package com.m500.hardware

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PocketLockManager {
    private const val TAG = "PocketLockManager"
    private const val PREFS_NAME = "m500_hardware_prefs"
    private const val KEY_FN_MODE = "m500_fn_mode"
    private const val KEY_ALLOW_VOLUME = "m500_fn_allow_volume_wheel"

    fun getFnMode(ctx: Context): String {
        val cr = ctx.contentResolver
        val globalVal = try { Settings.Global.getString(cr, "fn_settings") } catch (_: Throwable) { null }
        if (!globalVal.isNullOrEmpty()) return globalVal

        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_FN_MODE, "touch_and_key_lock") ?: "touch_and_key_lock"
    }

    fun isAllowVolumeWheel(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val globalVal = try { Settings.Global.getInt(cr, KEY_ALLOW_VOLUME, 1) } catch (_: Throwable) { 1 }
        return globalVal == 1
    }

    fun isFnSwitchEngaged(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "fn_status", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setPocketLockMode(ctx: Context, mode: String, allowVolumeWheel: Boolean) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_FN_MODE, mode)
            .putBoolean(KEY_ALLOW_VOLUME, allowVolumeWheel)
            .apply()

        try {
            Settings.Global.putString(ctx.contentResolver, "fn_settings", mode)
            Settings.Global.putInt(ctx.contentResolver, KEY_ALLOW_VOLUME, if (allowVolumeWheel) 1 else 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not write to Settings.Global", e)
        }

        // Only enforce hardware locking if the physical Fn switch is currently engaged
        if (isFnSwitchEngaged(ctx)) {
            applyHardwareLock(mode, allowVolumeWheel)
        } else {
            applyHardwareLock("off", allowVolumeWheel)
        }
    }

    suspend fun applyHardwareLock(mode: String, allowVolumeWheel: Boolean) = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext
        val isLocked = mode == "touch_and_key_lock" || mode == "Both"
        val script = if (isLocked) {
            """
                setprop vendor.audio.hiby.tp_gesture enable
                echo 1 > /sys/devices/platform/soc/4a88000.i2c/i2c-1/1-005a/hyn_gesture_mode 2>/dev/null || true
                echo ${if (allowVolumeWheel) "sw_user" else "all"} > /sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys 2>/dev/null || true
            """.trimIndent()
        } else {
            """
                setprop vendor.audio.hiby.tp_gesture disable
                echo 0 > /sys/devices/platform/soc/4a88000.i2c/i2c-1/1-005a/hyn_gesture_mode 2>/dev/null || true
                echo none > /sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys 2>/dev/null || true
            """.trimIndent()
        }
        RootShell.exec(script)
        Log.i(TAG, "Pocket Lock hardware state applied: $mode (allowVol=$allowVolumeWheel)")
    }
}
