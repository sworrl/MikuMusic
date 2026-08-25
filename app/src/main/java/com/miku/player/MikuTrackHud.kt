package com.miku.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Publishes "the track changed" to MikuOS SystemUI, which draws the over-all-apps now-playing
 * HUD (dismissable, user-switchable). The app only PUBLISHES — one explicit broadcast per real
 * change, throttled to ≥ 400 ms so seek/skip spam can't flood the shade.
 *
 * Broadcast: `com.miku.player.action.TRACK_CHANGED` → package `com.miku.systemui`
 * Extras: title, artist, album (String) · durationMs, positionMs (Long) · artUri (String —
 * FileProvider content URI of a 256px JPEG, read-grant attached) · artAlbumUri (String —
 * MediaStore albumart URI fallback) · artPath (String — raw cache file path, for privileged
 * readers) · quality ("FLAC 24/96") · liked, isPlaying (Boolean) · trackId (Long) · reason (String)
 *
 * Gate: Settings.Global `miku_track_hud_enabled` (default 1). 0 = never send.
 */
object MikuTrackHud {
    const val ACTION = "com.miku.player.action.TRACK_CHANGED"
    const val TARGET_PACKAGE = "com.miku.systemui"
    const val KEY_ENABLED = "miku_track_hud_enabled"
    private const val MIN_INTERVAL_MS = 400L
    private const val ART_PX = 256
    private const val AUTHORITY = "com.miku.player.hudart"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastSentMs = 0L
    @Volatile private var lastSignature = ""
    private var trailing: Runnable? = null

    fun isEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_ENABLED, 1) == 1 }.getOrDefault(true)

    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        val v = if (on) 1 else 0
        val ok = runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_ENABLED, v) }.getOrDefault(false)
        if (!ok) RootShell.execFast("settings put global $KEY_ENABLED $v")
        return ok
    }

    /** Call on every track transition / play-state change. Cheap when nothing changed. */
    fun publish(ctx: Context, player: ExoPlayer, reason: String) {
        val app = ctx.applicationContext
        if (!isEnabled(app)) return
        val item = player.currentMediaItem ?: return
        val trackId = item.mediaId.toLongOrNull() ?: return
        val isPlaying = player.isPlaying
        val signature = "$trackId:$isPlaying"
        val now = SystemClock.elapsedRealtime()
        val since = now - lastSentMs
        if (signature == lastSignature && since < 2_000L) return   // same state, nothing new to say
        if (since < MIN_INTERVAL_MS) {
            // Throttle: coalesce into ONE trailing send so the final state is never lost.
            trailing?.let { main.removeCallbacks(it) }
            val r = Runnable { trailing = null; publish(app, player, "$reason+trailing") }
            trailing = r
            main.postDelayed(r, MIN_INTERVAL_MS - since)
            return
        }
        lastSentMs = now
        lastSignature = signature
        val meta = item.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString().orEmpty()
        val durationMs = player.duration.takeIf { it > 0 } ?: 0L
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val albumArtUri = meta.artworkUri?.toString().orEmpty()
        scope.launch {
            val track = FastLibraryStore.loadSync(app)?.firstOrNull { it.id == trackId }
            val quality = track?.let { qualityLabel(app, it) }.orEmpty()
            val liked = runCatching { LikeStore.init(app); LikeStore.isLiked(trackId) }.getOrDefault(false)
            val (artUri, artPath) = artForHud(app, trackId, track?.path.orEmpty())
            val intent = Intent(ACTION).apply {
                setPackage(TARGET_PACKAGE)
                putExtra("trackId", trackId)
                putExtra("title", title)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("durationMs", durationMs)
                putExtra("positionMs", positionMs)
                putExtra("artUri", artUri?.toString().orEmpty())
                putExtra("artAlbumUri", albumArtUri)
                putExtra("artPath", artPath.orEmpty())
                putExtra("quality", quality)
                putExtra("liked", liked)
                putExtra("isPlaying", isPlaying)
                putExtra("reason", reason)
                putExtra("sentAtMs", System.currentTimeMillis())
                if (artUri != null) {
                    clipData = ClipData.newRawUri("hudart", artUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            runCatching { app.sendBroadcast(intent) }
                .onFailure { android.util.Log.w("MikuTrackHud", "broadcast failed", it) }
        }
    }

    private fun qualityLabel(ctx: Context, t: Track): String {
        val fmt = t.mime.substringAfterLast('/').uppercase().let { if (it == "X-FLAC") "FLAC" else it }.ifBlank { "" }
        val bits = runCatching { TrackTech.bitsFor(ctx, t) }.getOrNull()
        val sr = runCatching { TrackTech.sampleRateFor(ctx, t) }.getOrNull()
        val tech = when {
            bits != null && bits > 0 && sr != null && sr > 0 -> "$bits/${TrackTech.formatSampleRate(sr)}"
            sr != null && sr > 0 -> TrackTech.formatSampleRate(sr)
            bits != null && bits > 0 -> "$bits-bit"
            else -> ""
        }
        return listOf(fmt, tech).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 256px JPEG in cacheDir/hud, exposed through the app's FileProvider with a read grant. */
    private fun artForHud(ctx: Context, trackId: Long, path: String): Pair<Uri?, String?> {
        return try {
            val dir = File(ctx.cacheDir, "hud").apply { mkdirs() }
            val f = File(dir, "art_$trackId.jpg")
            if (!f.exists() || f.length() == 0L) {
                val bmp = kotlinx.coroutines.runBlocking { loadArtThumb(ctx, trackId, path, keepInMemory = false) }
                    ?: return Pair(null, null)
                val ab = bmp.asAndroidBitmap()
                val s = maxOf(1, maxOf(ab.width, ab.height) / ART_PX)
                val small = if (s > 1) android.graphics.Bitmap.createScaledBitmap(ab, ab.width / s, ab.height / s, true) else ab
                f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }
                // keep the cache small — drop anything older than the newest 40
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { runCatching { it.delete() } }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, AUTHORITY, f)
            runCatching { ctx.grantUriPermission(TARGET_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Pair(uri, f.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.w("MikuTrackHud", "art export failed", t)
            Pair(null, null)
        }
    }
}
