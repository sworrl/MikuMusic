package com.miku.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hatsune Miku System-Wide Pocket Lock & Hardware Fn Switch Manager.
 * Solves the hardware limitation where Touch + Key Lock only executed one mode.
 * Simultaneously locks/unlocks:
 * 1. Touchscreen Digitizer (via `vendor.audio.hw.set.disable_touch` and `libinputflinger.so`)
 * 2. Physical Transport Buttons (via `Settings.Global.button_lock`)
 * 3. Physical Power Button (via `/dev/input/event0` kernel permission masking)
 * 4. Rotary Volume Wheel (via `/dev/input/event2` configurable pass-through)
 */
object MikuPocketLockManager {
    private const val TAG = "MikuPocketLock"

    const val ACTION_FN_BUTTON_STATE_CHANGE = "FN_BUTTON_STATE_CHANGE"
    const val EXTRA_FN_COVERED = "fnCoverd"

    const val SETTING_FN_SETTINGS = "fn_settings"
    const val SETTING_FN_STATUS = "fn_status"
    const val SETTING_BUTTON_LOCK = "button_lock"
    const val PREF_ALLOW_VOLUME_WHEEL = "m500_fn_allow_volume_wheel" // 0 = off, 1 = on
    const val PREF_LOCK_POWER_BUTTON = "m500_fn_lock_power_button"   // 1 = on, 0 = off

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private var lastHandledStatus = -1

