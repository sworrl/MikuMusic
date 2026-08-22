package com.miku.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CirrusLogicManager {
    private const val TAG = "MikuOS_CS43198"
    private const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Reference linear phase, wide soundstage and precise imaging"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with ultra-low group delay and punchy dynamics"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Smooth linear decay with zero phase distortion"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Warm acoustic roll-off with minimal pre-ringing"),
        NOS("nos", "Non-Oversampling (NOS)", "Bypasses internal digital oversampling for raw, analog-like fidelity")
    }

    enum class GainMode(val id: String, val label: String, val sysfsValue: String, val description: String) {
        LOW("low", "Low Gain (0 dB)", "low", "Optimized for high-sensitivity IEMs and low-impedance earphones"),
        HIGH("high", "High Gain (+6 dB)", "high", "High-voltage rail swing for demanding planar magnetic and high-impedance headphones")
    }

    enum class OutputMode(val id: String, val label: String, val sysfsValue: String) {
        HEADPHONE_OUT("po", "Headphone Out (PO)", "po"),
        LINE_OUT("lo", "Line Out (LO)", "lo")
    }

    private fun readSysfs(node: String): String? {
        return try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                RootShell.execOut("cat $SYSFS_BASE/$node")?.trim()
            }
        } catch (_: Throwable) {
            RootShell.execOut("cat $SYSFS_BASE/$node")?.trim()
        }
    }

    fun getDigitalFilter(ctx: Context): DigitalFilter {
        val kernelVal = readSysfs("digital_filter")
        if (!kernelVal.isNullOrBlank()) {
            DigitalFilter.values().firstOrNull { it.id == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.digital_filter")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.digital_filter")
            ?: Settings.Global.getString(cr, "hw.digital_filter")
            ?: ""
        return DigitalFilter.values().firstOrNull { it.id == raw.trim().lowercase() } ?: DigitalFilter.FAST_LINEAR
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
        ctx.sendBroadcast(Intent("com.m500.hardware.action.FILTER_CHANGED").apply {
            putExtra("filter", filter.id)
        })
    }

    fun getGainMode(ctx: Context): GainMode {
        val kernelVal = readSysfs("gain")
        if (!kernelVal.isNullOrBlank()) {
            GainMode.values().firstOrNull { it.sysfsValue == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.gain")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.gain")
            ?: ""
        return GainMode.values().firstOrNull { it.sysfsValue == raw.trim().lowercase() } ?: GainMode.HIGH
    }

    suspend fun setGainMode(ctx: Context, mode: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", mode.sysfsValue) }

        RootShell.execFast(
            "echo ${mode.sysfsValue} > $SYSFS_BASE/gain; " +
            "settings put global vendor.audio.hiby.hw.gain ${mode.sysfsValue}; " +
            "settings put global vendor.audio.hiby.gain ${mode.sysfsValue}; " +
            "setprop vendor.audio.hiby.hw.gain ${mode.sysfsValue}; " +
            "setprop vendor.audio.hiby.gain ${mode.sysfsValue}"
        )
        ctx.sendBroadcast(Intent("com.m500.hardware.action.GAIN_CHANGED").apply {
            putExtra("gain", mode.sysfsValue)
        })
    }

    fun isDreEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("dre_mode")
        if (kernelVal != null) return kernelVal == "dremode_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dre", 0) == 1
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/dre_mode; settings put global vendor.audio.hiby.hw.dre $v; setprop vendor.audio.hiby.hw.dre $v")
    }

    fun isHighPowerEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("high_power_mode")
        if (kernelVal != null) return kernelVal == "hpower_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.high_power", 0) == 1
    }

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/high_power_mode; settings put global vendor.audio.hiby.hw.high_power $v; setprop vendor.audio.hiby.hw.high_power $v")
    }

    fun getOutputMode(ctx: Context): OutputMode {
        val kernelVal = readSysfs("out_mode")
        if (!kernelVal.isNullOrBlank()) {
            OutputMode.values().firstOrNull { it.sysfsValue == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.output_mode") ?: "po"
        return OutputMode.values().firstOrNull { it.sysfsValue == raw } ?: OutputMode.HEADPHONE_OUT
    }

    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.output_mode", mode.sysfsValue) }
        RootShell.execFast("echo ${mode.sysfsValue} > $SYSFS_BASE/out_mode; settings put global vendor.audio.hiby.hw.output_mode ${mode.sysfsValue}; setprop vendor.audio.hiby.hw.output_mode ${mode.sysfsValue}")
    }

    fun getBalance(ctx: Context): Int {
        val kernelVal = readSysfs("lr_balance")?.toIntOrNull()
        if (kernelVal != null) return kernelVal
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.balance", 0)
    }

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val clamped = balance.coerceIn(-10, 10)
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.balance", clamped) }
        RootShell.execFast("echo $clamped > $SYSFS_BASE/lr_balance; settings put global vendor.audio.hiby.hw.balance $clamped; setprop vendor.audio.hiby.hw.balance $clamped")
    }

    fun getDsdGainCompensate(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", 1) == 1
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        RootShell.execFast("settings put global vendor.audio.hiby.hw.dsd_gain_comp $v; setprop vendor.audio.hiby.hw.dsd_gain_comp $v")
    }

    fun getLiveHardwareAudit(): Map<String, String> {
        val audit = mutableMapOf<String, String>()
        audit["kernel_sysfs_filter"] = readSysfs("digital_filter") ?: "N/A"
        audit["kernel_sysfs_gain"] = readSysfs("gain") ?: "N/A"
        audit["kernel_sysfs_dre"] = readSysfs("dre") ?: "N/A"
        audit["kernel_sysfs_turbo"] = readSysfs("audio_turbo") ?: "N/A"
        audit["kernel_sysfs_out_mode"] = readSysfs("out_mode") ?: "N/A"
        audit["kernel_sysfs_balance"] = readSysfs("lr_balance") ?: "N/A"
        audit["prop_hw_filter"] = RootShell.execOut("getprop vendor.audio.hiby.hw.digital_filter") ?: "N/A"
        audit["prop_hw_gain"] = RootShell.execOut("getprop vendor.audio.hiby.hw.gain") ?: "N/A"
        return audit
    }
}
