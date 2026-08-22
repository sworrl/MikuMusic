package com.miku.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cross-app preference bridge: lets the MikuOS launcher's quick-settings shade flip player
 * behavior live (currently pause-on-unplug) without binding the player process. The launcher
 * broadcasts [ACTION_SET_PAUSE_ON_UNPLUG] with [EXTRA_ENABLED]; state is mirrored in
 * Settings.Global "miku_pause_on_unplug" for the launcher's tile to read.
 */
class MikuPrefsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SET_PAUSE_ON_UNPLUG = "com.miku.player.SET_PAUSE_ON_UNPLUG"
        const val EXTRA_ENABLED = "enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_PAUSE_ON_UNPLUG -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                PlayerHolder.applyPauseOnUnplug(context, enabled)
            }
        }
    }
}
