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
        /** Launcher "Force Scan" → LOCAL rescan of every storage volume (SD + internal): re-mirrors
         *  MediaStore into the FastLibraryStore index AND walks the volumes for files MediaStore
         *  hasn't indexed yet. Never touches the network; works with the ingress engine OFF. */
        const val ACTION_PROFILE = "com.miku.player.action.PROFILE"
        const val ACTION_PRESET_SELFTEST = "com.miku.player.action.PRESET_SELFTEST"
        const val ACTION_FORCE_LIBRARY_SCAN = "com.miku.player.action.FORCE_LIBRARY_SCAN"
        const val ACTION_RESCAN_LIBRARY = "com.miku.player.action.RESCAN_LIBRARY"
        /** Flip the ingress engine (Settings.Global miku_ingest_enabled) — extra "enabled". */
        const val ACTION_SET_INGEST_ENABLED = "com.miku.player.action.SET_INGEST_ENABLED"
        /** Instant random mode: start a random library track immediately (see InstantRandom). */
        const val ACTION_PLAY_RANDOM = "com.miku.player.action.PLAY_RANDOM"
        const val EXTRA_ENABLED = "enabled"

        /** The one local-rescan entry point everything funnels through (receiver, Force Scan
         *  button, storage modal). Foreground-service start is legal from a receiver on 14. */
        fun triggerLocalRescan(context: Context, reason: String) {
            android.util.Log.i("MikuPrefsReceiver", "Local library rescan requested ($reason)")
            AlbumArtCache.clearMisses()
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, LibraryScanService::class.java)
                )
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, LibraryDaemonService::class.java).apply { action = LibraryDaemonService.ACTION_FORCE_SYNC }
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Sampling profiler from inside the process, because `am profile` writes nothing on
            // this device while the app is under load and nothing else works without root.
            //   adb shell am broadcast -a com.miku.player.action.PROFILE --ei seconds 8
            //   adb shell run-as com.miku.player cat files/profile.trace > profile.trace
            // Debug builds only. A profiler trigger in a release build is a foot-gun.
            // adb shell am broadcast -a com.miku.player.action.PRESET_SELFTEST -p com.miku.player
            // Needs the visualiser open (projectM lives on its GL thread). Logs "PRESET SELFTEST".
            ACTION_PRESET_SELFTEST -> Thread {
                ProjectMNative.selfTest(context.applicationContext)
            }.start()
            ACTION_PROFILE -> if (BuildConfig.DEBUG) {
                val secs = intent.getIntExtra("seconds", 8).coerceIn(1, 60)
                val f = java.io.File(context.filesDir, "profile.trace")
                runCatching {
                    android.os.Debug.startMethodTracingSampling(f.absolutePath, 64 * 1024 * 1024, 1000)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching { android.os.Debug.stopMethodTracing() }
                        android.util.Log.i("MikuProfile", "sampling trace written: ${f.absolutePath} (${f.length()} bytes)")
                    }, secs * 1000L)
                    android.util.Log.i("MikuProfile", "sampling for ${secs}s")
                }.onFailure { android.util.Log.w("MikuProfile", "start failed: $it") }
            }
            ACTION_FORCE_LIBRARY_SCAN, ACTION_RESCAN_LIBRARY -> triggerLocalRescan(context, intent.action ?: "broadcast")
            ACTION_SET_INGEST_ENABLED -> MikuIngestGate.setEnabled(context, intent.getBooleanExtra(EXTRA_ENABLED, false))
            ACTION_PLAY_RANDOM -> {
                val ok = InstantRandom.start(context)
                if (!ok) android.util.Log.w("MikuPrefsReceiver", "PLAY_RANDOM: library index empty")
            }
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
