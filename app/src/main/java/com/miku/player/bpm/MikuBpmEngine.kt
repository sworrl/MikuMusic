package com.miku.player.bpm

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * OS-Level Real-Time BPM Engine & Public System API for MikuOS.
 * Analyzes track tempo, ID3 TBPM metadata, and broadcasts system-wide beat events
 * to drive hardware Pulsar LED, homescreen rainbow ring, and visualizers.
 */
object MikuBpmEngine {
    private const val TAG = "MikuBpmEngine"
    const val ACTION_BPM_UPDATE = "com.miku.action.BPM_UPDATE"
    const val ACTION_BPM_PULSE = "com.miku.action.BPM_PULSE"
    const val EXTRA_BPM = "bpm"
    const val EXTRA_BEAT_INTERVAL_MS = "beat_interval_ms"
    const val EXTRA_IS_PLAYING = "is_playing"
    const val EXTRA_DOMINANT_COLOR = "dominant_color"

    private val bpmCache = ConcurrentHashMap<String, Float>()
    private var beatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile var currentBpm: Float = 120.0f
        private set
    @Volatile var beatIntervalMs: Long = 500L
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var dominantColor: Int = 0xFF39C5BB.toInt()
        private set

    @Volatile private var npTitle: String = ""
    @Volatile private var npArtist: String = ""

    fun onPlaybackChanged(
        context: Context,
        mediaItem: MediaItem?,
        playing: Boolean,
        albumArtColor: Int? = null
    ) {
        isPlaying = playing
        if (albumArtColor != null) {
            dominantColor = albumArtColor
        }
        npTitle = mediaItem?.mediaMetadata?.title?.toString() ?: ""
        npArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""

        if (mediaItem == null || !playing) {
            stopBeatPulse(context)
            publishState(context, currentBpm, false)
            return
        }

        scope.launch {
            val bpm = resolveBpm(context, mediaItem)
            currentBpm = bpm
            beatIntervalMs = (60_000f / bpm).toLong().coerceIn(250L, 2000L)
            
            publishState(context, bpm, true)
            startBeatPulse(context)
        }
    }

    private fun resolveBpm(context: Context, mediaItem: MediaItem): Float {
        val key = mediaItem.mediaId
        bpmCache[key]?.let { return it }
        loadPersistedBpm(context, key)?.let { bpmCache[key] = it; return it }

        // REAL tempo analysis — decode + onset autocorrelation (MikuBpmAnalyzer). The old path
        // read METADATA_KEY_CAPTURE_FRAMERATE (a VIDEO key — never tempo) and then keyword-
        // guessed, which is why everything sat on defaults. Title heuristics remain only as the
        // final fallback for undecodable files.
        var extractedBpm = 0f
        val path = resolvePath(context, mediaItem)
        if (path != null) {
            MikuBpmAnalyzer.analyze(path)?.let { extractedBpm = it }
        }

        if (extractedBpm == 0f) {
            val title = mediaItem.mediaMetadata.title?.toString() ?: ""
            val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
            extractedBpm = estimateTempo(title, artist, key)
        }

        val finalBpm = extractedBpm.coerceIn(70f, 185f)
        bpmCache[key] = finalBpm
        persistBpm(context, key, finalBpm)
        return finalBpm
    }

    /** File path for the item: direct file URI, or resolve a content:// MediaStore uri to DATA. */
    private fun resolvePath(context: Context, mediaItem: MediaItem): String? {
        val uri = mediaItem.localConfiguration?.uri ?: return null
        if (uri.scheme == null || uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null
                )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
            }
        }
        return null
    }

    // Analysis is expensive (a decode) — persist results so each track is analyzed once EVER,
    // not once per process. Plain prefs keyed by mediaId.
    private fun bpmPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("miku_bpm_cache", Context.MODE_PRIVATE)

    private fun loadPersistedBpm(context: Context, key: String): Float? {
        val v = bpmPrefs(context).getFloat("bpm_$key", 0f)
        return if (v in 70f..185f) v else null
    }

    private fun persistBpm(context: Context, key: String, bpm: Float) {
        runCatching { bpmPrefs(context).edit().putFloat("bpm_$key", bpm).apply() }
    }

    private fun estimateTempo(title: String, artist: String, seedKey: String): Float {
        val text = "$title $artist".lowercase()
        return when {
            text.contains("speed") || text.contains("fast") || text.contains("hardcore") || text.contains("dnb") -> 174f
            text.contains("dance") || text.contains("club") || text.contains("remix") || text.contains("trance") -> 132f
            text.contains("electro") || text.contains("vocaloid") || text.contains("miku") || text.contains("pop") -> 128f
            text.contains("rock") || text.contains("metal") || text.contains("punk") -> 145f
            text.contains("slow") || text.contains("ballad") || text.contains("lofi") || text.contains("chill") -> 85f
            text.contains("hiphop") || text.contains("trap") || text.contains("r&b") -> 95f
            else -> {
                val hash = kotlin.math.abs((title + artist + seedKey).hashCode())
                110f + (hash % 45)
            }
        }
    }

    private fun startBeatPulse(context: Context) {
        beatJob?.cancel()
        beatJob = scope.launch {
            while (isActive && isPlaying) {
                val intent = Intent(ACTION_BPM_PULSE).apply {
                    putExtra(EXTRA_BPM, currentBpm)
                    putExtra(EXTRA_BEAT_INTERVAL_MS, beatIntervalMs)
                    putExtra(EXTRA_DOMINANT_COLOR, dominantColor)
                }
                context.sendBroadcast(intent)
                delay(beatIntervalMs)
            }
        }
    }

    private fun stopBeatPulse(context: Context) {
        beatJob?.cancel()
        beatJob = null
    }

    private fun publishState(context: Context, bpm: Float, playing: Boolean) {
        try {
            val cr = context.contentResolver
            Settings.Global.putFloat(cr, "miku_live_bpm", bpm)
            Settings.Global.putInt(cr, "miku_beat_interval_ms", (60000f / bpm).toInt())
            Settings.Global.putInt(cr, "miku_is_playing", if (playing) 1 else 0)
            Settings.Global.putInt(cr, "miku_album_dominant_color", dominantColor)
            // Now-playing metadata for OS surfaces (launcher AOD face, BPM observatory).
            Settings.Global.putString(cr, "miku_now_playing_title", npTitle)
            Settings.Global.putString(cr, "miku_now_playing_artist", npArtist)
        } catch (_: Throwable) {}

        val intent = Intent(ACTION_BPM_UPDATE).apply {
            putExtra(EXTRA_BPM, bpm)
            putExtra(EXTRA_BEAT_INTERVAL_MS, (60000f / bpm).toLong())
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_DOMINANT_COLOR, dominantColor)
        }
        context.sendBroadcast(intent)
    }
}
