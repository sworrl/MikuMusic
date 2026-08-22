package com.m500.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PulsarReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SET_MODE = "com.m500.hardware.action.SET_PULSAR_MODE"
        const val ACTION_SET_RGB = "com.m500.hardware.action.SET_PULSAR_RGB"
        const val EXTRA_MODE = "mode"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_R = "r"
        const val EXTRA_G = "g"
        const val EXTRA_B = "b"
        private const val TAG = "PulsarReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Pulsar broadcast received: action=$action")

        val scope = CoroutineScope(Dispatchers.IO)

        when (action) {
            ACTION_SET_MODE -> {
                val modeStr = intent.getStringExtra(EXTRA_MODE) ?: "purple_teal_fade"
                val brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, PulsarLight.getBrightness(context))
                val targetMode = PulsarLight.Mode.values().firstOrNull { it.id.equals(modeStr, ignoreCase = true) }
                    ?: PulsarLight.Mode.PURPLE_TEAL_FADE

                scope.launch {
                    PulsarLight.setEnabled(context, true)
                    PulsarLight.setBrightness(context, brightness)
                    PulsarLight.setMode(context, targetMode)
                    Log.i(TAG, "Applied Pulsar mode=$targetMode, brightness=$brightness")
                }
            }
            ACTION_SET_RGB -> {
                val r = intent.getIntExtra(EXTRA_R, 0).coerceIn(0, 255)
                val g = intent.getIntExtra(EXTRA_G, 229).coerceIn(0, 255)
                val b = intent.getIntExtra(EXTRA_B, 255).coerceIn(0, 255)
                val brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, PulsarLight.getBrightness(context))

                scope.launch {
                    PulsarLight.setEnabled(context, true)
                    PulsarLight.setManualRgb(r, g, b, brightness)
                    Log.i(TAG, "Applied manual RGB: ($r, $g, $b), brightness=$brightness")
                }
            }
            "com.m500.hardware.action.SET_SAMPLE_QUALITY" -> {
                val quality = intent.getStringExtra("quality") ?: "mqb"
                PulsarLight.setSystemProperty("vendor.audio.hiby.hw.led", "on")
                PulsarLight.setSystemProperty("vendor.audio.hiby.hw.sample_quality", quality)
                Log.i(TAG, "Applied vendor.audio.hiby.hw.sample_quality=$quality")
            }
        }
    }
}
