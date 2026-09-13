package com.miku.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * OS-side owner of the front Pulsar indicator.
 *
 * Miku Music used to drive the diode itself — its own sysfs writes and its own animation
 * coroutines, in parallel with this process. Per the owner's directive (2026-09-13) the indicator
 * is OS-level: the player now only *asks*, via `com.miku.launcher.action.PULSAR`, and every
 * hardware decision is made here in [PulsarLight].
 *
 * This receiver also publishes `Settings.Global miku_pulsar_hw_writable`, which is how the player
 * knows whether to tell the user the light will actually respond — it no longer probes sysfs.
 * On this unit the nodes are SELinux-locked, so that flag is normally 0.
 */
class PulsarReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.miku.launcher.action.PULSAR"
        const val EXTRA_OP = "op"
        const val EXTRA_VALUE = "value"
        const val GLOBAL_HW_WRITABLE = "miku_pulsar_hw_writable"

        /** Publish whether the diode is really drivable so clients stop guessing. Safe to call often. */
        fun publishHardwareState(ctx: Context) {
            val writable = PulsarLight.isHardwareWritable()
            runCatching {
                Settings.Global.putInt(ctx.applicationContext.contentResolver, GLOBAL_HW_WRITABLE, if (writable) 1 else 0)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Nothing here may throw: an exported receiver that throws takes the launcher down with it.
        try {
            if (intent.action != ACTION) return
            val ctx = context.applicationContext
            val op = intent.getStringExtra(EXTRA_OP) ?: return
            val value = intent.getStringExtra(EXTRA_VALUE)
            publishHardwareState(ctx)

            when (op) {
                "mode" -> {
                    val mode = PulsarLight.Mode.values().firstOrNull { it.id == value } ?: return
                    PulsarLight.setMode(ctx, mode)
                    PulsarLight.applyMode(ctx, mode, PulsarLight.getBrightness(ctx))
                }
                "enabled" -> {
                    val on = value == "1"
                    if (on) PulsarLight.applyMode(ctx, PulsarLight.getMode(ctx), PulsarLight.getBrightness(ctx))
                    else PulsarLight.applyMode(ctx, PulsarLight.Mode.OFF, 0)
                }
                "bpm_sync" -> {
                    val on = value == "1"
                    PulsarLight.setBpmSyncEnabled(ctx, on)
                    if (on) PulsarLight.startBpmSync(ctx)
                }
                "brightness" -> value?.toIntOrNull()?.let { b ->
                    PulsarLight.setBrightness(ctx, b)
                    PulsarLight.applyMode(ctx, PulsarLight.getMode(ctx), b)
                }
                // Momentary feedback flashes. The engine here owns the actual pattern; the player
                // only reports that the event happened.
                "hearted" -> PulsarLight.indicateHearted(ctx, value == "1")
                "pocket_lock" -> PulsarLight.indicatePocketLock(ctx, value == "1")
                // "<isPlaying 0|1>:<AudioFormatTier name|NONE>" — the player classifies the track
                // (only it knows one); this side decides what the diode does about it.
                "playback" -> {
                    val parts = value?.split(":") ?: return
                    if (parts.size == 2) PulsarLight.onPlaybackState(ctx, parts[0] == "1", parts[1])
                }
                // anim_speed / custom_hex are persisted by the player and mirrored into
                // Settings.Global; the engine reads them when it next applies a mode.
                "anim_speed", "custom_hex" ->
                    PulsarLight.applyMode(ctx, PulsarLight.getMode(ctx), PulsarLight.getBrightness(ctx))
                else -> Log.w("MikuOS_Pulsar", "unknown pulsar op: $op")
            }
        } catch (t: Throwable) {
            Log.w("MikuOS_Pulsar", "pulsar op failed", t)
        }
    }
}
