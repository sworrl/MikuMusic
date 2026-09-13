package com.miku.player

import android.content.Context
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-Precision Dual-Die (Red + Blue) Chroma Matrix Engine for the M500 DAP's front Pulsar Light.
 * 
 * Hardware Architecture:
 * - The physical diode is a Dual-Die (Red + Blue) LED controlled via PWM duty cycles (0-255).
 * - There is no physical green die; all visible colors are mathematical blends of Red & Blue:
 *   • Pure Miku Blue / Azure: (R: 0, B: 255)
 *   • Electric Ice Cyan: (R: 25, B: 255)
 *   • Holographic Violet (96k Hi-Res): (R: 85, B: 255)
 *   • Deep Royal Purple (192k-768k Ultra Hi-Res): (R: 160, B: 255)
 *   • Cyber Magenta / Hot Pink (DSD Master): (R: 255, B: 190)
 *   • Crimson Red (Alert / Low Battery / Lock): (R: 255, B: 0)
 *   • Dual-Die Sinusoidal Quadrature Wave: Smooth continuous morphing through Red ↔ Magenta ↔ Purple ↔ Blue.
 */
object PulsarLight {
    private const val TAG = "PulsarLight"

    private const val SYSFS_RED = "/sys/class/leds/red"
    private const val SYSFS_BLUE = "/sys/class/leds/blue"
    private const val SYSFS_SGM = "/sys/class/leds/sgm31324-leds"

    private const val PREFS_KEY_MODE = "m500_pulsar_mode"
    private const val PREFS_KEY_BRIGHTNESS = "m500_pulsar_brightness"
    private const val PREFS_KEY_BPM_SYNC = "m500_pulsar_bpm_sync"
    private const val PREFS_KEY_ANIM_SPEED = "m500_pulsar_anim_speed"
    private const val PREFS_KEY_CUSTOM_HEX = "m500_pulsar_custom_hex"
    private const val PREFS_KEY_CUSTOM_RED = "m500_pulsar_custom_red"
    private const val PREFS_KEY_CUSTOM_BLUE = "m500_pulsar_custom_blue"

    private var activeAnimJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var lastKnownTrack: Track? = null
    @Volatile private var lastIsPlaying: Boolean = false

    data class DualColor(val r: Int, val b: Int, val name: String)

    object DualPalette {
        val MIKU_BLUE = DualColor(0, 255, "Signature Miku Blue")
        val ICE_CYAN = DualColor(25, 255, "Electric Ice Cyan")
        val VIOLET_LAVENDER = DualColor(85, 255, "Holographic Violet")
        val ROYAL_PURPLE = DualColor(160, 255, "Royal Purple")
        val CYBER_MAGENTA = DualColor(255, 190, "Cyber Magenta")
        val MIKU_PINK = DualColor(255, 100, "Miku Hot Pink")
        val CRIMSON_RED = DualColor(255, 0, "Crimson Red")
        val TWILIGHT_BREATH = DualColor(40, 140, "Twilight Standby")
    }

    enum class AudioFormatTier(val label: String, val color: DualColor, val patternCode: Int, val qualityProp: String) {
        DSD("DSD Direct Stream Digital", DualPalette.CYBER_MAGENTA, 5, "dsd"),
        MQA_STUDIO("MQA Studio Master", DualPalette.VIOLET_LAVENDER, 9, "mqastudio"),
        ULTRA_HI_RES("Ultra Hi-Res (192k-768k / 32-bit)", DualPalette.ROYAL_PURPLE, 4, "high"),
        HI_RES("Hi-Res Lossless (88.2k-96k / 24-bit)", DualPalette.VIOLET_LAVENDER, 4, "high"),
        CD_LOSSLESS("CD Lossless (44.1k-48k / 16-bit)", DualPalette.MIKU_BLUE, 3, "standard"),
        STANDARD("Standard Compressed (MP3/AAC)", DualColor(0, 150, "Soft Blue"), 2, "low")
    }

