package com.miku.player

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

/**
 * Hardware controller for the HiBy M500's Dual Cirrus Logic CS43198 DAC
 * and audiophile subsystem via Direct Kernel Sysfs (`/sys/devices/platform/sa_sound_setting/`),
 * HAL System Properties, AudioRouting HAL, and Android Global Settings.
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

    enum class GainMode(val id: String, val label: String, val sysfsValue: String, val description: String) {
        LOW("low", "Low Gain (0 dB)", "low", "Optimized for ultra-sensitive in-ear monitors (zero noise floor)"),
        HIGH("high", "High Gain (+6 dB)", "high", "High voltage swing for planar and high-impedance headphones")
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
     * Live DAC readout. Every field is nullable: null = that node/property could not be read, and
     * the UI must render it as "—" (see [kernelFilterText] and friends). [isSysfsReadable] is true
     * only when at least one node actually came back — it is NOT a claim that the kernel state and
     * the app's settings agree, which is what the old `isHardwareSynced` (hardcoded true, with
     * per-node "typical" values standing in for unreadable nodes) pretended to report.
     */
    data class HardwareAuditState(
        val kernelFilter: String? = null,
        val kernelGain: String? = null,
        val kernelHighPower: String? = null,
        val kernelDre: String? = null,
        val kernelTurbo: String? = null,
        val kernelOutput: String? = null,
        val kernelBalance: String? = null,
        val isSysfsReadable: Boolean = false
    ) {
        val kernelFilterText: String get() = kernelFilter ?: "—"
        val kernelGainText: String get() = kernelGain ?: "—"
        val kernelHighPowerText: String get() = kernelHighPower ?: "—"
        val kernelDreText: String get() = kernelDre ?: "—"
        val kernelTurboText: String get() = kernelTurbo ?: "—"
        val kernelOutputText: String get() = kernelOutput ?: "—"
        val kernelBalanceText: String get() = kernelBalance ?: "—"
    }

    /**
     * No-op kept for call-site compatibility. It used to chmod the sa_sound_setting sysfs nodes
     * through su, which never runs on MikuOS (no root) - and the DAC does not need it: every write
     * this object performs now goes through the audio HAL (AudioManager.setParameters) and
     * Settings, which are the paths the vendor stack actually reads.
     */
    fun init(ctx: Context) {
        // Intentionally empty - see KDoc.
    }

    private fun readSysfs(node: String): String? {
        try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists()) {
                val txt = file.readText().trim()
                if (txt.isNotEmpty()) return txt
            }
        } catch (_: Throwable) {}
        // No su fallback: the node is either world-readable (handled above) or it is not readable
        // by this app at all, and null is the honest answer - the audit UI renders it as "-".
        return null
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

        // The HAL parameter is the route that actually lands on this hardware. (The old
        // echo-to-sysfs / setprop line ran through su, which does not exist on MikuOS: the
        // Settings row that [getDigitalFilter] reads back changed while the DAC did not.)
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.digital_filter", filter.id)
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.digital_filter", filter.id)
    }

    fun getGainMode(ctx: Context): GainMode {
        val kernelVal = readSysfs("gain")
        if (!kernelVal.isNullOrBlank()) {
            GainMode.values().firstOrNull { it.sysfsValue == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val g = Settings.Global.getString(cr, "vendor.audio.hiby.hw.gain")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.gain") ?: ""
        return GainMode.values().firstOrNull { it.sysfsValue == g.trim().lowercase() } ?: GainMode.HIGH
    }

    suspend fun setGainMode(ctx: Context, gain: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", gain.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", gain.sysfsValue) }

        // Apply it for real. The old path shelled out to su (echo > sysfs / setprop) which is a
        // guaranteed no-op on MikuOS (no root) - the Settings rows changed but the DAC stayed on
        // whatever gain it booted with (found live 2026-09-11: hw.gain=low while the UI said
        // otherwise, IEMs quiet). The HAL accepts these as parameters on the platform key.
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.gain", gain.sysfsValue)
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.gain", gain.sysfsValue)
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
                am?.setParameters("routing=128;vendor.audio.hiby.hw.output_mode=bt")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val btDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_HEARING_AID || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (btDev != null) am.setCommunicationDevice(btDev)
                }
            }
            OutputMode.LINE_OUT -> {
                am?.setParameters("routing=8;vendor.audio.hiby.hw.output_mode=lo")
            }
            OutputMode.USB_DAC -> {
                am?.setParameters("routing=16384;vendor.audio.hiby.hw.output_mode=usb")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val usbDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    }
                    if (usbDev != null) am.setCommunicationDevice(usbDev)
                }
            }
            OutputMode.AUTO -> {
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
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=bal_po")
                }
                AudioShareTarget.DUAL_35_AND_BT -> {
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=po")
                }
                AudioShareTarget.DUAL_PHYSICAL -> {
                    am?.setParameters("vendor.audio.dual_output=1")
                }
                AudioShareTarget.WIRED_AND_USB -> {
                    am?.setParameters("vendor.audio.usb_mirror=1")
                }
            }
        } else {
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

    fun isDreEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("dre_mode")
        if (kernelVal != null) return kernelVal == "dremode_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dre", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        // Root-free route that actually applies (the old su/sysfs line was a no-op on MikuOS), so the
        // toggle's state matches the DAC instead of only matching a Settings row.
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dre", v.toString())
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dre_mode", sysfsStr)
    }

    fun isHighPowerEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("high_power_mode")
        if (kernelVal != null) return kernelVal == "hpower_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.high_power", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.high_power", v.toString())
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.high_power_mode", sysfsStr)
    }

    fun getDsdGainCompensate(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", 1) == 1 } catch (_: Throwable) { true }
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        MikuDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dsd_gain_comp", v.toString())
    }

    /** Read a system property root-free (platform-signed app, android.os.SystemProperties). */
    private fun sysProp(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String)
            .trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Reads whatever the DAC actually exposes right now. Nodes that cannot be read come back null
     * (rendered "—"); nothing is substituted. The turbo property is read through SystemProperties
     * rather than a `getprop` shell-out, which needed su and therefore always failed on MikuOS.
     */
    fun getLiveHardwareAudit(): HardwareAuditState {
        val filter = readSysfs("digital_filter")
        val gain = readSysfs("gain")
        val hp = readSysfs("high_power_mode")
        val dre = readSysfs("dre_mode")
        val turbo = sysProp("vendor.audio.hiby.hw.audio_turbo")
        val out = readSysfs("out_mode")
        val bal = readSysfs("lr_balance")
        return HardwareAuditState(
            kernelFilter = filter,
            kernelGain = gain,
            kernelHighPower = hp,
            kernelDre = dre,
            kernelTurbo = turbo,
            kernelOutput = out,
            kernelBalance = bal,
            isSysfsReadable = listOf(filter, gain, hp, dre, out, bal).any { it != null }
        )
    }
}
