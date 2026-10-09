package com.caf.fmradio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlin.math.exp

/**
 * The FM audio path, reconstructed from stock FM2.
 *
 * WHY THIS FILE EXISTS. The tuner powered on, tuned, locked RDS and reported stereo, and there
 * was still no sound. The engine had been telling the audio HAL `handle_fm=1`, which looks like
 * a boolean and is not one. In the QTI HAL `handle_fm` carries an output-device bitmask, and the
 * HAL only starts the FM session when AUDIO_DEVICE_OUT_FM (0x100000) is set in it. A value of 1
 * has that bit clear, so every "power on" was in fact an instruction to STOP FM. The tuner ran,
 * the capture stream existed, and it carried digital silence.
 *
 * Everything below is what stock FM2's `AudioRouting()` and `setFMVolume()` do, read out of
 * /system_ext/app/FM2/FM2.apk with baksmali on 2026-10-09. Stock works on this hardware, so the
 * parameter strings are reproduced rather than re-derived.
 *
 * The device codes are HiBy's, not audio_devices_t and not AudioDeviceInfo.TYPE_*: the mapping
 * inverts 3 and 4 relative to the framework constants, because HiBy needed a code for the 4.4 mm
 * balanced output that the framework has no type for. They surface it as a TYPE_WIRED_HEADPHONES
 * device whose productName is literally "balance".
 */
object FmAudioRoute {

    private const val TAG = "FmAudioRoute"

    /** AUDIO_DEVICE_OUT_FM. Setting this bit in `handle_fm` is what starts the HAL's FM session. */
    const val AUDIO_DEVICE_OUT_FM = 0x100000

    /** HiBy FM-HAL output codes. */
    const val DEV_SPEAKER = 2
    const val DEV_BALANCED = 3
    const val DEV_HEADPHONE = 4
    const val DEV_A2DP = 8

    /** 20 / ln(10), the dB-to-linear exponent stock uses: gain = exp(dB * k) = 10^(dB/20). */
    private const val DB_TO_LINEAR = 0.11512925f

    data class Route(val code: Int, val label: String)

    fun describe(code: Int): String = when (code) {
        DEV_BALANCED -> "Balanced 4.4 mm"
        DEV_HEADPHONE -> "Headphone 3.5 mm"
        DEV_A2DP -> "Bluetooth"
        DEV_SPEAKER -> "Speaker"
        else -> "Device $code"
    }

    /**
     * Stock's `getOutputAddress()` followed by its address-to-code switch, collapsed into one
     * step. The product name lookup is the only way to tell the balanced output from the
     * single-ended one: both enumerate as TYPE_WIRED_HEADPHONES.
     */
    fun current(am: AudioManager): Route {
        val code = runCatching { computeCode(am) }.getOrElse {
            Log.w(TAG, "route lookup failed, assuming speaker: ${it.javaClass.simpleName}: ${it.message}")
            DEV_SPEAKER
        }
        return Route(code, describe(code))
    }