    enum class Mode(val id: String, val label: String, val description: String) {
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Colors LED by audio format tier & pulses to track tempo"),
        CHROMA_RAINBOW("chroma_rainbow", "Dual-Die Chroma Wave", "Hypnotic continuous crossfade through Red ↔ Magenta ↔ Purple ↔ Blue"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Dual-pulse heartbeat glow in cyber violet/magenta"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Deep analog sine-wave brightness breathing in Miku Blue"),
        DYNAMIC_STROBE("dynamic_strobe", "Rhythmic Music Strobe", "Energetic transient flashes synchronized to playback rhythm"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Continuous chromatic gauge from Red (empty) to Cyan-Blue (full)"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Solid futuristic Miku Cyan-Blue"),
        CUSTOM_COLOR("custom_color", "Custom Dual-Die Mix", "User-defined Red & Blue 8-bit PWM blend"),
        OFF("off", "Off", "Pulsar indicator disabled")
    }

    @Volatile private var ledWritableCache: Boolean? = null

    /**
     * Whether a Pulsar LED node this engine writes to actually exists AND is writable by this
     * process right now. Writes are plain java.io.File writes to those nodes (the old su path
     * never ran on MikuOS); when this returns false NOTHING is written at all and the controls
     * only persist a preference — the physical diode does not change. UI must say so instead of
     * implying the light responded.
     *
     * On this unit the RGB indicator is confirmed non-functional (SELinux-locked, no consumer
     * service), so this normally returns false and the Pulsar screens say the light will not
     * respond.
     *
     * Checked against the real node paths under sys/class/leds; result cached after the first probe.
     */
    fun isHardwareWritable(): Boolean {
        ledWritableCache?.let { return it }
        val writable = runCatching {
            listOf(
                "$SYSFS_SGM/brightness",
                "$SYSFS_SGM/rgb_val",
                "$SYSFS_RED/brightness",
                "$SYSFS_BLUE/brightness"
            ).any { p -> java.io.File(p).let { it.exists() && it.canWrite() } }
        }.getOrDefault(false)
        ledWritableCache = writable
        return writable
    }

    fun getMode(ctx: Context): Mode {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        val id = sp.getString(PREFS_KEY_MODE, Mode.AUDIOPHILE_AUTO.id) ?: Mode.AUDIOPHILE_AUTO.id
        return Mode.values().firstOrNull { it.id == id } ?: Mode.AUDIOPHILE_AUTO
    }

    fun getBrightness(ctx: Context): Int {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getInt(PREFS_KEY_BRIGHTNESS, 220).coerceIn(10, 255)
    }

