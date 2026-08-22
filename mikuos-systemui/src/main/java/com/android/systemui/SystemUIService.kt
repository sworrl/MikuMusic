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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MikuOS SystemUIService running (START_STICKY).")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
