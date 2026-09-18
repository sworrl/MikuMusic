package com.miku.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
        // No dB figures in the labels: the actual gain step of this unit is never read from anything,
        // so "(0 dB)" / "(+6 dB)" were unverified numbers rendered next to a live-looking readout.
        LOW("low", "Low Gain", "low", "Intended for high-sensitivity IEMs and low-impedance earphones"),
        HIGH("high", "High Gain", "high", "Intended for high-impedance / planar headphones")
    }

    enum class OutputMode(val id: String, val label: String, val sysfsValue: String, val icon: String, val description: String) {
        AUTO("auto", "Auto-Detect Physical / BT", "auto", "⚡", "Intelligently routes audio to whatever physical port or Bluetooth gear is connected"),
        BAL_HEADPHONE_OUT("bal_po", "4.4mm Balanced (BAL PO)", "bal_po", "🎧", "Force dual differential 4.4mm balanced output stage"),
        HEADPHONE_OUT("po", "3.5mm Single-Ended (PO)", "po", "🎧", "Force dedicated 3.5mm unbalanced headphone amplifier stage"),
        BLUETOOTH("bt", "Bluetooth Audio (A2DP / Speaker)", "bt", "🔊", "Force wireless stream to connected Bluetooth speaker or headphones"),
        LINE_OUT("lo", "Line Out (LO / BAL LO)", "lo", "📻", "Fixed reference voltage line output for external desktop amplifiers"),
        USB_DAC("usb", "USB-C Audio / UAC2 DAC", "usb", "💻", "Route audio stream to external Type-C audio hardware")
    }

    enum class AudioShareTarget(val id: String, val label: String, val description: String) {
        DUAL_44_AND_BT("dual_44_bt", "4.4mm Balanced DAC + Bluetooth Speaker", "Simultaneously powers 4.4mm balanced IEMs while streaming to Bluetooth speaker/gear"),
        DUAL_35_AND_BT("dual_35_bt", "3.5mm Single-Ended DAC + Bluetooth Speaker", "Simultaneously powers 3.5mm IEMs while streaming to Bluetooth speaker/gear"),
        DUAL_PHYSICAL("dual_phy", "Both Physical Ports (3.5mm + 4.4mm Balanced)", "Simultaneously powers both 3.5mm and 4.4mm ports for dual wired IEMs"),
        WIRED_AND_USB("wired_usb", "Wired DAC + USB-C External DAC", "Mirrors real-time audio across internal CS43198 DAC and external Type-C DAC")
    }

    /**
     * Where a value came from. This is the whole point of the class's read path: the DAC controls
     * used to fall back to our OWN SharedPreferences mirror and hand the result back through the
     * "honest" display readers, so the UI re-read what WE had last written and called it a
     * hardware state. Re-reading proved nothing.
     */
    enum class Source {
        /** Read back out of the kernel sysfs node — the DAC's actual state. */
        SYSFS,
        /** Read from the vendor.audio.hiby.* Settings.Global row the HiBy audio HAL consumes. */
        VENDOR_SETTING,
        /** Our own prefs mirror: what the user ASKED for. Says nothing about the hardware. */
        USER_REQUEST
    }

    /** A value plus where it came from, so a surface can say "measured" vs "requested". */
    data class Reading<T>(val value: T, val source: Source) {
        /** True when this came from a real source, not from our own prefs mirror. */
        val isMeasured: Boolean get() = source != Source.USER_REQUEST
        /** True only when the kernel node itself was read back. */
        val isKernelVerified: Boolean get() = source == Source.SYSFS
    }

    /**
     * REAL kernel read only. Null when the node is missing / unreadable / empty. It must never
     * fall back to anything we wrote ourselves.
     */
    private fun readKernelNode(node: String): String? = try {
        val file = File("$SYSFS_BASE/$node")
        if (file.exists() && file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (_: Throwable) { null }

    /** The prefs mirror: what the user last ASKED for. Only ever a [Source.USER_REQUEST] tier. */
    private fun readRequestedPref(ctx: Context, node: String): String? = try {
        ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
            .getString(node, null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    /** A vendor.audio.hiby.* Settings.Global row (the HAL reads these). Null when unset. */
    private fun readVendorSetting(ctx: Context, vararg keys: String): String? {
        for (k in keys) {
            val v = try { Settings.Global.getString(ctx.contentResolver, k) } catch (_: Throwable) { null }
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    fun getOutputMode(ctx: Context): OutputMode {
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("out_mode", "auto") ?: "auto"
        return OutputMode.values().firstOrNull { it.sysfsValue == saved } ?: OutputMode.AUTO
    }

    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("out_mode", mode.sysfsValue).apply()
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.output_mode", mode.sysfsValue) }

        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (mode) {
            OutputMode.BAL_HEADPHONE_OUT -> {
                RootShell.execFast("echo bal_po > $SYSFS_BASE/out_mode; settings put global vendor.audio.hiby.hw.output_mode bal_po; setprop vendor.audio.hiby.hw.output_mode bal_po")
                am?.setParameters("routing=4;vendor.audio.hiby.hw.output_mode=bal_po")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val wiredDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (wiredDev != null) am.setCommunicationDevice(wiredDev)
                    else am.clearCommunicationDevice()
                }
            }
            OutputMode.HEADPHONE_OUT -> {
                RootShell.execFast("echo po > $SYSFS_BASE/out_mode; settings put global vendor.audio.hiby.hw.output_mode po; setprop vendor.audio.hiby.hw.output_mode po")
                am?.setParameters("routing=4;vendor.audio.hiby.hw.output_mode=po")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val wiredDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (wiredDev != null) am.setCommunicationDevice(wiredDev)
                    else am.clearCommunicationDevice()
                }
            }
            OutputMode.BLUETOOTH -> {
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode bt; setprop vendor.audio.hiby.hw.output_mode bt")
                am?.setParameters("routing=128;vendor.audio.hiby.hw.output_mode=bt")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val btDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_HEARING_AID || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (btDev != null) am.setCommunicationDevice(btDev)
                }
            }
            OutputMode.LINE_OUT -> {
                RootShell.execFast("echo lo > $SYSFS_BASE/out_mode; settings put global vendor.audio.hiby.hw.output_mode lo; setprop vendor.audio.hiby.hw.output_mode lo")
                am?.setParameters("routing=8;vendor.audio.hiby.hw.output_mode=lo")
            }
            OutputMode.USB_DAC -> {
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode usb; setprop vendor.audio.hiby.hw.output_mode usb")
                am?.setParameters("routing=16384;vendor.audio.hiby.hw.output_mode=usb")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val usbDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    }
                    if (usbDev != null) am.setCommunicationDevice(usbDev)
                }
            }
            OutputMode.AUTO -> {
                RootShell.execFast("echo auto > $SYSFS_BASE/out_mode; settings put global vendor.audio.hiby.hw.output_mode auto; setprop vendor.audio.hiby.hw.output_mode auto")
                am?.setParameters("vendor.audio.hiby.hw.output_mode=auto")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    am.clearCommunicationDevice()
                }
            }
        }
    }

    suspend fun swapOutputMode(ctx: Context): OutputMode {
        val cur = getOutputMode(ctx)
        val next = if (cur == OutputMode.BLUETOOTH) OutputMode.BAL_HEADPHONE_OUT else OutputMode.BLUETOOTH
        setOutputMode(ctx, next)
        return next
    }

    fun isAudioShareEnabled(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("audio_share_enabled", false)
    }

    suspend fun setAudioShareEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("audio_share_enabled", enabled).apply()
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(ctx.contentResolver, "miku_audio_share_enabled", v) }
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (enabled) {
            val target = getAudioShareTarget(ctx)
            when (target) {
                AudioShareTarget.DUAL_44_AND_BT -> {
                    RootShell.execFast("echo bal_po > $SYSFS_BASE/out_mode; setprop vendor.audio.dual_output 1; setprop vendor.audio.bt_dual_stream 1")
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=bal_po")
                }
                AudioShareTarget.DUAL_35_AND_BT -> {
                    RootShell.execFast("echo po > $SYSFS_BASE/out_mode; setprop vendor.audio.dual_output 1; setprop vendor.audio.bt_dual_stream 1")
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=po")
                }
                AudioShareTarget.DUAL_PHYSICAL -> {
                    RootShell.execFast("echo dual_po > $SYSFS_BASE/out_mode; setprop vendor.audio.dual_output 1")
                    am?.setParameters("vendor.audio.dual_output=1")
                }
                AudioShareTarget.WIRED_AND_USB -> {
                    RootShell.execFast("setprop vendor.audio.usb_mirror 1")
                    am?.setParameters("vendor.audio.usb_mirror=1")
                }
            }
        } else {
            RootShell.execFast("setprop vendor.audio.dual_output 0; setprop vendor.audio.bt_dual_stream 0; setprop vendor.audio.usb_mirror 0")
            am?.setParameters("vendor.audio.dual_output=0;vendor.audio.bt_dual_stream=0;vendor.audio.usb_mirror=0")
            // Restore current single output mode
            setOutputMode(ctx, getOutputMode(ctx))
        }
    }

    fun getAudioShareTarget(ctx: Context): AudioShareTarget {
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("audio_share_target", AudioShareTarget.DUAL_44_AND_BT.id)
        return AudioShareTarget.values().firstOrNull { it.id == saved } ?: AudioShareTarget.DUAL_44_AND_BT
    }

    suspend fun setAudioShareTarget(ctx: Context, target: AudioShareTarget) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("audio_share_target", target.id).apply()
        runCatching { Settings.Global.putString(ctx.contentResolver, "miku_audio_share_target", target.id) }
        if (isAudioShareEnabled(ctx)) {
            setAudioShareEnabled(ctx, true)
        }
    }

    /**
     * Tiered read: kernel node, then the vendor Settings.Global row the HAL consumes, then our own
     * prefs mirror (tagged [Source.USER_REQUEST] — a request, NOT a hardware state). Null when no
     * tier has anything. Display surfaces use this and must honour [Reading.isMeasured].
     */
    fun readDigitalFilter(ctx: Context): Reading<DigitalFilter>? {
        readKernelNode("digital_filter")?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.SYSFS) }
        }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.digital_filter",
            "vendor.audio.hiby.digital_filter", "hw.digital_filter")?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.VENDOR_SETTING) }
        }
        readRequestedPref(ctx, "digital_filter")?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.USER_REQUEST) }
        }
        return null
    }

    /** Control-UI getter: falls back to a preset so the picker has a selection. Never a measurement. */
    fun getDigitalFilter(ctx: Context): DigitalFilter =
        readDigitalFilter(ctx)?.value ?: DigitalFilter.FAST_LINEAR

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

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports a gain. */
    fun readGainMode(ctx: Context): Reading<GainMode>? {
        readKernelNode("gain")?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.SYSFS) }
        }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.gain", "vendor.audio.hiby.gain")?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.VENDOR_SETTING) }
        }
        readRequestedPref(ctx, "gain")?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.USER_REQUEST) }
        }
        return null
    }

    /** Control-UI getter: falls back to a preset so the toggle has a selection. Never a measurement. */
    fun getGainMode(ctx: Context): GainMode = readGainMode(ctx)?.value ?: GainMode.HIGH

    suspend fun setGainMode(ctx: Context, mode: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gain", mode.sysfsValue).apply()
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

    private fun parseEnabled(v: String, onToken: String): Boolean =
        v == onToken || v == "1" || v.equals("on", true) || v.contains("enable")

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports DRE. */
    fun readDre(ctx: Context): Reading<Boolean>? {
        readKernelNode("dre_mode")?.let { return Reading(parseEnabled(it, "dremode_enable"), Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.dre", "vendor.audio.hiby.dre_mode")
            ?.let { return Reading(parseEnabled(it, "dremode_enable"), Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "dre_mode")
            ?.let { return Reading(parseEnabled(it, "dremode_enable"), Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: false when unknown, so the switch has a position. Never a measurement. */
    fun isDreEnabled(ctx: Context): Boolean = readDre(ctx)?.value ?: false

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("dre_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/dre_mode; settings put global vendor.audio.hiby.hw.dre $v; setprop vendor.audio.hiby.hw.dre $v")
    }

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports high power. */
    fun readHighPower(ctx: Context): Reading<Boolean>? {
        readKernelNode("high_power_mode")?.let { return Reading(parseEnabled(it, "hpower_enable"), Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.high_power", "vendor.audio.hiby.high_power_mode",
            "vendor.audio.hiby.high_power")?.let { return Reading(parseEnabled(it, "hpower_enable"), Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "high_power_mode")
            ?.let { return Reading(parseEnabled(it, "hpower_enable"), Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: false when unknown, so the switch has a position. Never a measurement. */
    fun isHighPowerEnabled(ctx: Context): Boolean = readHighPower(ctx)?.value ?: false

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("high_power_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/high_power_mode; settings put global vendor.audio.hiby.hw.high_power $v; setprop vendor.audio.hiby.hw.high_power $v")
    }

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports a balance. */
    fun readBalance(ctx: Context): Reading<Int>? {
        readKernelNode("lr_balance")?.toIntOrNull()?.let { return Reading(it, Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.balance")?.toIntOrNull()
            ?.let { return Reading(it, Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "lr_balance")?.toIntOrNull()
            ?.let { return Reading(it, Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: 0 (centre) when unknown, so the slider has a position. Never a measurement. */
    fun getBalance(ctx: Context): Int = readBalance(ctx)?.value ?: 0

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val clamped = balance.coerceIn(-10, 10)
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("lr_balance", clamped.toString()).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.balance", clamped) }
        RootShell.execFast("echo $clamped > $SYSFS_BASE/lr_balance; settings put global vendor.audio.hiby.hw.balance $clamped; setprop vendor.audio.hiby.hw.balance $clamped")
    }

    fun getDsdGainCompensate(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", 1) == 1 } catch (_: Throwable) { true }
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        RootShell.execFast("settings put global vendor.audio.hiby.hw.dsd_gain_comp $v; setprop vendor.audio.hiby.hw.dsd_gain_comp $v")
    }

    // ---- Honest (nullable) readers for DISPLAY surfaces ------------------------------------
    // These report REAL sources ONLY: the kernel sysfs node, or the vendor.audio.hiby.* Settings
    // .Global row the HiBy audio HAL consumes. They used to go through a reader that fell back to
    // our own "miku_dac_settings" SharedPreferences — the exact values our own setters had just
    // written — so "re-reading the DAC" returned our last write and proved nothing. null now means
    // the hardware genuinely cannot be read; render "—", never a guess.
    // For "what the user asked for", use the read*() functions and check Reading.isMeasured.

    fun getDigitalFilterOrNull(ctx: Context): DigitalFilter? =
        readDigitalFilter(ctx)?.takeIf { it.isMeasured }?.value

    fun getGainModeOrNull(ctx: Context): GainMode? =
        readGainMode(ctx)?.takeIf { it.isMeasured }?.value

    fun isDreEnabledOrNull(ctx: Context): Boolean? =
        readDre(ctx)?.takeIf { it.isMeasured }?.value

    fun isHighPowerEnabledOrNull(ctx: Context): Boolean? =
        readHighPower(ctx)?.takeIf { it.isMeasured }?.value

    fun getBalanceOrNull(ctx: Context): Int? =
        readBalance(ctx)?.takeIf { it.isMeasured }?.value

    /** Raw kernel node dump. "N/A" = that node could not be read; no prefs mirror is consulted. */
    fun getLiveHardwareAudit(ctx: Context): Map<String, String> {
        val audit = mutableMapOf<String, String>()
        audit["kernel_sysfs_filter"] = readKernelNode("digital_filter") ?: "N/A"
        audit["kernel_sysfs_gain"] = readKernelNode("gain") ?: "N/A"
        // Both spellings are tried: the setters write "dre_mode", the original audit read "dre".
        audit["kernel_sysfs_dre"] = readKernelNode("dre_mode") ?: readKernelNode("dre") ?: "N/A"
        audit["kernel_sysfs_turbo"] = readKernelNode("audio_turbo") ?: readKernelNode("turbo") ?: "N/A"
        audit["kernel_sysfs_out_mode"] = readKernelNode("out_mode") ?: "N/A"
        audit["kernel_sysfs_balance"] = readKernelNode("lr_balance") ?: "N/A"
        audit["prop_hw_filter"] = RootShell.execOut("getprop vendor.audio.hiby.hw.digital_filter") ?: "N/A"
        audit["prop_hw_gain"] = RootShell.execOut("getprop vendor.audio.hiby.hw.gain") ?: "N/A"
        return audit
    }
}
