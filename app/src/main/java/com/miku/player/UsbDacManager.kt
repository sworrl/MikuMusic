package com.miku.player

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OS-level USB DAC state client for Miku Music.
 * Interacts with system settings and launches the system USB DAC interface.
 */
object UsbDacManager {
    fun isActive(ctx: Context): Boolean {
        val workMode = try {
            Settings.Global.getString(ctx.contentResolver, "work_mode")
        } catch (_: Throwable) { null }
        return "dacin" == workMode
    }

    fun getSampleRate(ctx: Context): Int = 192000
    fun getBitDepth(ctx: Context): Int = 32

    suspend fun setUsbDacMode(ctx: Context, enabled: Boolean, rate: Int = 192000, bits: Int = 32): Boolean = withContext(Dispatchers.IO) {
        try {
            if (enabled) {
                Settings.Global.putString(ctx.contentResolver, "work_mode", "dacin")
                RootShell.execFast("setprop vendor.usb.uac2.function.start 1; setprop vendor.usb.port_type sink")
                val intent = Intent("com.m500.hardware.action.USB_DAC_HUD").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    ctx.startActivity(intent)
                } catch (_: Throwable) {
                    val fallback = ctx.packageManager.getLaunchIntentForPackage("com.m500.hardware")
                    if (fallback != null) ctx.startActivity(fallback)
                }
            } else {
                Settings.Global.putString(ctx.contentResolver, "work_mode", "android")
                RootShell.execFast("setprop vendor.usb.uac2.function.start 0; setprop sys.usb.config mtp,adb")
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
