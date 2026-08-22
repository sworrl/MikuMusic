package com.miku.launcher.volume

import android.util.Log
import com.miku.launcher.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SGM31324 "Pulsar Light" RGB driver — volume-level color feedback.
 *
 * History: under stock firmware this LED was fully dead — sysfs nodes SELinux-denied even to the
 * adb shell, no light service, no consumer path (the previous investigation ruled it out WITHOUT
 * root). MikuOS is rooted and owns policy, which was explicitly the "new information" condition:
 * all writes go through the root shell, and the boot rc opens the node permissions.
 *
 * Behavior (user spec): while the volume HUD is (or would be) on screen, the LED shows the level
 * as color — BLUE low → PURPLE mid → RED high, smoothly interpolated — then turns off when the
 * HUD window ends.
 *
 * The exact sysfs ABI of led_pattern/write_pattern is undocumented; [applyColor] therefore fires
 * a small battery of plausible write formats (RGB triple, hex, channel splits) in one root exec —
 * harmless no-ops for whichever forms the driver rejects — and [probe] captures the node listing
 * once so a live `adb logcat` session can pin down the real format for tightening later.
 */
object MikuPulsarLight {
    private const val TAG = "MikuPulsarLight"
    private const val LED_DIR =
        "/sys/devices/platform/soc/4ac0000.qcom,qupv3_0_geni_se/4a94000.i2c/i2c-2/2-0030/leds/sgm31324-leds"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var offJob: Job? = null
    @Volatile private var probed = false
    @Volatile private var available = false

    /** One-time discovery: does the node dir exist and what does it expose? Root-read. */
    private fun probe() {
        if (probed) return
        probed = true
        val ls = RootShell.execOut("ls -l $LED_DIR 2>/dev/null")
        available = !ls.isNullOrBlank()
        if (available) {
            Log.i(TAG, "Pulsar nodes: $ls")
            Log.i(TAG, "trigger opts: ${RootShell.execOut("cat $LED_DIR/trigger 2>/dev/null")}")
            Log.i(TAG, "led_pattern: ${RootShell.execOut("cat $LED_DIR/led_pattern 2>/dev/null")}")
        } else {
            Log.i(TAG, "Pulsar LED dir not present/readable — feature stays dark")
        }
    }

    /** Volume % → color. Blue (low) → purple (mid) → red (high), two-segment linear blend. */
    private fun colorFor(pct: Int): Triple<Int, Int, Int> {
        val t = (pct.coerceIn(0, 100)) / 100f
        return if (t <= 0.5f) {
            val k = t / 0.5f                       // blue -> purple
            Triple((0 + k * 170).toInt(), (60 * (1 - k)).toInt(), 255)
        } else {
            val k = (t - 0.5f) / 0.5f              // purple -> red
            Triple((170 + k * 85).toInt(), 0, (255 - k * 225).toInt())
        }
    }

    private fun applyColor(r: Int, g: Int, b: Int) {
        val hex = String.format("%02X%02X%02X", r, g, b)
        // One batched root exec: take manual control, then try every plausible ABI.
        RootShell.execFast(
            "cd $LED_DIR 2>/dev/null && " +
            "echo none > trigger 2>/dev/null; " +
            "echo 255 > brightness 2>/dev/null; " +
            "echo '$r $g $b' > write_pattern 2>/dev/null; " +
            "echo $hex > write_pattern 2>/dev/null; " +
            "echo '$r $g $b' > led_pattern 2>/dev/null; " +
            "echo $hex > led_pattern 2>/dev/null"
        )
    }

    private fun off() {
        RootShell.execFast(
            "cd $LED_DIR 2>/dev/null && " +
            "echo 0 > brightness 2>/dev/null; " +
            "echo '0 0 0' > write_pattern 2>/dev/null; " +
            "echo 000000 > write_pattern 2>/dev/null"
        )
    }

    /**
     * Show the volume level color for the HUD window, then fade off. Called from
     * [MikuVolumeManager.triggerHud] on every volume change — including when the HiBy fullscreen
     * dialog owns the on-screen UI ("is or WOULD be on screen").
     */
    fun showVolume(pct: Int, windowMs: Long = 3500L) {
        scope.launch {
            probe()
            if (!available) return@launch
            val (r, g, b) = colorFor(pct)
            applyColor(r, g, b)
            offJob?.cancel()
            offJob = scope.launch {
                delay(windowMs)
                off()
            }
        }
    }
}
