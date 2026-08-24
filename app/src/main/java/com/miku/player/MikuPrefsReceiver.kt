package com.miku.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cross-app preference and playback control bridge:
 * Lets the MikuOS lockscreen, launcher, and quick-settings flip player behavior live
 * (pause-on-unplug, toggle/set track like) without binding the player process.
 */
class MikuPrefsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SET_PAUSE_ON_UNPLUG = "com.miku.player.SET_PAUSE_ON_UNPLUG"
        const val ACTION_TOGGLE_LIKE = "com.miku.player.action.TOGGLE_LIKE"
        const val ACTION_SET_LIKE = "com.miku.player.action.SET_LIKE"
        const val EXTRA_ENABLED = "enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_PAUSE_ON_UNPLUG -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                PlayerHolder.applyPauseOnUnplug(context, enabled)
            }
            ACTION_TOGGLE_LIKE, "com.miku.player.action.TOGGLE_LIKE" -> {
                LikeStore.init(context)
                val p = PlayerHolder.player
                val trackId = intent.getLongExtra("track_id", -1L).takeIf { it > 0 }
                    ?: p?.currentMediaItem?.mediaId?.toLongOrNull()
                    ?: PlayerPreferences.loadLastTrackId(context)
                if (trackId > 0) {
                    LikeStore.toggle(context, trackId)
                }
            }
            ACTION_SET_LIKE, "com.miku.player.action.SET_LIKE" -> {
                LikeStore.init(context)
                val p = PlayerHolder.player
                val trackId = intent.getLongExtra("track_id", -1L).takeIf { it > 0 }
                    ?: p?.currentMediaItem?.mediaId?.toLongOrNull()
                    ?: PlayerPreferences.loadLastTrackId(context)
                val targetLiked = intent.getBooleanExtra("is_liked", false)
                if (trackId > 0) {
                    val currentlyLiked = LikeStore.isLiked(trackId)
                    if (currentlyLiked != targetLiked) {
                        LikeStore.toggle(context, trackId)
                    }
                }
            }
        }
    }
}
