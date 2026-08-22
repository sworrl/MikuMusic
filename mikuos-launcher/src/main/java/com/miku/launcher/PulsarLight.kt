package com.miku.launcher

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

    private var hddJob: Job? = null
    private val hddScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bpmJob: Job? = null
    private val bpmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun isDeviceCharging(ctx: Context): Boolean {
        return try {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Throwable) { false }
    }

    fun startBpmSync(ctx: Context) {
        if (bpmJob?.isActive == true) return
        com.miku.launcher.bpm.MikuBpmEngine.startListening(ctx)
        bpmJob = bpmScope.launch {
            var chargeStep = 0
            while (isActive) {
                val mode = getMode(ctx)
                val isBpmEnabled = isBpmSyncEnabled(ctx)
                val bpmState = com.miku.launcher.bpm.MikuBpmEngine.state.value
                val isCharging = isDeviceCharging(ctx)
                val bright = getBrightness(ctx)

                if (mode == Mode.AUDIOPHILE_AUTO || mode == Mode.BATTERY_MONITOR) {
                    if (isBpmEnabled && bpmState.isPlaying) {
                        // Music is playing -> Snappy BPM beat pulse
                        val color = bpmState.dominantColor
                        val r = (color shr 16) and 0xFF
                        val b = color and 0xFF

                        // Fast snappy beat attack
                        writeDual(r, b, bright)
                        delay(110L)

                        // If charging at the same time: decay to solid emerald charging energy floor!
                        // If not charging: decay to low-alpha format color
                        if (isCharging) {
                            writeDual(0, 180, (bright * 0.28f).toInt().coerceAtLeast(25))
                        } else {
                            writeDual((r * 0.20f).toInt(), (b * 0.20f).toInt(), (bright * 0.25f).toInt())
                        }

                        val sleepMs = (bpmState.beatIntervalMs - 110L).coerceIn(120L, 1800L)
                        delay(sleepMs)
                    } else if (isCharging) {
                        // CHARGING ALONE (NO MUSIC):
                        // Ultra-slow 5.0-second deep analog surge (unmistakably distinct from music)
                        chargeStep = (chargeStep + 1) % 50
                        val progress = chargeStep / 50.0f
                        val sine = kotlin.math.sin(progress * Math.PI.toFloat())
                        val glow = (sine * 0.85f + 0.15f) * bright

                        if (chargeStep == 45 || chargeStep == 47) {
                            // High-tech quantum charge spark tick (30ms pip)
                            writeDual(0, 255, bright)
                            delay(30L)
                        } else {
                            // Mint emerald charging surge
                            writeDual(0, 220, glow.toInt())
                            delay(100L)
                        }
                    } else {
                        delay(300L)
                    }
                } else {
                    delay(500L)
                }
            }
        }
    }

    fun startHddActivity() {
        if (hddJob?.isActive == true) return
        hddJob = hddScope.launch {
            while (isActive) {
                val isBurst = (1..100).random() < 70
                val blips = if (isBurst) (2..6).random() else 1
                for (i in 0 until blips) {
                    val r = if ((1..2).random() == 1) 220 else 40
                    val b = if (r > 100) 40 else 255
                    writeDual(r, b, 240)
                    delay((10..35).random().toLong())
                    writeDual(0, 0, 0)
                    delay((15..45).random().toLong())
                }
                delay((40..250).random().toLong())
            }
        }
    }

    fun stopHddActivity(ctx: Context? = null) {
        hddJob?.cancel()
        hddJob = null
        if (ctx != null) {
            applyMode(ctx, getMode(ctx), getBrightness(ctx))
        } else {
            writeDual(0, 255, 200)
        }
    }
}
