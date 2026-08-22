package com.miku.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

object PulsarLight {
    private const val TAG = "MikuOS_Pulsar"
    private const val SYSFS_RED = "/sys/class/leds/red"
    private const val SYSFS_BLUE = "/sys/class/leds/blue"
    private const val SYSFS_SGM = "/sys/class/leds/sgm31324-leds"

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

    enum class Mode(val id: String, val label: String, val description: String) {
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Colors LED by audio format tier & pulses to track tempo"),
        CHROMA_RAINBOW("chroma_rainbow", "Dual-Die Chroma Wave", "Hypnotic continuous crossfade through Red ↔ Magenta ↔ Purple ↔ Blue"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Dual-pulse heartbeat glow in cyber violet/magenta"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Deep analog sine-wave brightness breathing in Miku Blue"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Solid futuristic Miku Cyan-Blue"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Continuous chromatic gauge from Red (empty) to Cyan-Blue (full)"),
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
    }

    fun isBpmSyncEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean(PREFS_KEY_BPM_SYNC, true)
    }

    fun setBpmSyncEnabled(ctx: Context, enabled: Boolean) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean(PREFS_KEY_BPM_SYNC, enabled).apply()
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
