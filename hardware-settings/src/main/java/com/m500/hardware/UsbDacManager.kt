package com.m500.hardware

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsbDacManager {
    private const val TAG = "UsbDacManager"
    private const val PREFS_NAME = "m500_hardware_prefs"
    private const val KEY_DAC_MODE = "usb_dac_mode_active"
    private const val KEY_SAMPLE_RATE = "usb_dac_sample_rate"
    private const val KEY_BIT_DEPTH = "usb_dac_bit_depth"

    /**
     * REAL state: the USB gadget composition (`sys.usb.config` / `sys.usb.state`) actually contains
     * the uac2 function. The saved preference is only consulted when the property cannot be read,
     * so the toggle/tile can no longer claim "USB DAC: ON" after a reboot or a failed setprop.
     */
    fun isActive(ctx: Context): Boolean {
        val state = sysProp("sys.usb.state") ?: sysProp("sys.usb.config")
        if (state != null) return state.split(',').any { it.trim().equals("uac2", ignoreCase = true) }
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DAC_MODE, false)
    }

    /** Read-only system property via SystemProperties (no root needed). Null when unreadable/blank. */
    fun sysProp(key: String): String? = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    const val DEFAULT_SAMPLE_RATE = 192000
    const val DEFAULT_BIT_DEPTH = 32

    /**
     * The rate the user actually picked, or null if they never picked one. UI must use this:
     * [getSampleRate] hands back [DEFAULT_SAMPLE_RATE] for an unset preference, and the settings
     * screen was highlighting the 192k chip as a live selection before any choice had been made.
     */
    fun getSampleRateOrNull(ctx: Context): Int? {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (sp.contains(KEY_SAMPLE_RATE)) sp.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE) else null
    }

    /** The bit depth the user actually picked, or null if they never picked one. See [getSampleRateOrNull]. */
    fun getBitDepthOrNull(ctx: Context): Int? {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (sp.contains(KEY_BIT_DEPTH)) sp.getInt(KEY_BIT_DEPTH, DEFAULT_BIT_DEPTH) else null
    }

    /**
     * Effective rate used when programming the UAC2 gadget: the user's choice, else the build
     * default. NOT a statement that the user selected it — display surfaces want [getSampleRateOrNull].
     */
    fun getSampleRate(ctx: Context): Int = getSampleRateOrNull(ctx) ?: DEFAULT_SAMPLE_RATE

    /** Effective bit depth used when programming the gadget. See [getSampleRate]. */
    fun getBitDepth(ctx: Context): Int = getBitDepthOrNull(ctx) ?: DEFAULT_BIT_DEPTH

    suspend fun setUsbDacMode(ctx: Context, enable: Boolean) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DAC_MODE, enable).apply()

        if (enable) {
            val rate = getSampleRate(ctx)
            val bits = getBitDepth(ctx)
            val script = """
                setprop vendor.usb.uac2.function.init 1
                setprop sys.usb.config none
                sleep 0.1
                setprop vendor.usb.uac2.function.start 1
                setprop sys.audio.uac.sample_rate $rate
                setprop sys.audio.uac.sample_bit $bits
                setprop sys.audio.uac.channels 2
                setprop sys.usb.config diag,uac2,adb
            """.trimIndent()
            RootShell.exec(script)
            Log.i(TAG, "USB DAC Mode Enabled ($rate Hz, $bits bit)")
        } else {
            val script = """
                setprop vendor.usb.uac2.function.start 0
                setprop sys.usb.config none
                sleep 0.1
                setprop sys.usb.config mtp,adb
            """.trimIndent()
            RootShell.exec(script)
            Log.i(TAG, "USB DAC Mode Disabled (MTP/ADB restored)")
        }
    }

    suspend fun configureParams(ctx: Context, sampleRate: Int, bitDepth: Int) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_SAMPLE_RATE, sampleRate)
            .putInt(KEY_BIT_DEPTH, bitDepth)
            .apply()

        if (isActive(ctx)) {
            setUsbDacMode(ctx, true)
        }
    }
}
