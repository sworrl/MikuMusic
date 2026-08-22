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

    fun isActive(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DAC_MODE, false)
    }

    fun getSampleRate(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_SAMPLE_RATE, 192000)
    }

    fun getBitDepth(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_BIT_DEPTH, 32)
    }

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
