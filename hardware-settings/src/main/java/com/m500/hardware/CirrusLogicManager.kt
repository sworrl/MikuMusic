package com.m500.hardware

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hardware controller for the HiBy M500's Dual Cirrus Logic CS43198 DAC
 * and audiophile subsystem via Direct Kernel Sysfs (`/sys/devices/platform/sa_sound_setting/`),
 * HAL System Properties, and Android Global Settings.
 */
object CirrusLogicManager {
    private const val TAG = "CirrusLogicManager"
    const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Reference linear phase, wide soundstage and precise imaging"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with ultra-low group delay and punchy dynamics"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Smooth linear decay with zero phase distortion"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Warm acoustic roll-off with minimal pre-ringing"),
        NOS("nos", "Non-Oversampling (NOS)", "Bypasses internal digital oversampling for raw, analog-like fidelity")
    }

    enum class GainMode(val id: String, val label: String, val description: String) {
        LOW("low", "Low Gain (0 dB)", "Optimized for ultra-sensitive in-ear monitors (zero noise floor)"),
        HIGH("high", "High Gain (+6 dB)", "High voltage swing for planar and high-impedance headphones")
    }

    enum class OutputMode(val id: String, val label: String, val description: String) {
        HEADPHONE_OUT("bal_po", "Headphone Out (PO)", "Variable output with volume control for 3.5mm SE & 4.4mm BAL"),
        LINE_OUT("bal_lo", "Line Out (LO)", "Clean unamplified line-level bypass for external desktop amps")
    }

    /**
     * Raw kernel sysfs readings. A field is null when that node could NOT be read (missing node,
     * SELinux denial, empty) — never substituted with a guess. [isHardwareSynced] is true only
     * when every node was actually read; [readNodes] / [totalNodes] say how many were.
     */
    data class HardwareAuditState(
        val kernelFilter: String? = null,
        val kernelGain: String? = null,
        val kernelHighPower: String? = null,
        val kernelDre: String? = null,
        val kernelTurbo: String? = null,
        val kernelOutput: String? = null,
        val kernelBalance: String? = null,
        val readNodes: Int = 0,
        val totalNodes: Int = 7
    ) {
        val isHardwareSynced: Boolean get() = readNodes == totalNodes
        /** True when nothing at all could be read from the kernel — state is unknown. */
        val isUnknown: Boolean get() = readNodes == 0
    }

    /** True when the DAC sysfs directory exists and at least one control node is readable. */
    fun isSysfsReachable(): Boolean = try {
        val dir = File(SYSFS_BASE)
        dir.isDirectory && (dir.listFiles()?.any { it.canRead() } == true)
    } catch (_: Throwable) { false }

    fun init(ctx: Context) {
        RootShell.execFast("chmod 666 $SYSFS_BASE/*")
    }

    private fun readSysfs(node: String): String? {
        try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists()) {
                val txt = file.readText().trim()
                if (txt.isNotEmpty()) return txt
            }
        } catch (_: Throwable) {}
        val rootOut = RootShell.execOut("cat $SYSFS_BASE/$node")?.trim()
        if (!rootOut.isNullOrBlank()) return rootOut
        return null
    }

    private fun writeSysfs(node: String, value: String) {
        try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists() && file.canWrite()) {
                file.writeText(value)
            } else {
                RootShell.execFast("echo $value > $SYSFS_BASE/$node")
            }
        } catch (_: Throwable) {
            RootShell.execFast("echo $value > $SYSFS_BASE/$node")
        }
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = no real source says (never guessed). */
    fun getDigitalFilter(ctx: Context): DigitalFilter? {
        val kernelVal = readSysfs("digital_filter")
        if (!kernelVal.isNullOrBlank()) {
            DigitalFilter.values().firstOrNull { it.id == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.digital_filter")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.digital_filter")
            ?: Settings.Global.getString(cr, "hw.digital_filter")
            ?: ""
        return DigitalFilter.values().firstOrNull { it.id == raw.trim().lowercase() }
    }

    suspend fun setDigitalFilter(ctx: Context, filter: DigitalFilter) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "hw.digital_filter", filter.id) }

        RootShell.execFast(
            "echo ${filter.id} > $SYSFS_BASE/digital_filter; " +
            "settings put global vendor.audio.hiby.hw.digital_filter ${filter.id}; " +
            "settings put global vendor.audio.hiby.digital_filter ${filter.id}; " +
            "settings put global hw.digital_filter ${filter.id}; " +
            "setprop vendor.audio.hiby.hw.digital_filter ${filter.id}; " +
            "setprop vendor.audio.hiby.digital_filter ${filter.id}"
        )
        Log.i(TAG, "Applied Cirrus Logic filter: ${filter.id}")
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = no real source says (never guessed). */
    fun getGainMode(ctx: Context): GainMode? {
        val kernelVal = readSysfs("gain")
        if (!kernelVal.isNullOrBlank()) {
            if (kernelVal.contains("high")) return GainMode.HIGH
            if (kernelVal.contains("low")) return GainMode.LOW
        }
        val cr = ctx.contentResolver
        val g = Settings.Global.getString(cr, "vendor.audio.hiby.hw.gain")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.gain") ?: ""
        return when {
            g.contains("high") -> GainMode.HIGH
            g.contains("low") -> GainMode.LOW
            else -> null
        }
    }

    // ---- "All audio to max unless the user lowered it" (user directive 2026-09-13) ----
    // These Global rows record an EXPLICIT user choice; the Miku player's best-audio enforcer
    // (MikuDirectAudio.ensureBestAudio) pushes the maximum for every knob without a row and the
    // user's value where one exists. Same keys as com.miku.player.MikuDirectAudio.
    private const val KEY_USER_GAIN = "miku_audio_user_gain"
    private const val KEY_USER_DRE = "miku_audio_user_dre"
    private const val KEY_USER_HIGH_POWER = "miku_audio_user_high_power"

    /**
     * Push a vendor.audio.hiby.* value straight into the audio HAL via AudioManager.setParameters -
     * the root-free channel that actually moves the DAC. The RootShell lines that follow it in the
     * setters are kept for rooted builds only; on MikuOS (no su) they are no-ops.
     */
    private fun pushToHal(ctx: Context, key: String, value: String) {
        runCatching {
            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.setParameters("$key=$value")
        }.onFailure { Log.w(TAG, "pushToHal $key failed: $it") }
    }

    suspend fun setGainMode(ctx: Context, gain: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val hp = if (gain == GainMode.HIGH) "hpower_enable" else "hpower_disable"

        runCatching { Settings.Global.putString(cr, KEY_USER_GAIN, gain.id.lowercase()) }
        runCatching { Settings.Global.putInt(cr, KEY_USER_HIGH_POWER, if (gain == GainMode.HIGH) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", gain.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", gain.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", hp) }
        pushToHal(ctx, "vendor.audio.hiby.hw.gain", gain.id)
        pushToHal(ctx, "vendor.audio.hiby.gain", gain.id)
        pushToHal(ctx, "vendor.audio.hiby.hw.high_power_mode", hp)

        RootShell.execFast(
            "echo ${gain.id} > $SYSFS_BASE/gain; " +
            "echo $hp > $SYSFS_BASE/high_power_mode; " +
            "settings put global vendor.audio.hiby.hw.gain ${gain.id}; " +
            "settings put global vendor.audio.hiby.gain ${gain.id}; " +
            "settings put global vendor.audio.hiby.high_power $hp; " +
            "setprop vendor.audio.hiby.hw.gain ${gain.id}; " +
            "setprop vendor.audio.hiby.gain ${gain.id}; " +
            "setprop vendor.audio.hiby.high_power $hp; " +
            "am broadcast -a gain_value_change_from_Settings"
        )

        try {
            ctx.sendBroadcast(Intent("gain_value_change_from_Settings"))
        } catch (_: Throwable) {}

        Log.i(TAG, "Applied Gain mode: ${gain.id}")
    }

    fun isDreEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("dre_mode")
        if (!kernelVal.isNullOrBlank()) {
            return kernelVal.contains("enable")
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.dre_mode") ?: ""
        return raw.contains("enable")
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val cmd = if (enabled) "dremode_enable" else "dremode_disable"

        runCatching { Settings.Global.putInt(cr, KEY_USER_DRE, if (enabled) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", cmd) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", if (enabled) 1 else 0) }
        pushToHal(ctx, "vendor.audio.hiby.hw.dre", if (enabled) "1" else "0")
        pushToHal(ctx, "vendor.audio.hiby.hw.dre_mode", cmd)

        RootShell.execFast(
            "echo $cmd > $SYSFS_BASE/dre_mode; " +
            "settings put global vendor.audio.hiby.dre_mode $cmd; " +
            "setprop vendor.audio.hiby.dre_mode $cmd"
        )
        Log.i(TAG, "Applied DRE Mode: $cmd")
    }

    fun isHighPowerEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("high_power_mode")
        if (!kernelVal.isNullOrBlank()) {
            return kernelVal.contains("enable")
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.high_power")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.high_power_mode") ?: ""
        return raw.contains("enable")
    }

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val cmd = if (enabled) "hpower_enable" else "hpower_disable"

        runCatching { Settings.Global.putInt(cr, KEY_USER_HIGH_POWER, if (enabled) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", cmd) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power_mode", cmd) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", if (enabled) 1 else 0) }
        pushToHal(ctx, "vendor.audio.hiby.hw.high_power", if (enabled) "1" else "0")
        pushToHal(ctx, "vendor.audio.hiby.hw.high_power_mode", cmd)

        RootShell.execFast(
            "echo $cmd > $SYSFS_BASE/high_power_mode; " +
            "settings put global vendor.audio.hiby.high_power $cmd; " +
            "settings put global vendor.audio.hiby.high_power_mode $cmd; " +
            "setprop vendor.audio.hiby.high_power $cmd; " +
            "setprop vendor.audio.hiby.high_power_mode $cmd"
        )
        Log.i(TAG, "Applied High Power Turbo: $cmd")
    }

    fun isTurboEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("turbo")
        if (!kernelVal.isNullOrBlank()) {
            return kernelVal == "1" || kernelVal.contains("on") || kernelVal.contains("enable")
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getInt(cr, "vendor.audio.hiby.hw.turbo", 0)
        return raw == 1
    }

    suspend fun setTurboEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val strVal = if (enabled) "on" else "off"
        val intVal = if (enabled) 1 else 0

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.turbo", intVal) }

        RootShell.execFast(
            "echo ${if (enabled) "1" else "0"} > $SYSFS_BASE/turbo; " +
            "settings put global vendor.audio.hiby.hw.turbo $intVal; " +
            "setprop vendor.audio.hiby.hw.turbo $strVal"
        )
        Log.i(TAG, "Applied Turbo Mode: $strVal")
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = not set anywhere (never guessed). */
    fun getDsdGainCompensate(ctx: Context): Int? {
        val kernelVal = readSysfs("dsd_compensate") ?: readSysfs("dac_dsd_gain")
        if (!kernelVal.isNullOrBlank()) {
            kernelVal.toIntOrNull()?.let { return it }
        }
        val cr = ctx.contentResolver
        return Settings.Global.getString(cr, "vendor.audio.hiby.hw.dac_dsd_gain")?.trim()?.toIntOrNull()
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.dsd_compensate")?.trim()?.toIntOrNull()
    }

    suspend fun setDsdGainCompensate(ctx: Context, db: Int) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val safe = db.coerceIn(0, 6)

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dac_dsd_gain", safe) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.dsd_compensate", safe) }

        RootShell.execFast(
            "echo $safe > $SYSFS_BASE/dsd_compensate; " +
            "echo $safe > $SYSFS_BASE/dac_dsd_gain; " +
            "settings put global vendor.audio.hiby.hw.dac_dsd_gain $safe; " +
            "settings put global vendor.audio.hiby.dsd_compensate $safe; " +
            "setprop vendor.audio.hiby.hw.dac_dsd_gain $safe; " +
            "setprop vendor.audio.hiby.dsd_compensate $safe"
        )
        Log.i(TAG, "Applied DSD Gain Compensation: ${safe}dB")
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = no real source says (never guessed). */
    fun getOutputMode(ctx: Context): OutputMode? {
        val kernelVal = readSysfs("bal_po_lo_switch") ?: readSysfs("po_lo_switch")
        if (!kernelVal.isNullOrBlank()) {
            return if (kernelVal.contains("lo")) OutputMode.LINE_OUT else OutputMode.HEADPHONE_OUT
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.bal_po_lo_switch")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.bal_po_lo_switch") ?: return null
        return if (raw.contains("lo")) OutputMode.LINE_OUT else OutputMode.HEADPHONE_OUT
    }

    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver

        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.bal_po_lo_switch", mode.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.bal_po_lo_switch", mode.id) }

        RootShell.execFast(
            "echo ${mode.id} > $SYSFS_BASE/bal_po_lo_switch; " +
            "echo ${if (mode == OutputMode.LINE_OUT) "lo" else "po"} > $SYSFS_BASE/po_lo_switch; " +
            "settings put global vendor.audio.hiby.hw.bal_po_lo_switch ${mode.id}; " +
            "settings put global vendor.audio.hiby.bal_po_lo_switch ${mode.id}; " +
            "setprop vendor.audio.hiby.hw.bal_po_lo_switch ${mode.id}; " +
            "setprop vendor.audio.hiby.bal_po_lo_switch ${mode.id}"
        )
        Log.i(TAG, "Applied Output Routing: ${mode.id}")
    }

    /**
     * Null when NO real source reports a balance. [getBalance] falls back to 0 so callers that need
     * a number have one, but display surfaces must use this: an unreadable node was being printed
     * as "Center" next to an enabled slider, i.e. an unknown shown as a measurement.
     */
    fun getBalanceOrNull(ctx: Context): Int? {
        readSysfs("lrbalance")?.toIntOrNull()?.let { return it }
        val cr = ctx.contentResolver
        return try {
            Settings.Global.getString(cr, "vendor.audio.hiby.hw.lrbalance")?.trim()?.toIntOrNull()
                ?: Settings.Global.getString(cr, "vendor.audio.hiby.lrbalance")?.trim()?.toIntOrNull()
        } catch (_: Throwable) { null }
    }

    fun getBalance(ctx: Context): Int {
        val kernelVal = readSysfs("lrbalance")
        if (!kernelVal.isNullOrBlank()) {
            kernelVal.toIntOrNull()?.let { return it }
        }
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.lrbalance",
            Settings.Global.getInt(cr, "vendor.audio.hiby.lrbalance", 0))
    }

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val safe = balance.coerceIn(-10, 10)

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.lrbalance", safe) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.lrbalance", safe) }

        RootShell.execFast(
            "echo $safe > $SYSFS_BASE/lrbalance; " +
            "settings put global vendor.audio.hiby.hw.lrbalance $safe; " +
            "settings put global vendor.audio.hiby.lrbalance $safe; " +
            "setprop vendor.audio.hiby.hw.lrbalance $safe; " +
            "setprop vendor.audio.hiby.lrbalance $safe"
        )
        Log.i(TAG, "Applied L/R Balance: $safe")
    }

    /**
     * Reads the live hardware state directly from kernel sysfs for truthful verification in the UI.
     * Nodes that cannot be read stay null (previously they were silently replaced by defaults and
     * the result was always flagged "synced" — a fabricated audit).
     */
    fun getLiveHardwareAudit(): HardwareAuditState {
        val kFilter = readSysfs("digital_filter")
        val kGain = readSysfs("gain")
        val kHp = readSysfs("high_power_mode")
        val kDre = readSysfs("dre_mode")
        val kTurbo = readSysfs("turbo")
        val kOutput = readSysfs("bal_po_lo_switch") ?: readSysfs("po_lo_switch")
        val kBal = readSysfs("lrbalance")
        val read = listOf(kFilter, kGain, kHp, kDre, kTurbo, kOutput, kBal).count { it != null }

        return HardwareAuditState(
            kernelFilter = kFilter,
            kernelGain = kGain,
            kernelHighPower = kHp,
            kernelDre = kDre,
            kernelTurbo = kTurbo,
            kernelOutput = kOutput,
            kernelBalance = kBal,
            readNodes = read,
            totalNodes = 7
        )
    }
}