    fun init(ctx: Context) {
        if (isInitialized) return
        isInitialized = true

        val cr = ctx.applicationContext.contentResolver

        // 1. Observe Settings.Global.fn_status for real-time kernel hardware switch transitions
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val status = try {
                    Settings.Global.getInt(cr, SETTING_FN_STATUS, 0)
                } catch (_: Throwable) { 0 }
                if (status != lastHandledStatus) {
                    lastHandledStatus = status
                    applyLockState(ctx.applicationContext, isLocked = status == 1)
                }
            }
        }

        try {
            cr.registerContentObserver(
                Settings.Global.getUriFor(SETTING_FN_STATUS),
                false,
                observer
            )
        } catch (_: Throwable) {}

        // Initial sync on startup
        val currentStatus = try {
            Settings.Global.getInt(cr, SETTING_FN_STATUS, 0)
        } catch (_: Throwable) { 0 }
        lastHandledStatus = currentStatus
        if (currentStatus == 1) {
            applyLockState(ctx.applicationContext, isLocked = true, showHud = false)
        } else {
            // Ensure hardware devices and system media_lock / volume_lock are unmasked on boot
            try {
                Settings.System.putInt(cr, "media_lock", 0)
                Settings.System.putInt(cr, "volume_lock", 0)
                Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 0)
            } catch (_: Throwable) {}
            RootShell.execFast("chmod 666 /dev/input/event*; setprop vendor.audio.hw.set.disable_touch false; settings put system media_lock 0; settings put system volume_lock 0")
        }
    }

    fun handleBroadcastIntent(ctx: Context, intent: Intent) {
        if (ACTION_FN_BUTTON_STATE_CHANGE == intent.action) {
            val isCovered = intent.getBooleanExtra(EXTRA_FN_COVERED, false)
            val cr = ctx.applicationContext.contentResolver
            try {
                Settings.Global.putInt(cr, SETTING_FN_STATUS, if (isCovered) 1 else 0)
            } catch (_: Throwable) {}
            lastHandledStatus = if (isCovered) 1 else 0
            applyLockState(ctx.applicationContext, isLocked = isCovered)
        }
    }

    fun applyLockState(ctx: Context, isLocked: Boolean, showHud: Boolean = true) {
        scope.launch {
            val cr = ctx.contentResolver
            val fnMode = try {
                Settings.Global.getString(cr, SETTING_FN_SETTINGS) ?: "touch_and_key_lock"
            } catch (_: Throwable) { "touch_and_key_lock" }

            val lockPower = try {
                Settings.Global.getInt(cr, PREF_LOCK_POWER_BUTTON, 1) == 1
            } catch (_: Throwable) { true }

            val allowVolume = try {
                Settings.Global.getInt(cr, PREF_ALLOW_VOLUME_WHEEL, 0) == 1
            } catch (_: Throwable) { false }

            Log.i(TAG, "Applying Pocket Lock State: isLocked=$isLocked, mode=$fnMode, lockPower=$lockPower, allowVolume=$allowVolume")

            if (isLocked) {
                // ==================== LOCK ACTIVE ====================
                when (fnMode) {
                    "touch_and_key_lock" -> {
                        try { Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 1) } catch (_: Throwable) {}
                        // Root-free digitizer lock (the old setprop no-ops unrooted). +blank screen.
                        MikuInputLock.setTouch(ctx, enabled = false)
                        RootShell.execFast("setprop vendor.audio.hw.set.disable_touch true")
                        blankScreen(ctx)
                    }
                    "key_lock" -> {
                        try { Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 1) } catch (_: Throwable) {}
                        MikuInputLock.setTouch(ctx, enabled = true)
                        RootShell.execFast("setprop vendor.audio.hw.set.disable_touch false")
                    }
                    "touch_lock" -> {
                        try { Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 0) } catch (_: Throwable) {}
                        MikuInputLock.setTouch(ctx, enabled = false)
                        RootShell.execFast("setprop vendor.audio.hw.set.disable_touch true")
                        blankScreen(ctx)
                    }
                    else -> {
                        try { Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 1) } catch (_: Throwable) {}
                        MikuInputLock.setTouch(ctx, enabled = false)
                        RootShell.execFast("setprop vendor.audio.hw.set.disable_touch true")
                        blankScreen(ctx)
                    }
                }

                // 3. Block physical Power Button (/dev/input/event0)
                if (lockPower) {
                    RootShell.execFast("chmod 000 /dev/input/event0")
                } else {
                    RootShell.execFast("chmod 660 /dev/input/event0")
                }

                // 4. Volume wheel rotary encoder (/dev/input/event2)
                if (!allowVolume && fnMode != "touch_lock") {
                    MikuInputLock.setWheel(ctx, enabled = false)
                    RootShell.execFast("chmod 000 /dev/input/event2")
                } else {
                    MikuInputLock.setWheel(ctx, enabled = true)
                    RootShell.execFast("chmod 660 /dev/input/event2")
                }
            } else {
                // ==================== UNLOCKED ====================
                try {
                    Settings.Global.putInt(cr, SETTING_BUTTON_LOCK, 0)
                    Settings.System.putInt(cr, "media_lock", 0)
                    Settings.System.putInt(cr, "volume_lock", 0)
                } catch (_: Throwable) {}
                MikuInputLock.enableAll(ctx)
                RootShell.execFast("setprop vendor.audio.hw.set.disable_touch false; chmod 666 /dev/input/event*; settings put system media_lock 0; settings put system volume_lock 0")
            }

            // Visual feedback & animated cyber HUD overlay (only when screen is awake)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isInteractive = pm?.isInteractive ?: true
            if (showHud && isInteractive) {
                try {
                    val hudIntent = Intent(ctx, FnLockHudActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(FnLockHudActivity.EXTRA_IS_LOCKED, isLocked)
                        putExtra(FnLockHudActivity.EXTRA_ALLOW_VOLUME, allowVolume)
                        putExtra(FnLockHudActivity.EXTRA_LOCK_POWER, lockPower)
                    }
                    ctx.startActivity(hudIntent)
                } catch (t: Throwable) {
                    Log.w(TAG, "HUD launch error: ${t.message}")
                }
            }
        }
    }

    /** Blank the screen on lock (root-free; DEVICE_POWER via the platform key). goToSleep is @hide. */
    private fun blankScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            val m = android.os.PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(pm, android.os.SystemClock.uptimeMillis())
            Log.i(TAG, "blankScreen: goToSleep issued")
        } catch (t: Throwable) {
            Log.w(TAG, "blankScreen failed (no DEVICE_POWER?): $t")
        }
    }
}

/** BroadcastReceiver registered in manifest for FN_BUTTON_STATE_CHANGE */
class MikuFnLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MikuPocketLockManager.handleBroadcastIntent(context, intent)
    }
}
