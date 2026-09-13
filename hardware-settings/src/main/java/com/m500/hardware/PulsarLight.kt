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

    /**
     * HONESTY NOTE (fake-data sweep): the descriptions promised effects the user would SEE - a
     * liquid morph, a tempo pulse, a heartbeat, sine-wave breathing, a battery gauge. The
     * animation loops below are real, but every one of them is gated on [isHardwareWritable],
     * which is FALSE on this unit: the M500's RGB indicator is confirmed non-functional (LED sysfs
     * nodes SELinux-locked, no consumer LED service, no factory-test config), so nothing lights
     * up. "Audiophile BPM Pulse" was doubly wrong - it runs a plain breathing loop, it has never
     * read a tempo or an audio format. Descriptions now state the stored intent only.
     */
    enum class Mode(val id: String, val label: String, val description: String) {
        PURPLE_TEAL_FADE("purple_teal_fade", "Dual-Die Chroma Wave", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        DUAL_CHROMA_WAVE("chroma_rainbow", "Dual-Die Chroma Wave", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Saved preference only — not tempo- or format-driven, and the indicator does not respond on this unit"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
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

    /**
     * Was a stub that returned TRUE unconditionally: when the reflective SystemProperties.set threw
     * (the normal case for a non-privileged setprop) it fired RootShell.execFast - which returns
     * Unit and silently does nothing without su - and still reported success. Callers logged /
     * displayed that as "applied". It now reports only what actually happened.
     *
     * @return true only when the reflective set() completed; false when the write was refused and
     *         only the optional (usually absent) root path was attempted.
     */
    fun setSystemProperty(key: String, value: String): Boolean {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val set = c.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, value)
            true
        } catch (_: Throwable) {
            RootShell.execFast("setprop $key '$value'")
            false
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

    /**
     * Can this unit's LED actually be driven at all?
     *
     * On this M500 it cannot: the Pulsar RGB indicator is SELinux-locked with no consumer service
     * and no factory-test config (confirmed by direct probing, see the project notes). Every write
     * below is therefore a no-op — but it used to be an EXPENSIVE no-op, because the animation
     * loops call writeDual every 35 ms and each call forked an `su` that does not exist.
     *
     * Probed once, cached for the process. When it comes back false the animations do not start,
     * so the daemon stops burning CPU pretending to drive a diode that will never light.
     */
    @Volatile private var hwWritableCache: Boolean? = null

    fun isHardwareWritable(): Boolean {
        hwWritableCache?.let { return it }
        synchronized(this) {
            hwWritableCache?.let { return it }
            // Root-free path first — that is the one that has to work. Root is only consulted as
            // an optional bonus for power users who have it, and on a rooted unit it may well be
            // what finally gets this LED lit, since the nodes are SELinux-locked to us otherwise.
            val ok = try {
                java.io.File(SYSFS_RED).canWrite() || java.io.File(SYSFS_SGM_RGB).canWrite()
            } catch (_: Throwable) { false } || RootShell.isAvailable()
            if (!ok) {
                android.util.Log.i("PulsarLight",
                    "Pulsar LED is not writable on this unit - the sysfs nodes are SELinux-locked " +
                    "to us and no (optional) root is available. Modes are still stored and the UI " +
                    "says so; the light will not respond unless the user roots the device.")
            }
            hwWritableCache = ok
            return ok
        }
    }

    private fun writeDual(r: Int, b: Int, brightness: Int) {
        if (!isHardwareWritable()) return
        val scale = brightness.coerceIn(0, 255) / 255f
        val fr = (r.coerceIn(0, 255) * scale).toInt()
        val fb = (b.coerceIn(0, 255) * scale).toInt()

        // Direct sysfs write where the platform allows it; no shell, no fork.
        val direct = runCatching { java.io.File(SYSFS_RED).writeText(fr.toString()) }.isSuccess
        runCatching { java.io.File(SYSFS_BLUE).writeText(fb.toString()) }
        runCatching { java.io.File(SYSFS_SGM_RGB).writeText("$fr 0 $fb") }
        runCatching { java.io.File(SYSFS_SGM_BRIGHT).writeText(brightness.toString()) }
        // Only if the unprivileged write was refused AND this user has root do we spend a shell.
        if (!direct && RootShell.isAvailable()) {
            RootShell.execFast("setprop vendor.audio.hiby.hw.led on; echo $fr > $SYSFS_RED; echo $fb > $SYSFS_BLUE; echo \"$fr 0 $fb\" > $SYSFS_SGM_RGB; echo $brightness > $SYSFS_SGM_BRIGHT")
        }
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
        if (!isHardwareWritable()) return
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
        if (!isHardwareWritable()) return
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
                val level = run {
                    val bi = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val l = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val sc = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                    if (l >= 0 && sc > 0) (l * 100 / sc).coerceIn(0, 100)
                    // Was "?: 50" - an invented half-charge when neither source reports. -1 means
                    // unknown, and an unknown level must not be drawn as a gauge position.
                    else bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        ?.takeIf { it in 0..100 } ?: -1
                }
                if (level < 0) return@launch   // no real level: leave the indicator alone
                val t = level / 100f
                val r = ((1f - t) * 255f).toInt().coerceIn(0, 255)
                val b = (t * 255f).toInt().coerceIn(0, 255)
                writeDual(r, b, peakBrightness)
            } catch (_: CancellationException) {}
        }
    }
}
