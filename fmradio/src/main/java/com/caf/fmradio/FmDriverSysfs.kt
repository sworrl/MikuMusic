package com.caf.fmradio

import android.util.Log
import java.io.File

/**
 * HiBy's own sysfs controls on the Si4705 driver.
 *
 * The driver bound at `/sys/bus/i2c/devices/2-0063` (kernel log: `si4705 2-0063: power_gpio get
 * success`) exposes four attributes that are not part of any upstream si470x driver:
 *
 *     radio_switch  radio_freq  radio_seek_start  radio_info
 *
 * They are denied to a root-less `adb shell`, which is why they were not found earlier, but this
 * app runs in the `vendor_fm_app` SELinux domain and may be able to reach them. `radio_switch` in
 * particular is the right shape for the thing currently missing: the tuner receives, the HAL
 * routes correctly, and the audio is constant noise, which is what an enabled codec port with
 * nothing driving it sounds like.
 *
 * Nothing here writes anything. It reads, reports what it finds, and says plainly when it is
 * denied, so the diagnostics panel can show whether this is a usable control surface before any
 * decision is made to use it.
 */
object FmDriverSysfs {

    private const val TAG = "FmDriverSysfs"
    private const val BASE = "/sys/bus/i2c/devices/2-0063"

    val ATTRS = listOf("radio_switch", "radio_freq", "radio_info", "radio_seek_start")

    data class Probe(val name: String, val value: String?, val problem: String?)

    /** Read each attribute once. A null value with a problem string means it exists but is shut. */
    fun probe(): List<Probe> = ATTRS.map { name ->
        val f = File(BASE, name)
        try {
            if (!f.exists()) Probe(name, null, "not present")
            else if (!f.canRead()) Probe(name, null, "exists, not readable by this process")
            else Probe(name, f.readText().trim().take(120), null)
        } catch (t: Throwable) {
            Probe(name, null, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** One line for logcat, so a session log carries the answer without opening the UI. */
    fun logProbe() {
        Log.i(TAG, probe().joinToString("  ") { p ->
            "${p.name}=${p.value ?: "<${p.problem}>"}"
        })
    }

    /** True when at least one attribute could actually be read. */
    fun anyReadable(): Boolean = probe().any { it.value != null }
}
