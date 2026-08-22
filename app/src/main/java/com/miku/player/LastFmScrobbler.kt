package com.miku.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Drives Last.fm now-playing/scrobble calls off the shared [[PlayerHolder]] ExoPlayer instance.
 * Attached once, from PlayerHolder.ensure(), alongside the widget-update listener already there.
 *
 * Last.fm's own eligibility rule (https://www.last.fm/api/scrobbling — "a scrobble"): the track
 * must be longer than 30 seconds, and playback must reach at least 50% of its duration OR 4
 * minutes, whichever comes first. "Now playing" updates are unconditional (any track, any length)
 * and don't themselves count as a scrobble.
 *
 * Implemented as a lightweight self-rescheduling Handler tick rather than a per-tick coroutine
 * flow — checks are cheap (a couple of field reads) and this avoids holding a long-lived
 * coroutine/Flow collector alive for the whole app lifetime for what's fundamentally a polling
 * timer.
 */
object LastFmScrobbler {
    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var attached = false

    private var nowPlayingSentForKey: String? = null
    private var scrobbledForKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            checkAndMaybeScrobble()
            handler.postDelayed(this, 10_000L)
        }
    }

    /** Called from PlayerHolder.release() — without this, `attached` stayed true forever after a
     *  release+re-ensure cycle (rare, but possible in-process), and this object would silently
     *  never register a Player.Listener on the NEW ExoPlayer instance — instant now-playing pings
     *  would go quiet (scrobbles would still eventually happen via the 10s poll's own fallback
     *  check, just delayed, so this was a partial rather than total loss, but a real gap). */
    fun reset() {
        attached = false
        nowPlayingSentForKey = null
        scrobbledForKey = null
    }

    fun attach(context: Context, player: ExoPlayer) {
        if (attached) return
        attached = true
        appCtx = context.applicationContext
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // New track — reset both one-shot flags so the fresh track gets its own
                // now-playing ping and, once eligible, its own scrobble.
                sendNowPlayingIfNeeded(player)
            }
        })
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun trackKey(player: ExoPlayer): String? = player.currentMediaItem?.mediaId

    private fun sendNowPlayingIfNeeded(player: ExoPlayer) {
        val ctx = appCtx ?: return
        val key = trackKey(player) ?: return
        if (nowPlayingSentForKey == key) return
        val sk = LastFmPreferences.loadSessionKey(ctx) ?: return
        val m = player.mediaMetadata
        val title = m.title?.toString()?.ifBlank { null } ?: return
        val artist = m.artist?.toString()?.ifBlank { null } ?: return
        val album = m.albumTitle?.toString()
        nowPlayingSentForKey = key
        val durSec = (player.duration.takeIf { it > 0 } ?: 0L).let { (it / 1000).toInt() }
        LastFm.updateNowPlaying(sk, artist, title, album, durSec.takeIf { it > 0 })
    }

    private fun checkAndMaybeScrobble() {
        val ctx = appCtx ?: return
        val player = PlayerHolder.player ?: return
        if (!player.isPlaying) return
        val key = trackKey(player) ?: return
        sendNowPlayingIfNeeded(player) // catches metadata that wasn't ready at transition time
        if (scrobbledForKey == key) return

        val durMs = player.duration
        if (durMs <= 0 || durMs == androidx.media3.common.C.TIME_UNSET) return
        if (durMs < 30_000L) return // Last.fm minimum scrobble-eligible track length
        val threshold = minOf(durMs / 2, 4 * 60_000L)
        if (player.currentPosition < threshold) return

        val sk = LastFmPreferences.loadSessionKey(ctx) ?: return
        val m = player.mediaMetadata
        val title = m.title?.toString()?.ifBlank { null } ?: return
        val artist = m.artist?.toString()?.ifBlank { null } ?: return
        val album = m.albumTitle?.toString()

        scrobbledForKey = key
        val startedAtSec = (System.currentTimeMillis() - player.currentPosition) / 1000
        LastFm.scrobble(sk, artist, title, album, startedAtSec, (durMs / 1000).toInt())
    }
}