    private fun computeCode(am: AudioManager): Int {
        // Name of whatever wired-headphone device is attached, if any.
        var wiredName = ""
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                wiredName = d.productName?.toString().orEmpty()
            }
        }

        val type = activeMediaDeviceType(am) ?: return fallbackCode(am)

        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> DEV_HEADPHONE
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                if (wiredName == "balance") DEV_BALANCED else DEV_HEADPHONE
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> DEV_A2DP
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> DEV_HEADPHONE
            else -> DEV_SPEAKER
        }
    }

    /**
     * Which output the policy manager would send media to right now.
     *
     * `AudioManager.getDevicesForAttributes` is a system API, so it is not in the SDK jar this
     * module compiles against even though this app may call it. Reflection keeps the source
     * buildable without a hidden-API shim, and a failure here is not fatal: [fallbackCode]
     * scans the attached outputs instead, which is also what stock does when the list is empty.
     */
    private fun activeMediaDeviceType(am: AudioManager): Int? = runCatching {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val m = AudioManager::class.java.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
        val list = m.invoke(am, attrs) as? List<*> ?: return@runCatching null
        val first = list.firstOrNull() ?: return@runCatching null
        first.javaClass.getMethod("getType").invoke(first) as? Int
    }.getOrNull()

    /** Stock's second pass, for when the policy manager reports no active device yet. */
    private fun fallbackCode(am: AudioManager): Int {
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> return DEV_A2DP
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return DEV_HEADPHONE
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                    return if (d.productName?.toString() == "balance") DEV_BALANCED else DEV_HEADPHONE
            }
        }
        return DEV_SPEAKER
    }

    /**
     * Ask the HAL whether its FM hardware loopback is currently up.
     *
     * `fm_status` is a GET key, not a SET key — the engine used to write it, which did nothing.
     * Read back it is the only root-free confirmation that the FM session actually started, so
     * it is what the diagnostics panel reports instead of an assumption.
     */
    fun loopbackActive(am: AudioManager): Boolean? = runCatching {
        // The HAL answers with the whole pair, e.g. "fm_status=1", not a bare "1".
        val v = am.getParameters("fm_status") ?: return@runCatching null
        if (v.isBlank()) null else v.trim().endsWith("1")
    }.getOrNull()

    /**
     * Start the HAL's FM session on [code], in stock FM2's exact order.
     *
     * Captured from a stock session on this hardware 2026-10-09:
     *
     *     setParameters fm_routing=1048579   -> HAL does fm_stop then fm_start
     *     setParameters fm_volume=0.052481   -> HAL applies it to the running session
     *     setParameters handle_fm=1048579    -> already running, so the HAL just notes it
     *
     * `fm_routing` is what actually brings the session up; `handle_fm` arrives last and is a
     * no-op by then. Doing it the other way round works too, but the HAL then starts the
     * backend before it has a volume, and matching stock removes a variable rather than
     * leaving one in.
     */
    fun start(am: AudioManager, code: Int): Int {
        val withFm = code or AUDIO_DEVICE_OUT_FM
        am.setParameters("fm_routing=$withFm")
        Log.i(TAG, "fm_routing=$withFm (${describe(code)}) [start]")
        val gain = applyVolume(am, code)
        am.setParameters("handle_fm=$withFm")
        Log.i(TAG, "handle_fm=$withFm, fm_volume=$gain")
        return withFm
    }

    /** Move a running session to a new output without stopping it. */
    fun reroute(am: AudioManager, code: Int): Int {
        val withFm = code or AUDIO_DEVICE_OUT_FM
        am.setParameters("fm_routing=$withFm")
        Log.i(TAG, "fm_routing=$withFm (${describe(code)})")
        return withFm
    }

    /** Stop it. Stock writes the bare device code, i.e. the same value with the FM bit cleared. */
    fun stop(am: AudioManager, code: Int) {
        am.setParameters("handle_fm=$code")
        Log.i(TAG, "handle_fm=$code (stop)")
    }

    fun setMuted(am: AudioManager, muted: Boolean) {
        am.setParameters("fm_mute=${if (muted) 1 else 0}")
    }

    /**
     * Track STREAM_MUSIC onto the HAL's FM gain, the way stock does: look up the real dB value
     * the volume curve assigns to this index on this device, then convert to linear.
     *
     * Returns the gain written, or null if the lookup failed. The engine shows that value rather
     * than claiming a volume it could not set.
     */
    fun applyVolume(am: AudioManager, code: Int): Float? = runCatching {
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, code)
        val linear = if (db.isInfinite() || db.isNaN()) 0f else exp(db * DB_TO_LINEAR)
        am.setParameters("fm_volume=$linear")
        linear
    }.getOrElse {
        Log.w(TAG, "fm_volume not applied: ${it.javaClass.simpleName}: ${it.message}")
        null
    }
}
