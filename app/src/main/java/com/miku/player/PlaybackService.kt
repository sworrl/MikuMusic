package com.miku.player

import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession

/**
 * Hosts the MediaSession so the OS shows our Miku-branded media control on the lockscreen
 * and in the notification shade (album art + title/artist come from each MediaItem's
 * MediaMetadata). Media3's DefaultMediaNotificationProvider posts/removes the foreground
 * notification automatically as playback starts and stops.
 *
 * This is a Media3 [MediaLibraryService] (a superset of MediaSessionService) so the SAME session
 * also serves Android Auto's browsable media tree — see [MikuLibraryCallback]. Android Auto
 * discovers it via the extra manifest intent-filter actions (MediaLibraryService +
 * android.media.browse.MediaBrowserService) and the automotive_app_desc media descriptor.
 */
class PlaybackService : MediaLibraryService() {

    override fun onCreate() {
        super.onCreate()
        // Permanently ensure Android framework recognizes device provisioning & user setup complete
        // (AOSP MediaSessionService drops all global hardware media buttons if user_setup_complete == 0)
        try {
            val cr = applicationContext.contentResolver
            android.provider.Settings.Secure.putInt(cr, "user_setup_complete", 1)
            android.provider.Settings.Global.putInt(cr, "device_provisioned", 1)
        } catch (_: Throwable) {}

        // Register the session up front. We drive the ExoPlayer directly from the Activity (no
        // MediaController), so onGetSession never fires on its own — without addSession() the
        // notification manager would never observe the player and no lockscreen control posts.
        addSession(PlayerHolder.ensureSession(this))
        try {
            com.miku.player.api.MikuApiServer.start(this)
        } catch (e: Throwable) {
            android.util.Log.e("PlaybackService", "Failed to start MikuApiServer", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON || intent?.action == "hiby_media_button_action") {
            PlayerHolder.ensureSession(this)
            PlayerHolder.ensureControllerConnected(this)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        PlayerHolder.ensureSession(this)

    // Media3 demotes the service out of foreground when playback pauses. On this device that
    // let the process go cached → the media session stopped answering ANY transport command
    // (hardware play/next/prev buttons dead unless the app was focused with the screen on).
    // While a queue is loaded, stay foreground so the session keeps responding paused,
    // backgrounded, and screen-off. It's our own DAP — the buttons must always work.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepAlive = startInForegroundRequired || (PlayerHolder.player?.mediaItemCount ?: 0) > 0
        super.onUpdateNotification(session, keepAlive)
    }

    // Swiping the app away with nothing playing tears everything down.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerHolder.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            try {
                com.miku.player.api.MikuApiServer.stop(this)
            } catch (_: Throwable) {}
            PlayerHolder.release()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            com.miku.player.api.MikuApiServer.stop(this)
        } catch (_: Throwable) {}
        // Player lifecycle is owned by PlayerHolder; only release here on a real teardown.
        super.onDestroy()
    }
}
