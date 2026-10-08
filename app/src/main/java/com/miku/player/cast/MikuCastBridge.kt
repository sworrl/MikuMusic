package com.miku.player.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miku.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Joins the cast channel to the player: metadata out, transport commands in.
 *
 * Kept apart from [MikuCastServer] on purpose. The server knows about sockets and framing and
 * nothing about playback; this knows about the player and nothing about the wire. That split is
 * what lets the transport be tested without a TV and the TV be tested without touching playback.
 *
 * THREADING. ExoPlayer is main-thread-only, and commands arrive on a socket thread, so every
 * control hop is posted to the main looper. Calling the player straight from the reader would
 * throw on the first button press from the remote.
 */
object MikuCastBridge {

    private const val TAG = "MikuCastBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var attached = false
    @Volatile private var lastMetaKey = ""

    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        val app = ctx.applicationContext

        // Commands from the TV remote.
        scope.launch {
            MikuCastServer.commands.filterNotNull().collect { cmd ->
                runCatching { handle(cmd) }
                    .onFailure { Log.w(TAG, "command failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        // Metadata out. Polled on a slow tick rather than hooked to a player listener: the TV only
        // needs to know what is playing, a second of lag is invisible at ten feet, and a poll
        // cannot leak a listener if the player is torn down and rebuilt underneath us.
        scope.launch {
            while (true) {
                runCatching { pushMetaIfChanged() }
                kotlinx.coroutines.delay(1000)
            }
        }
        Log.i(TAG, "bridge attached")
    }

    private fun handle(cmd: JSONObject) {
        val what = cmd.optString("cmd")
        Log.i(TAG, "TV sent '$what'")
        main.post {
            val p = PlayerHolder.player ?: return@post
            runCatching {
                when (what) {
                    "playpause" -> if (p.isPlaying) p.pause() else p.play()
                    "play" -> p.play()
                    "pause" -> p.pause()
                    "next" -> p.seekToNextMediaItem()
                    "prev" -> p.seekToPreviousMediaItem()
                    "seek" -> p.seekTo(cmd.optLong("positionMs", 0L))
                    else -> Log.d(TAG, "ignoring unknown command '$what'")
                }
            }.onFailure { Log.w(TAG, "player rejected '$what': ${it.message}") }
        }
    }

    private fun pushMetaIfChanged() {
        if (!MikuCastServer.state.value.connected) return
        main.post {
            val p = PlayerHolder.player ?: return@post
            val md = runCatching { p.mediaMetadata }.getOrNull() ?: return@post
            val title = md.title?.toString().orEmpty()
            val artist = md.artist?.toString().orEmpty()
            val album = md.albumTitle?.toString().orEmpty()
            val dur = runCatching { p.duration }.getOrDefault(0L)
            val pos = runCatching { p.currentPosition }.getOrDefault(0L)
            val playing = runCatching { p.isPlaying }.getOrDefault(false)

            // Only the identity fields gate the send; position changes every tick and would
            // otherwise push a frame a second forever.
            val key = "$title|$artist|$album|$playing"
            if (key == lastMetaKey) return@post
            lastMetaKey = key

            MikuCastServer.sendMeta(
                JSONObject()
                    .put("title", title)
                    .put("artist", artist)
                    .put("album", album)
                    .put("durationMs", if (dur > 0) dur else 0L)
                    .put("positionMs", pos)
                    .put("playing", playing)
            )
        }
    }
}
