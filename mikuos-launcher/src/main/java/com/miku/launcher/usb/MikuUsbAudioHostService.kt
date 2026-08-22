package com.miku.launcher.usb

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * System-Wide Persistent USB Audio & Host Controller Service.
 * Manages UAC2 Direct ALSA bypass, MTP configurations, and host PC connection events.
 */
class MikuUsbAudioHostService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var usbReceiver: BroadcastReceiver? = null

    companion object {
        private val _usbConnectedEvents = MutableSharedFlow<Boolean>(replay = 0)
        val usbConnectedEvents: SharedFlow<Boolean> = _usbConnectedEvents.asSharedFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context, MikuUsbAudioHostService::class.java)
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerUsbReceiver()
    }

    private fun registerUsbReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.hardware.usb.action.USB_STATE") {
                    val connected = intent.getBooleanExtra("connected", false)
                    val configured = intent.getBooleanExtra("configured", false)
                    val ctx = context ?: return

                    if (connected && configured) {
                        scope.launch {
                            val savedMode = MikuUsbPreferences.getSavedMode(ctx)
                            val shouldPrompt = MikuUsbPreferences.shouldShowPrompt(ctx)

                            if (shouldPrompt) {
                                _usbConnectedEvents.emit(true)
                            } else {
                                // Apply saved mode silently
                                when (savedMode) {
                                    MikuUsbPreferences.MODE_DAC -> MikuUsbAudioHostManager.setUsbDacMode(ctx, true)
                                    MikuUsbPreferences.MODE_MTP -> MikuUsbAudioHostManager.setMtpMode(ctx)
                                    MikuUsbPreferences.MODE_CHARGE -> MikuUsbAudioHostManager.setChargeOnlyMode(ctx)
                                }
                            }
                        }
                    }
                }
            }
        }
        usbReceiver = receiver
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        usbReceiver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
