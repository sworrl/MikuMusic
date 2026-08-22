package com.m500.hardware

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Platform Dual-Die (Red + Blue) Pulsar Engine for M500 Quick Settings & Hardware Settings.
 */
object PulsarLight {
    private const val TAG = "PulsarLight"
    private const val PREFS_NAME = "m500_hardware_prefs"
    private const val KEY_ENABLED = "m500_pulsar_enabled"
    private const val KEY_MODE = "m500_pulsar_mode"
    private const val KEY_BRIGHTNESS = "m500_pulsar_brightness"

    private const val SYSFS_RED = "/sys/class/leds/red/brightness"
    private const val SYSFS_BLUE = "/sys/class/leds/blue/brightness"
    private const val SYSFS_SGM_BRIGHT = "/sys/class/leds/sgm31324-leds/brightness"
    private const val SYSFS_SGM_RGB = "/sys/class/leds/sgm31324-leds/rgb_val"
    private const val SYSFS_SGM_PATTERN = "/sys/class/leds/sgm31324-leds/led_pattern"

    enum class Mode(val id: String, val label: String, val description: String) {
        PURPLE_TEAL_FADE("purple_teal_fade", "Dual-Die Chroma Wave", "Liquid continuous morph through Red ↔ Magenta ↔ Purple ↔ Blue"),
        DUAL_CHROMA_WAVE("chroma_rainbow", "Dual-Die Chroma Wave", "Liquid continuous morph through Red ↔ Magenta ↔ Purple ↔ Blue"),
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Dynamic format color and tempo pulse"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Dual-pulse heartbeat glow in cyber violet/magenta"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Deep analog sine-wave brightness breathing in Miku Blue"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Solid futuristic Miku Cyan-Blue"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Continuous chromatic gauge from Red (empty) to Cyan-Blue (full)"),
        OFF("off", "Off", "Pulsar indicator disabled")
    }

    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, true)
    }

    fun getMode(ctx: Context): Mode {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = sp.getString(KEY_MODE, Mode.PURPLE_TEAL_FADE.id) ?: Mode.PURPLE_TEAL_FADE.id
        return Mode.values().firstOrNull { it.id == id } ?: Mode.PURPLE_TEAL_FADE
    }

    fun getBrightness(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_BRIGHTNESS, 255).coerceIn(10, 255)
    }

    suspend fun setEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply()
        try {
            android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", if (enabled) 1 else 0)
        } catch (_: Throwable) {}
        if (enabled) {
            applyMode(ctx, getMode(ctx), getBrightness(ctx))
        } else {
            turnOff(ctx)
        }
    }

    suspend fun setMode(ctx: Context, mode: Mode) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_MODE, mode.id).apply()
        applyMode(ctx, mode, getBrightness(ctx))
    }

    suspend fun setBrightness(ctx: Context, brightness: Int) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_BRIGHTNESS, brightness).apply()
        applyMode(ctx, getMode(ctx), brightness)
    }

    suspend fun setManualRgb(r: Int, g: Int, b: Int, brightness: Int) = withContext(Dispatchers.IO) {
        activeJob?.cancel()
        writeDual(r, b, brightness)
    }

    suspend fun setManualDual(red: Int, blue: Int, brightness: Int) = withContext(Dispatchers.IO) {
        activeJob?.cancel()
        writeDual(red, blue, brightness)
    }

    fun setSystemProperty(key: String, value: String): Boolean {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val set = c.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, value)
            true
        } catch (_: Throwable) {
            RootShell.execFast("setprop $key '$value'")
            true
        }
    }

    private fun turnOff(ctx: Context? = null) {
        activeJob?.cancel()
        if (ctx != null) {
            try {
                android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", 0)
            } catch (_: Throwable) {}
        }
        setSystemProperty("vendor.audio.hiby.hw.led", "off")
        RootShell.execFast("echo 0 > $SYSFS_RED; echo 0 > /sys/class/leds/green/brightness; echo 0 > $SYSFS_BLUE; echo 0 > $SYSFS_SGM_BRIGHT; echo 0 > $SYSFS_SGM_PATTERN; setprop vendor.audio.hiby.hw.led off")
    }

    private fun writeDual(r: Int, b: Int, brightness: Int) {
        val scale = brightness.coerceIn(0, 255) / 255f
        val fr = (r.coerceIn(0, 255) * scale).toInt()
        val fb = (b.coerceIn(0, 255) * scale).toInt()

        RootShell.execFast("setprop vendor.audio.hiby.hw.led on; echo $fr > $SYSFS_RED; echo 0 > /sys/class/leds/green/brightness; echo $fb > $SYSFS_BLUE; echo \"$fr 0 $fb\" > $SYSFS_SGM_RGB; echo $brightness > $SYSFS_SGM_BRIGHT")
    }

    private fun applyMode(ctx: Context, mode: Mode, brightness: Int) {
        activeJob?.cancel()
        try {
            android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", if (mode != Mode.OFF) 1 else 0)
        } catch (_: Throwable) {}

        when (mode) {
            Mode.OFF -> turnOff(ctx)
            Mode.SIGNATURE_TEAL -> writeDual(0, 255, brightness)
            Mode.PURPLE_TEAL_FADE, Mode.DUAL_CHROMA_WAVE -> startChromaWave(brightness)
            Mode.SMOOTH_BREATHING -> startBreathing(0, 255, brightness, 2400)
            Mode.CYBER_HEARTBEAT -> startHeartbeat(85, 255, brightness)
            Mode.BATTERY_MONITOR -> startBatteryGlow(ctx, brightness)
            Mode.AUDIOPHILE_AUTO -> startBreathing(25, 255, brightness, 2800)
        }
    }

    private fun startChromaWave(peakBrightness: Int) {
        val totalSteps = 60
        activeJob = scope.launch {
            try {
                var step = 0
                while (isActive) {
                    val angle = (step.toDouble() / totalSteps) * 2.0 * Math.PI
                    val rFactor = ((sin(angle) + 1.0) / 2.0).toFloat()
                    val bFactor = ((cos(angle) + 1.0) / 2.0).toFloat()

                    val r = (rFactor * 255f).toInt().coerceIn(0, 255)
                    val b = (bFactor * 255f).toInt().coerceIn(0, 255)

                    writeDual(r, b, peakBrightness)
                    step = (step + 1) % totalSteps
                    delay(35)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startBreathing(r: Int, b: Int, peakBrightness: Int, periodMs: Long) {
        val totalSteps = 30
        val stepDelayMs = (periodMs / totalSteps).coerceAtLeast(20)
        activeJob = scope.launch {
            try {
                var step = 0
                while (isActive) {
                    val angle = (step.toDouble() / totalSteps) * 2.0 * Math.PI
                    val factor = ((sin(angle) + 1.0) / 2.0 * 0.85 + 0.15).toFloat()
                    val curB = (peakBrightness * factor).toInt().coerceIn(5, peakBrightness)

                    writeDual(r, b, curB)
                    step = (step + 1) % totalSteps
                    delay(stepDelayMs)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startHeartbeat(r: Int, b: Int, peakBrightness: Int) {
        activeJob = scope.launch {
            try {
                while (isActive) {
                    writeDual(r, b, peakBrightness)
                    delay(120)
                    writeDual(r, b, (peakBrightness * 0.20f).toInt())
                    delay(90)
                    writeDual(r, b, (peakBrightness * 0.70f).toInt())
                    delay(130)
                    writeDual(r, b, (peakBrightness * 0.05f).toInt())
                    delay(750)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startBatteryGlow(ctx: Context, peakBrightness: Int) {
        activeJob = scope.launch {
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val level = (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50).coerceIn(0, 100)
                val t = level / 100f
                val r = ((1f - t) * 255f).toInt().coerceIn(0, 255)
                val b = (t * 255f).toInt().coerceIn(0, 255)
                writeDual(r, b, peakBrightness)
            } catch (_: CancellationException) {}
        }
    }
}
