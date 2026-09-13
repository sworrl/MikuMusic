package com.miku.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

object PulsarLight {
    private const val TAG = "MikuOS_Pulsar"
    private const val SYSFS_RED = "/sys/class/leds/red"
    private const val SYSFS_BLUE = "/sys/class/leds/blue"
    private const val SYSFS_SGM = "/sys/class/leds/sgm31324-leds"

    /**
     * The front indicator is owned by the OS (com.miku.launcher/.PulsarReceiver); this process only
     * asks. Before the fake-data sweep the setters below wrote this app's OWN SharedPreferences and
     * nothing else, so a mode/brightness/BPM change here never reached the engine that drives the
     * diode (different app, different sandbox) - the controls looked applied and were inert.
     */
    const val ACTION_PULSAR = "com.miku.launcher.action.PULSAR"
    private const val EXTRA_OP = "op"
    private const val EXTRA_VALUE = "value"

    /** Published by the OS. 1 = the diode is really drivable. Absent/0 = it will NOT respond. */
    private const val GLOBAL_HW_WRITABLE = "miku_pulsar_hw_writable"

    /**
     * Does the OS report the LED as actually drivable? Default FALSE: never imply the light
     * responded. On this unit the nodes are SELinux-locked, so this is normally false and UI must
     * say so rather than show a mode as if it were lit.
     */
    fun isHardwareWritable(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            ctx.applicationContext.contentResolver, GLOBAL_HW_WRITABLE, 0) == 1
    }.getOrDefault(false)

    private fun sendToOs(ctx: Context, op: String, value: String? = null) {
        runCatching {
            val i = android.content.Intent(ACTION_PULSAR)
                .setPackage("com.miku.launcher")
                .putExtra(EXTRA_OP, op)
            if (value != null) i.putExtra(EXTRA_VALUE, value)
            ctx.applicationContext.sendBroadcast(i)
        }
    }

    private const val PREFS_KEY_MODE = "m500_pulsar_mode"
    private const val PREFS_KEY_BRIGHTNESS = "m500_pulsar_brightness"
    private const val PREFS_KEY_BPM_SYNC = "m500_pulsar_bpm_sync"

    data class DualColor(val r: Int, val b: Int, val name: String)

    object DualPalette {
        val MIKU_BLUE = DualColor(0, 255, "Signature Miku Blue")
        val ICE_CYAN = DualColor(25, 255, "Electric Ice Cyan")
        val VIOLET_LAVENDER = DualColor(85, 255, "Holographic Violet")
        val ROYAL_PURPLE = DualColor(160, 255, "Royal Purple")
        val CYBER_MAGENTA = DualColor(255, 190, "Cyber Magenta")
        val MIKU_PINK = DualColor(255, 100, "Miku Hot Pink")
        val CRIMSON_RED = DualColor(255, 0, "Crimson Red")
    }

    /**
     * HONESTY NOTE (fake-data sweep): these descriptions used to promise a tempo pulse, a
     * "hypnotic continuous crossfade", a "dual-pulse heartbeat", "sine-wave brightness breathing"
     * and a "continuous chromatic gauge". None of that exists here: [applyMode] writes ONE static
     * colour pair per mode and never animates. On top of that the M500's RGB indicator is
     * confirmed non-functional on this unit (the LED sysfs nodes are SELinux-denied and the only
     * write path below is RootShell, which has no su to run), so nothing visible happens at all.
     * They now state the stored intent, matching the launcher's and the player's corrected copies.
     */
    enum class Mode(val id: String, val label: String, val description: String) {
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        CHROMA_RAINBOW("chroma_rainbow", "Dual-Die Chroma Wave", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        OFF("off", "Off", "Pulsar indicator disabled")
    }

    fun getMode(ctx: Context): Mode {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        val id = sp.getString(PREFS_KEY_MODE, Mode.AUDIOPHILE_AUTO.id) ?: Mode.AUDIOPHILE_AUTO.id
        return Mode.values().firstOrNull { it.id == id } ?: Mode.AUDIOPHILE_AUTO
    }

    fun setMode(ctx: Context, mode: Mode) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putString(PREFS_KEY_MODE, mode.id).apply()
        applyMode(ctx, mode, getBrightness(ctx))
        sendToOs(ctx, "mode", mode.id)          // was pref-only: never reached the OS engine
    }

    fun getBrightness(ctx: Context): Int {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getInt(PREFS_KEY_BRIGHTNESS, 220).coerceIn(10, 255)
    }

    fun setBrightness(ctx: Context, brightness: Int) {
        val clamped = brightness.coerceIn(10, 255)
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putInt(PREFS_KEY_BRIGHTNESS, clamped).apply()
        applyMode(ctx, getMode(ctx), clamped)
        sendToOs(ctx, "brightness", clamped.toString())   // was pref-only
    }

    fun isBpmSyncEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean(PREFS_KEY_BPM_SYNC, true)
    }

    fun setBpmSyncEnabled(ctx: Context, enabled: Boolean) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean(PREFS_KEY_BPM_SYNC, enabled).apply()
        // Was a dead write: the BPM engine lives in com.miku.launcher and reads ITS own prefs,
        // so this toggle changed nothing at all. Forward the op to the OS that owns the engine.
        sendToOs(ctx, "bpm_sync", if (enabled) "1" else "0")
    }

    fun applyMode(ctx: Context, mode: Mode, brightness: Int) {
        when (mode) {
            Mode.OFF -> {
                writeDual(0, 0, 0)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led off")
            }
            Mode.SIGNATURE_TEAL -> {
                writeDual(0, 255, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
            Mode.AUDIOPHILE_AUTO -> {
                writeDual(85, 255, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
            Mode.CHROMA_RAINBOW -> {
                writeDual(160, 255, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
            Mode.CYBER_HEARTBEAT -> {
                writeDual(255, 100, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
            Mode.SMOOTH_BREATHING -> {
                writeDual(25, 255, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
            Mode.BATTERY_MONITOR -> {
                writeDual(0, 255, brightness)
                RootShell.execFast("setprop vendor.audio.hiby.hw.led on")
            }
        }
    }

    fun writeDual(r: Int, b: Int, brightness: Int = 255) {
        val scale = (brightness.coerceIn(0, 255)) / 255.0f
        val scaledR = (r * scale).toInt().coerceIn(0, 255)
        val scaledB = (b * scale).toInt().coerceIn(0, 255)

        RootShell.execFast(
            "echo $scaledR > $SYSFS_RED/brightness 2>/dev/null; " +
            "echo $scaledB > $SYSFS_BLUE/brightness 2>/dev/null; " +
            "echo $scaledR > $SYSFS_SGM/red_current 2>/dev/null; " +
            "echo $scaledB > $SYSFS_SGM/blue_current 2>/dev/null"
        )
    }
}
