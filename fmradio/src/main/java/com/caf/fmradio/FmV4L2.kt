package com.caf.fmradio

import android.util.Log
import qcom.fmradio.FmReceiverJNI

/**
 * HiBy's direct V4L2 hooks on /dev/radio0, wrapped so a missing or renamed native never takes
 * the tuner down with it.
 *
 * These live in the same `qcom.fmradio.jar` as the HCI API but are HiBy additions, so they are
 * resolved at runtime from the framework and could in principle not exist on a future build.
 * Every call here reports unavailability as null/false rather than throwing, and each distinct
 * failure is logged exactly once so a broken hook is visible without flooding logcat.
 *
 * The important one is [setMute]. The HiBy driver comes up muted and `FmReceiver.setMuteMode()`
 * does not clear it: that goes over HCI to the FM core, while the audio actually passes through
 * the V4L2 layer's own mute. Stock FM2 mutes here while it builds the route and unmutes 300 ms
 * later, which is also where its lack of a power-on pop comes from.
 */
object FmV4L2 {

    private const val TAG = "FmV4L2"

    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (reported.add(what)) {
            Log.w(TAG, "$what unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
        null
    }

    /** 1 = muted. Returns true when the driver accepted the call. */
    fun setMute(muted: Boolean): Boolean =
        guard("setV4L2RadioFmMute") { FmReceiverJNI.setV4L2RadioFmMute(if (muted) 1 else 0) } != null

    fun isMuted(): Boolean? = guard("getV4L2RadioFmMute") { FmReceiverJNI.getV4L2RadioFmMute() != 0 }

    /**
     * Tune through the V4L2 layer, which is how stock FM2 tunes on this device.
     *
     * This matters more than it looks. `FmReceiver.setStation()` goes over HCI and reports a
     * TuneStatus callback, so it looks like it worked — but after one, `getV4L2RadioFrequency()`
     * still read the bottom of the band. Stock's own tune() takes the HiBy branch and calls
     * this instead. Both are issued now, and the diagnostics panel shows the V4L2 read-back so
     * the two can be compared rather than assumed equal.
     */
    fun setTunedKHz(khz: Int): Boolean =
        guard("setV4L2RadioFrequency") { FmReceiverJNI.setV4L2RadioFrequency(khz * 16) } != null

    /**
     * Set the tuner part's own receive volume.
     *
     * Worth a look because of what the symptom is: the HAL enables the FM codec port and the
     * output is constant noise that does not change with frequency, which is what an enabled
     * port with nothing driving it sounds like. If the Si4705's output is sitting at zero then
     * everything upstream can be correct and still produce exactly that.
     *
     * Range is a guess (the part takes 0..63), so the debug hook sweeps it rather than
     * trusting the guess.
     */
    fun setRadioVolume(v: Int): Boolean =
        guard("setV4L2RadioFmVolume") { FmReceiverJNI.setV4L2RadioFmVolume(v) } != null

    /** Frequency the driver believes it is on, in kHz. V4L2 counts in 1/16 kHz units. */
    fun tunedKHz(): Int? = guard("getV4L2RadioFrequency") {
        val raw = FmReceiverJNI.getV4L2RadioFrequency()
        if (raw <= 0) null else raw / 16
    }

    /**
     * One signal reading straight off the tuner. Element order is stock FM2's own labelling of
     * the same array; anything the array is too short to contain comes back null rather than 0,
     * because 0 is a legitimate value for most of these.
     */
    data class Reading(
        val signal: Int?,
        val rssi: Int?,
        val snr: Int?,
        val multipath: Int?,
        val freqOffset: Int?,
        val freqKHz: Int?,
        val valid: Boolean?,
    ) {
        companion object { val EMPTY = Reading(null, null, null, null, null, null, null) }
    }

    fun signal(): Reading? = guard("getV4L2RadioFmSignal") {
        val a = FmReceiverJNI.getV4L2RadioFmSignal() ?: return@guard null
        fun at(i: Int): Int? = if (i < a.size) a[i] else null
        Reading(
            signal = at(0),
            rssi = at(1),
            snr = at(2),
            multipath = at(3),
            freqOffset = at(4),
            freqKHz = at(5)?.let { if (it > 0) it / 16 else null },
            valid = at(6)?.let { it != 0 },
        )
    }
}
