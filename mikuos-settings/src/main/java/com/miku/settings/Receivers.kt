package com.miku.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("MikuOS_Settings", "Boot received, initializing MikuOS hardware & settings")
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1)
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        } catch (_: Throwable) {}

        // Root HAL Daemon initialization
        RootShell.execFast(
            "settings put global adb_enabled 1; " +
            "settings put global development_settings_enabled 1; " +
            "settings put global adb_wifi_enabled 1; " +
            "setprop persist.sys.usb.config mtp,adb; " +
            "setprop service.adb.tcp.port 5555; " +
            "setprop persist.service.adb.enable 1; " +
            "stop adbd 2>/dev/null; start adbd 2>/dev/null"
        )

        val mode = PulsarLight.getMode(context)
        val bright = PulsarLight.getBrightness(context)
        PulsarLight.applyMode(context, mode, bright)
    }
}

class PulsarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "com.m500.hardware.action.SET_PULSAR_MODE") {
            val modeId = intent.getStringExtra("mode") ?: return
            val mode = PulsarLight.Mode.values().firstOrNull { it.id == modeId } ?: return
            PulsarLight.setMode(context, mode)
        } else if (action == "com.m500.hardware.action.SET_PULSAR_RGB") {
            val r = intent.getIntExtra("r", 0)
            val b = intent.getIntExtra("b", 255)
            val brightness = intent.getIntExtra("brightness", 255)
            PulsarLight.writeDual(r, b, brightness)
        }
    }
}
