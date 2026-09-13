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

    /**
     * IMPORTANT — HONESTY NOTE: the M500's RGB "Pulsar Light" indicator is confirmed NON-FUNCTIONAL
     * on this unit. The LED sysfs nodes are SELinux-denied to this process, there is no consumer LED
     * service to ask, and the vendor factory-test config that drove it is absent. Every write below
     * is therefore a no-op in practice, so NO mode description may promise a visible effect.
     *
     * The descriptions used to advertise breathing / BPM pulsing / a battery gauge / a continuous
     * chroma crossfade. Two of those were never even implemented (applyMode writes ONE static colour
     * pair per mode, it does not crossfade or breathe), and none of them can light anything on this
     * hardware. They now state the stored intent and that the hardware does not respond.
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

    /**
     * null = the user has never chosen a pattern. DISPLAY surfaces must use this: [getMode] falls
     * back to AUDIOPHILE_AUTO so the engine has something to run, and the hardware card was printing
     * that fallback as the user's "Configured Pattern".
     */
    fun getModeOrNull(ctx: Context): Mode? {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        val id = sp.getString(PREFS_KEY_MODE, null) ?: return null
        return Mode.values().firstOrNull { it.id == id }
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

    /**
     * Is the indicator actually writable? Probed ONCE, without `su` (see the no-root directive):
     * if the sysfs nodes aren't there for us, every write in this object is a no-op and the BPM loop
     * is pure waste. It used to run regardless — `delay(110L)` around a `writeDual` that shelled out
     * to a nonexistent `su`, plus an `isDeviceCharging()` sticky-broadcast binder call, ~9x a second
     * forever. Measured cost: the launcher's main thread pinned while the user was listening, which
     * starved Miku Music's render thread and culled the whole projectM preset library as "slow".
     */
    private val ledWritable: Boolean by lazy {
        val ok = sequenceOf("$SYSFS_RED/brightness", "$SYSFS_BLUE/brightness", "$SYSFS_SGM/red_current")
            .any { path -> runCatching { java.io.File(path).canWrite() }.getOrDefault(false) }
        if (!ok) Log.i(TAG, "Pulsar indicator not writable by this process — LED effects disabled (see PulsarLight KDoc)")
        ok
    }

    fun startBpmSync(ctx: Context) {
        if (bpmJob?.isActive == true) return
        if (!ledWritable) return          // dead hardware on this unit: never spin the loop
        com.miku.launcher.bpm.MikuBpmEngine.startListening(ctx)
        bpmJob = bpmScope.launch {
            var chargeStep = 0
            // Charging state arrives by broadcast instead of a registerReceiver() binder round-trip
            // on every one of these iterations.
            var charging = isDeviceCharging(ctx)
            val chargeWatcher = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) {
                    val st = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        st == android.os.BatteryManager.BATTERY_STATUS_FULL
                }
            }
            runCatching {
                ctx.registerReceiver(chargeWatcher, android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                    addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
                })
            }
            try {
            while (isActive) {
                val mode = getMode(ctx)
                val isBpmEnabled = isBpmSyncEnabled(ctx)
                val bpmState = com.miku.launcher.bpm.MikuBpmEngine.state.value
                val isCharging = charging
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
            } finally {
                runCatching { ctx.unregisterReceiver(chargeWatcher) }
            }
        }
    }

    // REMOVED: startHddActivity()/stopHddActivity(). They drove the indicator with Random blip
    // bursts presented as "HDD activity" while being tied to no actual storage I/O counter at all —
    // pure fabricated telemetry. Both were unreferenced. If a real activity light is ever wanted it
    // must be driven from a real source (e.g. deltas of the device's own diskstats), not Random.

    // ================================================================= OS-level Pulsar API
    // Miku Music no longer drives the diode; it broadcasts to PulsarReceiver, which calls these.
    // Every one is a no-op unless the indicator is genuinely writable, so a dead LED costs nothing.

    /** Is the diode actually drivable by this process? Published to clients as miku_pulsar_hw_writable. */
    fun isHardwareWritable(): Boolean = ledWritable

    /** Momentary double-pulse when a track is liked/unliked. */
    fun indicateHearted(ctx: Context, hearted: Boolean) {
        if (!ledWritable) return
        val (r, b) = if (hearted) Pair(255, 100) else Pair(85, 255)
        bpmScope.launch {
            writeDual(r, b, 255); delay(100)
            writeDual(r, b, 50);  delay(80)
            writeDual(r, b, 255); delay(120)
            writeDual(0, 0, 0);   delay(100)
            applyMode(ctx, getMode(ctx), getBrightness(ctx))
        }
    }

    /** Three short blinks when the pocket/button lock is engaged or released. */
    fun indicatePocketLock(ctx: Context, locked: Boolean) {
        if (!ledWritable) return
        val (r, b) = if (locked) Pair(255, 0) else Pair(0, 255)
        bpmScope.launch {
            repeat(3) {
                writeDual(r, b, 255); delay(70)
                writeDual(0, 0, 0);   delay(70)
            }
            applyMode(ctx, getMode(ctx), getBrightness(ctx))
        }
    }

    /**
     * The player reports what it is playing and how it classified the format; the colour decision
     * lives here. [tierName] is a com.miku.player AudioFormatTier name, or "NONE".
     */
    fun onPlaybackState(ctx: Context, isPlaying: Boolean, tierName: String) {
        if (!ledWritable) return
        if (!isPlaying) { applyMode(ctx, getMode(ctx), getBrightness(ctx)); return }
        val color = when (tierName) {
            "DSD" -> DualPalette.CYBER_MAGENTA
            "MQA_STUDIO", "HI_RES" -> DualPalette.VIOLET_LAVENDER
            "ULTRA_HI_RES" -> DualPalette.ROYAL_PURPLE
            "CD_LOSSLESS" -> DualPalette.MIKU_BLUE
            else -> DualColor(0, 150, "Soft Blue")
        }
        if (getMode(ctx) == Mode.AUDIOPHILE_AUTO) writeDual(color.r, color.b, getBrightness(ctx))
    }
}
