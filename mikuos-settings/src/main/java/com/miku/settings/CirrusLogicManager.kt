package com.miku.settings

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
        LOW("low", "Low Gain (0 dB)", "low", "Optimized for high-sensitivity IEMs and low-impedance earphones"),
        HIGH("high", "High Gain (+6 dB)", "high", "High-voltage rail swing for demanding planar magnetic and high-impedance headphones")
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

    private fun readSysfs(ctx: Context, node: String): String? {
        return try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
                prefs.getString(node, null)
            }
        } catch (_: Throwable) {
            val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
            prefs.getString(node, null)
        }
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
                RootShell.execFast(
                    "echo bal_po > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "echo bal_po > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "echo 0 > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode bal_po; " +
                    "setprop vendor.audio.hiby.hw.output_mode bal_po"
                )
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
                RootShell.execFast(
                    "echo po > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "echo po > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "echo 0 > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode po; " +
                    "setprop vendor.audio.hiby.hw.output_mode po"
                )
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
                RootShell.execFast(
                    "echo lo > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "echo bal_lo > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "echo lo > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode lo; " +
                    "setprop vendor.audio.hiby.hw.output_mode lo"
                )
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
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode auto; setprop vendor.audio.hiby.hw.output_mode auto")
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
                    RootShell.execFast("setprop vendor.audio.dual_output 1; setprop vendor.audio.bt_dual_stream 1")
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=bal_po")
                }
                AudioShareTarget.DUAL_35_AND_BT -> {
                    RootShell.execFast("setprop vendor.audio.dual_output 1; setprop vendor.audio.bt_dual_stream 1")
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=po")
                }
                AudioShareTarget.DUAL_PHYSICAL -> {
                    RootShell.execFast("setprop vendor.audio.dual_output 1")
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

    fun getDigitalFilter(ctx: Context): DigitalFilter {
        val kernelVal = readSysfs(ctx, "digital_filter")
        if (!kernelVal.isNullOrBlank()) {
            DigitalFilter.values().firstOrNull { it.id == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = try {
            Settings.Global.getString(cr, "vendor.audio.hiby.hw.digital_filter")
                ?: Settings.Global.getString(cr, "vendor.audio.hiby.digital_filter")
                ?: Settings.Global.getString(cr, "hw.digital_filter")
        } catch (_: Throwable) { null } ?: ""
        return DigitalFilter.values().firstOrNull { it.id == raw.trim().lowercase() } ?: DigitalFilter.FAST_LINEAR
    }

    suspend fun setDigitalFilter(ctx: Context, filter: DigitalFilter) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "hw.digital_filter", filter.id) }

        RootShell.execFast(
            "echo ${filter.id} > $SYSFS_BASE/digital_filter 2>/dev/null; " +
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
        val kernelVal = readSysfs(ctx, "gain")
        if (!kernelVal.isNullOrBlank()) {
            GainMode.values().firstOrNull { it.sysfsValue == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = try {
            Settings.Global.getString(cr, "vendor.audio.hiby.hw.gain")
                ?: Settings.Global.getString(cr, "vendor.audio.hiby.gain")
        } catch (_: Throwable) { null } ?: ""
        return GainMode.values().firstOrNull { it.sysfsValue == raw.trim().lowercase() } ?: GainMode.HIGH
    }

    suspend fun setGainMode(ctx: Context, mode: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gain", mode.sysfsValue).apply()
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", mode.sysfsValue) }

        RootShell.execFast(
            "echo ${mode.sysfsValue} > $SYSFS_BASE/gain 2>/dev/null; " +
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
        val kernelVal = readSysfs(ctx, "dre_mode")
        if (kernelVal != null) return kernelVal == "dremode_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dre", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("dre_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/dre_mode 2>/dev/null; settings put global vendor.audio.hiby.hw.dre $v; setprop vendor.audio.hiby.hw.dre $v")
    }

    fun isHighPowerEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs(ctx, "high_power_mode")
        if (kernelVal != null) return kernelVal == "hpower_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.high_power", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("high_power_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        RootShell.execFast("echo $sysfsStr > $SYSFS_BASE/high_power_mode 2>/dev/null; settings put global vendor.audio.hiby.hw.high_power $v; setprop vendor.audio.hiby.hw.high_power $v")
    }

    fun getBalance(ctx: Context): Int {
        val kernelVal = (readSysfs(ctx, "lrbalance") ?: readSysfs(ctx, "lr_balance"))?.toIntOrNull()
        if (kernelVal != null) return kernelVal
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.balance", 0) } catch (_: Throwable) { 0 }
    }

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val clamped = balance.coerceIn(-10, 10)
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("miku_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("lrbalance", clamped.toString()).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.balance", clamped) }
        RootShell.execFast("echo $clamped > $SYSFS_BASE/lrbalance 2>/dev/null; settings put global vendor.audio.hiby.hw.balance $clamped; setprop vendor.audio.hiby.hw.balance $clamped")
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

    fun getLiveHardwareAudit(ctx: Context): Map<String, String> {
        val audit = mutableMapOf<String, String>()
        audit["kernel_sysfs_filter"] = readSysfs(ctx, "digital_filter") ?: "N/A"
        audit["kernel_sysfs_gain"] = readSysfs(ctx, "gain") ?: "N/A"
        audit["kernel_sysfs_dre"] = readSysfs(ctx, "dre_mode") ?: "N/A"
        audit["kernel_sysfs_turbo"] = readSysfs(ctx, "turbo") ?: "N/A"
        audit["kernel_sysfs_out_mode"] = readSysfs(ctx, "bal_po_lo_switch") ?: "N/A"
        audit["kernel_sysfs_balance"] = readSysfs(ctx, "lrbalance") ?: "N/A"
        audit["prop_hw_filter"] = RootShell.execOut("getprop vendor.audio.hiby.hw.digital_filter") ?: "N/A"
        audit["prop_hw_gain"] = RootShell.execOut("getprop vendor.audio.hiby.hw.gain") ?: "N/A"
        return audit
    }
}