    fun isBpmSyncEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean(PREFS_KEY_BPM_SYNC, true)
    }

    fun getAnimationSpeed(ctx: Context): Float {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getFloat(PREFS_KEY_ANIM_SPEED, 1.0f).coerceIn(0.5f, 3.0f)
    }

    fun getCustomColorHex(ctx: Context): String {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getString(PREFS_KEY_CUSTOM_HEX, "#00E5FF") ?: "#00E5FF"
    }

    suspend fun setBpmSyncEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean(PREFS_KEY_BPM_SYNC, enabled).apply()
    }

    suspend fun setAnimationSpeed(ctx: Context, speed: Float) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putFloat(PREFS_KEY_ANIM_SPEED, speed).apply()
        applyMode(ctx, getMode(ctx), getBrightness(ctx))
    }

    suspend fun setCustomColorHex(ctx: Context, hex: String) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putString(PREFS_KEY_CUSTOM_HEX, hex).apply()
        val c = try { Color.parseColor(hex) } catch (_: Throwable) { Color.CYAN }
        // Map 24-bit RGB to Dual-Die Red + Blue
        val r = Color.red(c)
        val b = Color.blue(c)
        sp.edit().putInt(PREFS_KEY_CUSTOM_RED, r).putInt(PREFS_KEY_CUSTOM_BLUE, b).apply()
        if (getMode(ctx) == Mode.CUSTOM_COLOR) {
            writeDual(r, b, getBrightness(ctx))
        }
    }

    suspend fun setCustomDual(ctx: Context, red: Int, blue: Int) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putInt(PREFS_KEY_CUSTOM_RED, red).putInt(PREFS_KEY_CUSTOM_BLUE, blue).apply()
        if (getMode(ctx) == Mode.CUSTOM_COLOR) {
            writeDual(red, blue, getBrightness(ctx))
        }
    }

    suspend fun setMode(ctx: Context, mode: Mode, brightness: Int = getBrightness(ctx)) = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit()
            .putString(PREFS_KEY_MODE, mode.id)
            .putInt(PREFS_KEY_BRIGHTNESS, brightness)
            .apply()

        cancelActiveAnimation()
        applyMode(ctx, mode, brightness)
    }

    suspend fun setEnabled(ctx: Context, on: Boolean) = withContext(Dispatchers.IO) {
        PlayerPreferences.savePulsarEnabled(ctx, on)
        try {
            android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", if (on) 1 else 0)
        } catch (_: Throwable) {}
        if (on) {
            applyMode(ctx, getMode(ctx), getBrightness(ctx))
        } else {
            cancelActiveAnimation()
            turnOff(ctx)
        }
    }

    private suspend fun applyMode(ctx: Context, mode: Mode, brightness: Int) {
        cancelActiveAnimation()
        try {
            android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", if (mode != Mode.OFF) 1 else 0)
        } catch (_: Throwable) {}
        if (!isHardwareWritable()) {
            // No writable LED node on this unit: the preference is stored (above) but there is
            // nothing to drive. Do NOT spin an animation loop that writes to nothing - that was
            // pure battery burn with zero visible effect.
            Log.i(TAG, "applyMode($mode): no writable Pulsar LED node - preference stored only")
            return
        }
        when (mode) {
            Mode.OFF -> {
                turnOff(ctx)
            }
            Mode.AUDIOPHILE_AUTO -> {
                startBreathingAnimation(DualPalette.ICE_CYAN.r, DualPalette.ICE_CYAN.b, brightness, periodMs = 2800)
            }
            Mode.CHROMA_RAINBOW -> {
                val speed = getAnimationSpeed(ctx)
                startChromaWaveAnimation(brightness, speed)
            }
            Mode.CYBER_HEARTBEAT -> {
                startHeartbeatAnimation(DualPalette.VIOLET_LAVENDER.r, DualPalette.VIOLET_LAVENDER.b, brightness)
            }
            Mode.SMOOTH_BREATHING -> {
                startBreathingAnimation(DualPalette.MIKU_BLUE.r, DualPalette.MIKU_BLUE.b, brightness, periodMs = 2400)
            }
            Mode.DYNAMIC_STROBE -> {
                startStrobeAnimation(DualPalette.ROYAL_PURPLE.r, DualPalette.ROYAL_PURPLE.b, brightness, intervalMs = 120)
            }
            Mode.BATTERY_MONITOR -> {
                startBatteryAnimation(ctx, brightness)
            }
            Mode.SIGNATURE_TEAL -> {
                writeDual(DualPalette.MIKU_BLUE.r, DualPalette.MIKU_BLUE.b, brightness)
            }
            Mode.CUSTOM_COLOR -> {
                val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
                val r = sp.getInt(PREFS_KEY_CUSTOM_RED, 80)
                val b = sp.getInt(PREFS_KEY_CUSTOM_BLUE, 255)
                writeDual(r, b, brightness)
            }
        }
    }

    fun getAudioFormatTier(track: Track): AudioFormatTier {
        val mime = track.mime.lowercase()
        val path = track.path.lowercase()
        val isDsd = mime.contains("dsd") || mime.contains("dsf") || mime.contains("dff") || path.endsWith(".dsf") || path.endsWith(".dff") || path.endsWith(".iso")
        if (isDsd) return AudioFormatTier.DSD

        val rate = sampleRateHzOf(track)
        val bitDepth = estimateBitDepth(track)

        return when {
            rate >= 176_400 || bitDepth >= 32 -> AudioFormatTier.ULTRA_HI_RES
            rate >= 88_200 || bitDepth >= 24 -> AudioFormatTier.HI_RES
            mime.contains("flac") || mime.contains("wav") || mime.contains("alac") || track.bitrateKbps >= 700 -> AudioFormatTier.CD_LOSSLESS
            else -> AudioFormatTier.STANDARD
        }
    }

    suspend fun updateForPlayback(ctx: Context, track: Track?, isPlaying: Boolean) = withContext(Dispatchers.IO) {
        lastKnownTrack = track
        lastIsPlaying = isPlaying

        if (!PlayerPreferences.loadPulsarEnabled(ctx)) return@withContext
        val mode = getMode(ctx)
        // The sample-quality HAL hint below is worth pushing either way; the animation loops are
        // not, when nothing can be written to the diode.

        if (mode != Mode.AUDIOPHILE_AUTO && mode != Mode.DYNAMIC_STROBE) {
            return@withContext
        }

        if (!isPlaying || track == null) {
            cancelActiveAnimation()
            if (!isHardwareWritable()) return@withContext
            val (batR, batB) = batteryIndicatorDual(ctx)
            val brightness = getBrightness(ctx)
            if (isCharging(ctx)) {
                startBreathingAnimation(batR, batB, brightness, periodMs = 4000)
            } else {
                writeDual(batR, batB, (brightness * 0.5f).toInt().coerceAtLeast(15))
            }
            return@withContext
        }

        val brightness = getBrightness(ctx)
        val tier = getAudioFormatTier(track)
        val bpm = BpmEngine.resolveTrackBpm(ctx, track) { updatedBpm ->
            if (isPlaying && isBpmSyncEnabled(ctx)) {
                startBpmPulse(tier.color.r, tier.color.b, brightness, updatedBpm)
            }
        }

        // Root-free equivalent of the old setprop line: the two vendor.audio.hiby.* keys are audio
        // HAL parameters, so push them the way every other HiBy setting is pushed. The LED pattern
        // node is only touched when it is genuinely writable (see [isHardwareWritable]).
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.led", "on")
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.sample_quality", tier.qualityProp)
        writeNode("$SYSFS_SGM/led_pattern", tier.patternCode.toString())

        // Nothing to animate when the diode is unreachable - the HAL hints above are still useful.
        if (!isHardwareWritable()) return@withContext

        if (isBpmSyncEnabled(ctx)) {
            startBpmPulse(tier.color.r, tier.color.b, brightness, bpm)
        } else {
            cancelActiveAnimation()
            val isChg = isCharging(ctx)
            if (isChg) {
                startBreathingAnimation(tier.color.r, tier.color.b, brightness, 2800L)
            } else {
                writeDual(tier.color.r, tier.color.b, brightness)
            }
        }
    }

    fun indicatePocketLock(ctx: Context, locked: Boolean) {
        if (!isHardwareWritable()) return
        scope.launch {
            cancelActiveAnimation()
            val (r, b) = if (locked) Pair(255, 0) else Pair(0, 255)
            for (i in 0 until 3) {
                writeDual(r, b, 255)
                delay(70)
                writeDual(0, 0, 0)
                delay(70)
            }
            if (PlayerPreferences.loadPulsarEnabled(ctx)) {
                applyMode(ctx, getMode(ctx), getBrightness(ctx))
            }
        }
    }

    fun indicateHearted(ctx: Context, hearted: Boolean = true) {
        if (!isHardwareWritable()) return
        scope.launch {
            cancelActiveAnimation()
            val (r, b) = if (hearted) Pair(255, 100) else Pair(85, 255)
            // Double-pulse heart indicator
            writeDual(r, b, 255)
            delay(100)
            writeDual(r, b, 50)
            delay(80)
            writeDual(r, b, 255)
            delay(120)
            writeDual(0, 0, 0)
            delay(100)
            if (PlayerPreferences.loadPulsarEnabled(ctx)) {
                applyMode(ctx, getMode(ctx), getBrightness(ctx))
            }
        }
    }

    // ==========================================
    // Animation Loops (Dual-Die Red + Blue)
    // ==========================================

    private fun startChromaWaveAnimation(peakBrightness: Int, speed: Float) {
        cancelActiveAnimation()
        val totalSteps = 60
        val delayMs = (40L / speed).toLong().coerceIn(15L, 100L)

        activeAnimJob = scope.launch {
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
                    delay(delayMs)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startBpmPulse(r: Int, b: Int, peakBrightness: Int, bpm: Int) {
        cancelActiveAnimation()
        val clampedBpm = bpm.coerceIn(50, 220)
        val beatMs = (60_000L / clampedBpm).coerceIn(270L, 1200L)

        activeAnimJob = scope.launch {
            try {
                while (isActive) {
                    writeDual(r, b, peakBrightness)
                    delay((beatMs * 0.20f).toLong().coerceAtLeast(30L))

                    writeDual(r, b, (peakBrightness * 0.40f).toInt())
                    delay((beatMs * 0.35f).toLong().coerceAtLeast(40L))

                    writeDual(r, b, (peakBrightness * 0.12f).toInt())
                    delay((beatMs * 0.45f).toLong().coerceAtLeast(50L))
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startHeartbeatAnimation(r: Int, b: Int, peakBrightness: Int) {
        cancelActiveAnimation()
        activeAnimJob = scope.launch {
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

    private fun startBreathingAnimation(r: Int, b: Int, peakBrightness: Int, periodMs: Long) {
        cancelActiveAnimation()
        val totalSteps = 30
        val stepDelayMs = (periodMs / totalSteps).coerceAtLeast(20)

        activeAnimJob = scope.launch {
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

    private fun startStrobeAnimation(r: Int, b: Int, peakBrightness: Int, intervalMs: Long) {
        cancelActiveAnimation()
        activeAnimJob = scope.launch {
            try {
                var on = true
                while (isActive) {
                    writeDual(r, b, if (on) peakBrightness else 0)
                    on = !on
                    delay(intervalMs)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun startBatteryAnimation(ctx: Context, peakBrightness: Int) {
        cancelActiveAnimation()
        activeAnimJob = scope.launch {
            try {
                val (r, b) = batteryIndicatorDual(ctx)
                if (isCharging(ctx)) {
                    startBreathingAnimation(r, b, peakBrightness, 3500)
                } else {
                    writeDual(r, b, peakBrightness)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun cancelActiveAnimation() {
        activeAnimJob?.cancel()
        activeAnimJob = null
    }

    // ==========================================
    // Low-Level Hardware Sysfs Writes (Dual-Die)
    // ==========================================

    /**
     * Write one LED sysfs node directly. Root-free and honest: if the node is missing or this
     * process cannot write it (the normal case on this unit), nothing happens and nothing pretends
     * otherwise. Guarded by the cached [isHardwareWritable] probe so a dead LED costs no syscalls
     * inside the animation loops.
     */
    private fun writeNode(path: String, value: String) {
        if (!isHardwareWritable()) return
        runCatching {
            val f = java.io.File(path)
            if (f.exists() && f.canWrite()) f.writeText(value)
        }
    }

    private fun writeDual(r: Int, b: Int, brightness: Int) {
        if (!isHardwareWritable()) return
        val scale = brightness.coerceIn(0, 255) / 255f
        val fr = (r.coerceIn(0, 255) * scale).toInt()
        val fb = (b.coerceIn(0, 255) * scale).toInt()

        writeNode("$SYSFS_RED/brightness", fr.toString())
        writeNode("/sys/class/leds/green/brightness", "0")
        writeNode("$SYSFS_BLUE/brightness", fb.toString())
        writeNode("$SYSFS_SGM/rgb_val", "$fr 0 $fb")
        writeNode("$SYSFS_SGM/brightness", brightness.coerceIn(0, 255).toString())
    }

    private fun turnOff(ctx: Context? = null) {
        if (ctx != null) {
            try {
                android.provider.Settings.System.putInt(ctx.contentResolver, "show_turn_on_power", 0)
            } catch (_: Throwable) {}
            // vendor.audio.hiby.hw.* are audio HAL parameters, not shell properties.
            MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.led", "off")
            MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.sample_quality", "none")
        }
        writeNode("$SYSFS_RED/brightness", "0")
        writeNode("/sys/class/leds/green/brightness", "0")
        writeNode("$SYSFS_BLUE/brightness", "0")
        writeNode("$SYSFS_SGM/brightness", "0")
        writeNode("$SYSFS_SGM/led_pattern", "0")
    }

    private fun batteryIndicatorDual(ctx: Context): Pair<Int, Int> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = run {
                    val bi = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val l = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val sc = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                    if (l >= 0 && sc > 0) (l * 100 / sc).coerceIn(0, 100)
                    else (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50).coerceIn(0, 100)
                }
        val t = level / 100f
        val r = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        val b = (t * 255f).toInt().coerceIn(0, 255)
        return Pair(r, b)
    }

    private fun isCharging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return bm?.isCharging == true
    }

    private fun sampleRateHzOf(track: Track): Int = when {
        track.bitrateKbps >= 4500 -> 352_800
        track.bitrateKbps >= 2500 -> 192_000
        track.bitrateKbps >= 1400 -> 96_000
        track.bitrateKbps >= 800 -> 48_000
        else -> 44_100
    }

    private fun estimateBitDepth(track: Track): Int = when {
        track.bitrateKbps >= 4000 -> 32
        track.bitrateKbps >= 1500 -> 24
        else -> 16
    }

    private var hddJob: Job? = null

    /**
     * Rapid retro HDD / memory-card activity LED flicker for SD card scans and heavy file I/O.
     */
    fun startHddActivity() {
        if (!isHardwareWritable()) return
        if (hddJob?.isActive == true) return
        hddJob = scope.launch {
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
            scope.launch { applyMode(ctx, getMode(ctx), getBrightness(ctx)) }
        } else {
            writeDual(0, 255, 200)
        }
    }
}
