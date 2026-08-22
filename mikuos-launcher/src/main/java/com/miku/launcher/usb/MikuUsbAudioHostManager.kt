package com.miku.launcher.usb

import android.content.Context
import android.provider.Settings
import com.miku.launcher.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MikuUsbAudioHostManager {
    fun isActive(ctx: Context): Boolean {
        val workMode = try {
            Settings.Global.getString(ctx.contentResolver, "work_mode")
        } catch (_: Throwable) { null }
        return "dacin" == workMode
    }

    suspend fun setUsbDacMode(ctx: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            if (enabled) {
                Settings.Global.putString(ctx.contentResolver, "work_mode", "dacin")
                RootShell.execFast("setprop vendor.usb.uac2.function.start 1; setprop vendor.usb.port_type sink; setprop sys.usb.config uac2,adb")
            } else {
                Settings.Global.putString(ctx.contentResolver, "work_mode", "android")
                RootShell.execFast("setprop vendor.usb.uac2.function.start 0; setprop sys.usb.config mtp,adb")
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun setMtpMode(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Settings.Global.putString(ctx.contentResolver, "work_mode", "android")
            RootShell.execFast("setprop vendor.usb.uac2.function.start 0; setprop sys.usb.config mtp,adb")
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun setChargeOnlyMode(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Settings.Global.putString(ctx.contentResolver, "work_mode", "android")
            RootShell.execFast("setprop vendor.usb.uac2.function.start 0; setprop sys.usb.config none")
            true
        } catch (_: Throwable) {
            false
        }
    }
}
