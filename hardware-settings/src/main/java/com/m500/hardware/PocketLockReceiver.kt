package com.m500.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class PocketLockReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PocketLockReceiver"
        const val ACTION_FN_BUTTON_STATE_CHANGE = "FN_BUTTON_STATE_CHANGE"
        const val EXTRA_FN_COVERED = "fnCoverd"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_FN_BUTTON_STATE_CHANGE) return

        val isCovered = when {
            intent.hasExtra("fnCoverd") -> intent.getBooleanExtra("fnCoverd", false)
            intent.hasExtra("isCovered") -> intent.getBooleanExtra("isCovered", false)
            intent.hasExtra("state") -> intent.getBooleanExtra("state", false)
            else -> false
        }
        Log.i(TAG, "Hardware Fn Switch state changed: isCovered=$isCovered")

        val cr = context.contentResolver
        try {
            Settings.Global.putInt(cr, "fn_status", if (isCovered) 1 else 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update fn_status setting", e)
        }

        val fnMode = Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
        val allowVolume = Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) == 1

        Log.i(TAG, "Handling Fn mode: $fnMode, isCovered=$isCovered, allowVolume=$allowVolume")

        if (fnMode == "key_lock" || fnMode == "touch_and_key_lock" || fnMode == "Both") {
            CoroutineScope(Dispatchers.IO).launch {
                handlePocketLock(context, isCovered, allowVolume)
            }
        }
    }

    private fun handlePocketLock(context: Context, isCovered: Boolean, allowVolume: Boolean) {
        try {
            if (isCovered) {
                Log.i(TAG, "Engaging Pocket Lock (Touch, Power & Buttons Locked, Volume Allowed=$allowVolume)")

                val keyMode = if (allowVolume) "sw_user" else "all"
                val rootCmd = """
                    setprop vendor.audio.hw.set.disable_touch true
                    setprop vendor.audio.hiby.tp_gesture enable
                    setprop vendor.audio.hw.gpiokey_state_update $keyMode
                    setprop vendor.audio.hw.set.mute "speaker off"
                    echo "$keyMode" > /sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys 2>/dev/null
                    echo "speaker off" > /sys/devices/platform/soc/soc:hiby,sound-plat/mute 2>/dev/null
                    echo 1 > /sys/class/input/input0/inhibited 2>/dev/null
                """.trimIndent()
                RootShell.execFast(rootCmd)

                try { Settings.Global.putInt(context.contentResolver, "button_lock", 1) } catch (_: Throwable) {}
                try { Settings.Global.putInt(context.contentResolver, "speaker_mute_status", 0) } catch (_: Throwable) {}
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Throwable) {}
                try {
                    context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Throwable) {}

                // Fullscreen WindowManager Cyber Pocket Lock Touch Shield
                PocketLockService.start(context, isLocked = true, allowVol = allowVolume)
            } else {
                Log.i(TAG, "Releasing Pocket Lock (Uninhibiting all inputs)")

                // Remove Cyber Pocket Lock Touch Shield
                PocketLockService.start(context, isLocked = false, allowVol = allowVolume)

                val rootCmd = """
                    setprop vendor.audio.hw.set.disable_touch false
                    setprop vendor.audio.hiby.tp_gesture disable
                    setprop vendor.audio.hw.gpiokey_state_update none
                    setprop vendor.audio.hw.set.mute "speaker off"
                    echo "none" > /sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys 2>/dev/null
                    echo "speaker off" > /sys/devices/platform/soc/soc:hiby,sound-plat/mute 2>/dev/null
                    echo 0 > /sys/class/input/input0/inhibited 2>/dev/null
                    for i in /sys/class/input/input*/inhibited; do echo 0 > ${'$'}i 2>/dev/null; done
                """.trimIndent()
                RootShell.execFast(rootCmd)

                try { Settings.Global.putInt(context.contentResolver, "button_lock", 0) } catch (_: Throwable) {}
                try { Settings.Global.putInt(context.contentResolver, "speaker_mute_status", 0) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing Pocket Lock transition", e)
        }
    }

    private fun inhibitInputNodes(inhibit: Boolean) {
        val value = if (inhibit) "1" else "0"
        val inputDir = File("/sys/class/input")
        if (inputDir.exists() && inputDir.isDirectory) {
            inputDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("input")) {
                    val node = File(file, "inhibited")
                    if (node.exists()) {
                        directWrite(node.absolutePath, value)
                    }
                }
            }
        }
    }

    private fun directWrite(path: String, value: String) {
        try {
            val f = File(path)
            if (f.exists()) {
                FileOutputStream(f).use { fos ->
                    fos.write(value.toByteArray())
                    fos.flush()
                }
            }
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '$value' > $path"))
            } catch (_: Throwable) {}
        }
    }

    private fun setProp(key: String, value: String) {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val setMethod = spClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
        } catch (_: Throwable) {}
    }
}
