package com.miku.player.bpm

import com.miku.player.safeQuery
import com.miku.player.MikuPowerGovernor
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

    /** Last item handed to [onPlaybackChanged] — replayed when the power governor lets analysis run again. */
    @Volatile private var lastMediaItem: MediaItem? = null
    @Volatile private var governorListenerArmed = false

    /**
     * Once: when the governor leaves AUDIO_ONLY/IDLE (screen back on) while we're playing, redo
     * the deferred analysis + beat pulse. Without this the launcher's BPM game sat on "ALSA
     * Standby" for a track that started while the screen was off — the state was never
     * published and nothing ever retried.
     */
    private fun armGovernorListener(context: Context) {
        if (governorListenerArmed) return
        governorListenerArmed = true
        val app = context.applicationContext
        MikuPowerGovernor.addListener { _ ->
            if (MikuPowerGovernor.allowBackgroundWork && isPlaying && beatJob?.isActive != true) {
                lastMediaItem?.let { onPlaybackChanged(app, it, true) }
            }
        }
    }

    fun onPlaybackChanged(
        context: Context,
        mediaItem: MediaItem?,
        playing: Boolean,
        albumArtColor: Int? = null
    ) {
        armGovernorListener(context)
        isPlaying = playing
        lastMediaItem = mediaItem
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
        // Power governor: BPM *analysis* and the beat pulse are screen-facing work — skipped
        // while the screen is off (AUDIO_ONLY/IDLE) and replayed by [armGovernorListener] when
        // the profile returns. The play/pause STATE itself is always published (a few Settings
        // writes + one broadcast) so OS surfaces never show "standby" for a live stream.
        if (!MikuPowerGovernor.allowBackgroundWork) {
            stopBeatPulse(context)
            publishState(context, currentBpm, true)
            return
        }

        scope.launch {
            val bpm = resolveBpm(context, mediaItem)
            currentBpm = bpm
            beatIntervalMs = (60_000f / bpm).toLong().coerceIn(60L, 4000L)
            
            publishState(context, bpm, true)
            startBeatPulse(context)
        }
    }

    private fun resolveBpm(context: Context, mediaItem: MediaItem): Float {
        val key = mediaItem.mediaId
        bpmCache[key]?.let { return it }
        loadPersistedBpm(context, key)?.let { bpmCache[key] = it; return it }

        val title = mediaItem.mediaMetadata.title?.toString() ?: ""
        val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
        findPreseededBpm(artist, title)?.let { dictBpm ->
            bpmCache[key] = dictBpm
            persistBpm(context, key, dictBpm)
            return dictBpm
        }

        // REAL tempo analysis — decode + onset autocorrelation with octave disambiguation (MikuBpmAnalyzer).
        var extractedBpm = 0f
        val path = resolvePath(context, mediaItem)
        if (path != null) {
            MikuBpmAnalyzer.analyze(path)?.let { extractedBpm = it }
        }

        if (extractedBpm == 0f) {
            extractedBpm = estimateTempo(title, artist, key)
        }

        val finalBpm = extractedBpm.coerceIn(20f, 999f)
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
                context.contentResolver.safeQuery(
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
        return if (v in 40f..320f) v else null
    }

    private fun persistBpm(context: Context, key: String, bpm: Float) {
        runCatching { bpmPrefs(context).edit().putFloat("bpm_$key", bpm).apply() }
    }

    fun calibrateBpm(context: Context, newBpm: Float) {
        val sanitized = newBpm.coerceIn(20f, 999f)
        currentBpm = sanitized
        beatIntervalMs = (60_000f / sanitized).toLong().coerceIn(60L, 4000L)
        val key = npTitle + npArtist
        if (key.isNotBlank()) {
            bpmCache[key] = sanitized
            persistBpm(context, key, sanitized)
        }
        publishState(context, sanitized, isPlaying)
        if (isPlaying) {
            startBeatPulse(context)
        }
    }

    private val PRESEEDED_BPM_DICTIONARY = mapOf(
        "beck|cellphone's dead" to 108.0f,
        "beck|cellphones dead" to 108.0f,
        "beck|loser" to 85.0f,
        "beck|e-pro" to 96.0f,
        "supercell|world is mine" to 165.0f,
        "supercell feat. hatsune miku|world is mine" to 165.0f,
        "supercell|melt" to 170.0f,
        "supercell feat. hatsune miku|melt" to 170.0f,
        "kurousa-p|senbonzakura" to 154.0f,
        "whiteflame feat. hatsune miku|senbonzakura" to 154.0f,
        "kz|tell your world" to 150.0f,
        "livetune feat. hatsune miku|tell your world" to 150.0f,
        "lamaze-p|popipo" to 140.0f,
        "wowaka|rolling girl" to 195.0f,
        "wowaka|world's end dancehall" to 165.0f,
        "wowaka|two-faced lovers" to 210.0f,
        "wowaka|ura-omote lovers" to 210.0f,
        "wowaka|unknown mother goose" to 224.0f,
        "deco*27|ghost rule" to 210.0f,
        "deco*27|vampire" to 132.0f,
        "deco*27|hibana" to 200.0f,
        "deco*27|otome dissection" to 135.0f,
        "samfree|luka luka night fever" to 160.0f,
        "giga-p|bring it on" to 145.0f,
        "mitchie m|freely tomorrow" to 135.0f,
        "daft punk|around the world" to 121.0f,
        "daft punk|one more time" to 123.0f,
        "daft punk|get lucky" to 116.0f,
        "gorillaz|feel good inc" to 139.0f,
        "gorillaz|clint eastwood" to 84.0f
    )

    private fun findPreseededBpm(artist: String, title: String): Float? {
        val a = artist.trim().lowercase()
        val t = title.trim().lowercase()
        if (a.isBlank() && t.isBlank()) return null
        val directKey = "$a|$t"
        PRESEEDED_BPM_DICTIONARY[directKey]?.let { return it }
        for ((k, bpm) in PRESEEDED_BPM_DICTIONARY) {
            val parts = k.split("|")
            if (parts.size == 2) {
                val dictA = parts[0]
                val dictT = parts[1]
                if (t.contains(dictT) && (a.contains(dictA) || dictA.contains(a))) {
                    return bpm
                }
            }
        }
        return null
    }

    private fun estimateTempo(title: String, artist: String, seedKey: String): Float {
        val text = "$title $artist".lowercase()
        return when {
            text.contains("cellphone") || text.contains("cell phone") -> 108f
            text.contains("speed") || text.contains("fast") || text.contains("hardcore") || text.contains("dnb") -> 174f
            text.contains("dance") || text.contains("club") || text.contains("remix") || text.contains("trance") -> 132f
            text.contains("electro") || text.contains("vocaloid") || text.contains("miku") || text.contains("pop") -> 128f
            text.contains("rock") || text.contains("metal") || text.contains("punk") -> 145f
            text.contains("slow") || text.contains("ballad") || text.contains("lofi") || text.contains("chill") -> 85f
            text.contains("hiphop") || text.contains("trap") || text.contains("r&b") -> 95f
            else -> 120f
        }
    }

    private fun startBeatPulse(context: Context) {
        beatJob?.cancel()
        beatJob = scope.launch {
            while (isActive && isPlaying) {
                val intent = Intent(ACTION_BPM_PULSE).apply {
                    putExtra(EXTRA_BPM, currentBpm)
                    putExtra(EXTRA_BEAT_INTERVAL_MS, beatIntervalMs)
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
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
