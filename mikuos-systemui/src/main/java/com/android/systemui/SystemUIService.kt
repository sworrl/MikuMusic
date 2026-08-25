package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * AOSP-standard SystemUIService endpoint invoked by Android system_server on boot.
 * Keeps MikuOS SystemUI services, Cyber Status Bar HUD, and Shade active.
 */
class SystemUIService : Service() {

    companion object {
        private const val TAG = "MikuSystemUIService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MikuOS SystemUIService initialized by system_server.")
        startGestureService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MikuOS SystemUIService running (START_STICKY).")
        startGestureService()
        return START_STICKY
    }

    private fun startGestureService() {
        try {
            val gIntent = Intent().setClassName("com.miku.launcher", "com.miku.launcher.gesture.MikuSystemGestureService")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(gIntent)
            } else {
                startService(gIntent)
            }
        } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
