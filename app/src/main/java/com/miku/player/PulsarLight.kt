package com.miku.player

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulsar Light — **OS client**. The front indicator is owned by MikuOS (the launcher), not by this
 * player.
 *
 * Miku Music used to drive the diode itself: it held the sysfs paths, ran its own animation
 * coroutines, and wrote PWM duty cycles directly. That was the wrong layer — an app competing with
 * the OS for a piece of system hardware, with two independent animation engines able to fight over
 * the same LED. Per the owner's directive (2026-09-13) the indicator is now OS-level: this object
 * keeps the same public API so call sites are unchanged, but every ACTION is forwarded to
 * `com.miku.launcher` and every hardware decision is made there.
 *
 * Preferences are still written locally (the player's own settings screens read them back), and
 * mirrored into `Settings.Global` so the OS side sees one authoritative value.
 *
 * Hardware note kept from the original engine: the physical part is a dual-die Red+Blue LED with no
 * green die, so every colour below is a mathematical blend of the two 0-255 PWM channels. On this
 * unit the indicator is confirmed non-functional (the sysfs nodes are SELinux-locked to us), which
 * is why [isHardwareWritable] normally reports false — UI must say the light will not respond
 * rather than imply it did.
 */
object PulsarLight {

    /** Broadcast the OS listens on (com.miku.launcher/.PulsarReceiver). */
    const val ACTION_PULSAR = "com.miku.launcher.action.PULSAR"
    const val EXTRA_OP = "op"
    const val EXTRA_VALUE = "value"

    /** Written by the OS; read-only here. Absent/0 = the diode will not respond. */
    private const val GLOBAL_HW_WRITABLE = "miku_pulsar_hw_writable"

    private const val PREFS = "m500_hardware_prefs"
    private const val PREFS_KEY_MODE = "m500_pulsar_mode"
    private const val PREFS_KEY_BRIGHTNESS = "m500_pulsar_brightness"
    private const val PREFS_KEY_BPM_SYNC = "m500_pulsar_bpm_sync"
    private const val PREFS_KEY_ANIM_SPEED = "m500_pulsar_anim_speed"
    private const val PREFS_KEY_CUSTOM_HEX = "m500_pulsar_custom_hex"

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

    /**
     * Descriptions state the STORED INTENT, not an effect the user will see. The old copy here
     * advertised "pulses to track tempo", "hypnotic continuous crossfade" and "sine-wave brightness
     * breathing"; two of those were never implemented even in the engine (a mode applies ONE static
     * colour pair), and none of them can light anything on this unit, whose LED nodes are
     * SELinux-locked. The launcher's copy was corrected earlier — this one now matches it.
     */
    enum class Mode(val id: String, val label: String, val description: String) {
        AUDIOPHILE_AUTO("audiophile_auto", "Audiophile BPM Pulse", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        CHROMA_RAINBOW("chroma_rainbow", "Dual-Die Chroma Wave", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        CYBER_HEARTBEAT("cyber_heartbeat", "Cyber Heartbeat", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SMOOTH_BREATHING("smooth_breathing", "Analog Breathing Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        DYNAMIC_STROBE("dynamic_strobe", "Rhythmic Music Strobe", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        BATTERY_MONITOR("battery_monitor", "Battery & Charging Glow", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        SIGNATURE_TEAL("signature_teal", "Signature Miku Blue", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        CUSTOM_COLOR("custom_color", "Custom Dual-Die Mix", "Saved preference only — the M500's RGB indicator does not respond on this unit"),
        OFF("off", "Off", "Pulsar indicator disabled")
    }

    // ---------------------------------------------------------------- OS transport

    /**
     * Momentary feedback ops (a like flash, a lock blink, a track change). These are skipped
     * entirely once the OS has told us the diode is dead — otherwise every liked track would
     * wake the launcher process to drive an LED that cannot light. Settings ops always go through,
     * so the OS stays in sync regardless. An ABSENT flag means "not asked yet": send once, which
     * makes the OS publish it.
     */
    private fun sendEvent(ctx: Context, op: String, value: String? = null) {
        val known = runCatching {
            Settings.Global.getInt(ctx.applicationContext.contentResolver, GLOBAL_HW_WRITABLE, -1)
        }.getOrDefault(-1)
        hardwareWritableCache = known == 1
        if (known == 0) return
        send(ctx, op, value)
    }

    private fun send(ctx: Context, op: String, value: String? = null) {
        runCatching {
            val i = Intent(ACTION_PULSAR)
                .setPackage("com.miku.launcher")
                .putExtra(EXTRA_OP, op)
            if (value != null) i.putExtra(EXTRA_VALUE, value)
            ctx.applicationContext.sendBroadcast(i)
        }
    }

    /** Mirror a setting into Settings.Global so the OS reads one authoritative value. */
    private fun mirror(ctx: Context, key: String, value: String) {
        runCatching { Settings.Global.putString(ctx.applicationContext.contentResolver, key, value) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- state (read)

    /**
     * Does the OS report the diode as actually drivable? This process no longer probes sysfs — the
     * OS owns that answer and publishes it. Default false: never imply the light responded.
     */
    fun isHardwareWritable(): Boolean = hardwareWritableCache

    @Volatile private var hardwareWritableCache = false

    /** Refresh [isHardwareWritable] from the OS-published flag. Cheap; call from settings screens. */
    fun refreshHardwareState(ctx: Context) {
        hardwareWritableCache = runCatching {
            Settings.Global.getInt(ctx.applicationContext.contentResolver, GLOBAL_HW_WRITABLE, 0) == 1
        }.getOrDefault(false)
    }

    fun getMode(ctx: Context): Mode {
        val id = prefs(ctx).getString(PREFS_KEY_MODE, Mode.AUDIOPHILE_AUTO.id) ?: Mode.AUDIOPHILE_AUTO.id
        return Mode.values().firstOrNull { it.id == id } ?: Mode.AUDIOPHILE_AUTO
    }

    fun getBrightness(ctx: Context): Int =
        prefs(ctx).getInt(PREFS_KEY_BRIGHTNESS, 220).coerceIn(10, 255)

    fun isBpmSyncEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREFS_KEY_BPM_SYNC, true)

    fun getAnimationSpeed(ctx: Context): Float =
        prefs(ctx).getFloat(PREFS_KEY_ANIM_SPEED, 1.0f).coerceIn(0.5f, 3.0f)

    fun getCustomColorHex(ctx: Context): String =
        prefs(ctx).getString(PREFS_KEY_CUSTOM_HEX, "#00E5FF") ?: "#00E5FF"

    // ---------------------------------------------------------------- state (write → OS)

    suspend fun setMode(ctx: Context, mode: Mode, brightness: Int = getBrightness(ctx)) = withContext(Dispatchers.IO) {
        prefs(ctx).edit().putString(PREFS_KEY_MODE, mode.id).putInt(PREFS_KEY_BRIGHTNESS, brightness).apply()
        mirror(ctx, PREFS_KEY_MODE, mode.id)
        mirror(ctx, PREFS_KEY_BRIGHTNESS, brightness.toString())
        send(ctx, "mode", mode.id)
    }

    suspend fun setEnabled(ctx: Context, on: Boolean) = withContext(Dispatchers.IO) {
        PlayerPreferences.savePulsarEnabled(ctx, on)
        send(ctx, "enabled", if (on) "1" else "0")
    }

    suspend fun setBpmSyncEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs(ctx).edit().putBoolean(PREFS_KEY_BPM_SYNC, enabled).apply()
        mirror(ctx, PREFS_KEY_BPM_SYNC, if (enabled) "1" else "0")
        send(ctx, "bpm_sync", if (enabled) "1" else "0")
    }

    suspend fun setAnimationSpeed(ctx: Context, speed: Float) = withContext(Dispatchers.IO) {
        val s = speed.coerceIn(0.5f, 3.0f)
        prefs(ctx).edit().putFloat(PREFS_KEY_ANIM_SPEED, s).apply()
        mirror(ctx, PREFS_KEY_ANIM_SPEED, s.toString())
        send(ctx, "anim_speed", s.toString())
    }

    suspend fun setCustomColorHex(ctx: Context, hex: String) = withContext(Dispatchers.IO) {
        prefs(ctx).edit().putString(PREFS_KEY_CUSTOM_HEX, hex).apply()
        mirror(ctx, PREFS_KEY_CUSTOM_HEX, hex)
        send(ctx, "custom_hex", hex)
    }

    // ---------------------------------------------------------------- events (→ OS)

    fun indicatePocketLock(ctx: Context, locked: Boolean) = sendEvent(ctx, "pocket_lock", if (locked) "1" else "0")

    fun indicateHearted(ctx: Context, hearted: Boolean = true) = sendEvent(ctx, "hearted", if (hearted) "1" else "0")

    /**
     * Tell the OS what is playing so its Pulsar engine can colour by format tier. The tier is
     * computed here because only the player knows the track; the OS decides what to do with it.
     */
    suspend fun updateForPlayback(ctx: Context, track: Track?, isPlaying: Boolean) = withContext(Dispatchers.IO) {
        val tier = track?.let { getAudioFormatTier(it) }
        sendEvent(ctx, "playback", "${if (isPlaying) 1 else 0}:${tier?.name ?: "NONE"}")
    }

    // ---------------------------------------------------------------- format classification

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

    // REMOVED with the OS-level move: startHddActivity()/stopHddActivity(). They blinked the diode
    // with Random() bursts presented as "HDD activity" while being tied to no storage I/O counter
    // at all — fabricated telemetry. The launcher deleted its copy for the same reason.
}
