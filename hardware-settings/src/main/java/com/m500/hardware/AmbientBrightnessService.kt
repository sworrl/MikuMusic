package com.m500.hardware

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the screen-on trigger for the camera-derived ambient light reading.
 *
 * ACTION_SCREEN_ON cannot be declared in a manifest (Android delivers it only
 * to runtime-registered receivers), so something resident has to hold the
 * registration. Started from BootCompletedReceiver.
 *
 * Sampling is deliberately tied to screen-on rather than a timer: see the
 * pocket/chicken-and-egg note in AmbientBrightnessManager. It also means the
 * camera is opened only when the user is actually looking at the device, which
 * keeps the Android 12+ camera privacy indicator from flashing at random.
 */
class AmbientBrightnessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    scope.launch {
                        try {
                            val applied = AmbientBrightnessManager.sampleAndApply(ctx)
                            if (applied != null) Log.i(TAG, "ambient brightness -> $applied")
                        } catch (t: Throwable) {
                            Log.w(TAG, "ambient sample failed", t)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!AmbientCamera.isAvailable(this)) {
            // No camera enumerable - the whole feature is inert on this
            // hardware. Stop rather than sit resident doing nothing.
            Log.w(TAG, "no camera on this device; ambient brightness disabled")
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        Log.i(TAG, "ambient brightness service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AmbientBrightnessSvc"

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, AmbientBrightnessService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "could not start ambient brightness service", t)
            }
        }
    }
}
