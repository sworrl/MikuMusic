package com.miku.launcher.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri

/**
 * Session-agnostic now-playing link. Wraps a platform [android.media.session.MediaController] so the
 * lockscreen / now-playing HUD render and control the CURRENTLY ACTIVE media app in Miku style —
 * Spotify, Tidal, YouTube, anything — not just com.miku.player. Method names mirror the media3
 * controller the lockscreen previously used, so its transport call-sites are unchanged.
 *
 * Needs android.permission.MEDIA_CONTENT_CONTROL (signature — held via the Falcon platform key).
 */
class MikuMediaLink(private val c: MediaController) {
    val packageName: String get() = c.packageName ?: ""
    val title: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    val artist: String? get() = c.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
    val album: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
    val mediaId: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
    val durationMs: Long get() = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    val positionMs: Long get() = c.playbackState?.position ?: 0L
    val isPlaying: Boolean get() = c.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Bitmap when the app embeds art, else a Uri, else null. Coil consumes any of them. */
    val artwork: Any? get() {
        val md = c.metadata ?: return null
        val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) return bmp
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        return uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    // media3-compatible names so the lockscreen transport call-sites need no edits.
    fun play() { runCatching { c.transportControls.play() } }
    fun pause() { runCatching { c.transportControls.pause() } }
    fun seekToNextMediaItem() { runCatching { c.transportControls.skipToNext() } }
    fun seekToPreviousMediaItem() { runCatching { c.transportControls.skipToPrevious() } }
    fun seekTo(ms: Long) { runCatching { c.transportControls.seekTo(ms) } }

    companion object {
        private const val MIKU = "com.miku.player"
        /** The active session to surface: prefer a PLAYING one (our player first), else any session. */
        fun active(ctx: Context): MikuMediaLink? = try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val sessions = msm?.getActiveSessions(null) ?: emptyList()
            val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val pick = playing.firstOrNull { it.packageName == MIKU }
                ?: playing.firstOrNull()
                ?: sessions.firstOrNull { it.packageName == MIKU }
                ?: sessions.firstOrNull()
            pick?.let { MikuMediaLink(it) }
        } catch (_: Throwable) { null }
    }
}
