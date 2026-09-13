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

    // FAKE-DATA FIX: these used to start at 120 BPM / 500 ms, and that invented tempo was written
    // straight into Settings.Global miku_live_bpm (and broadcast) on the first pause, before any
    // track had ever been analysed. 0 = "no tempo known"; OS surfaces render that as "—".
    @Volatile var currentBpm: Float = 0f
        private set
    @Volatile var beatIntervalMs: Long = 0L
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
            if (bpm <= 0f) {
                // No real tempo for this track: publish "unknown" and run no beat pulse at all,
                // rather than driving the LED / ring / visualizers off an invented tempo.
                beatIntervalMs = 0L
                stopBeatPulse(context)
                publishState(context, 0f, true)
                return@launch
            }
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

        // FAKE-DATA FIX: when the analyzer found nothing this used to call estimateTempo(), which
        // made a BPM up out of words in the title ("dance" -> 132, "rock" -> 145, anything else ->
        // 120), cached it, PERSISTED it, and published it to Settings.Global as this track's tempo.
        // No tempo found is now 0f — unknown — and 0 is never persisted, so a later real analysis
        // still gets its chance.
        if (extractedBpm <= 0f) return 0f

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
        // FAKE-DATA FIX: the fuzzy pass used to run with a blank artist, and `dictA.contains("")`
        // is always true — so ANY artist-less track whose title merely contained "melt" was given
        // supercell's 165/170 BPM as a measured tempo. Both sides must be real text now.
        if (a.isBlank() || t.isBlank()) return null
        for ((k, bpm) in PRESEEDED_BPM_DICTIONARY) {
            val parts = k.split("|")
            if (parts.size == 2) {
                val dictA = parts[0]
                val dictT = parts[1]
                if (dictA.isBlank() || dictT.isBlank()) continue
                if (t.contains(dictT) && (a.contains(dictA) || dictA.contains(a))) {
                    return bpm
                }
            }
        }
        return null
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
            // bpm <= 0 means "not known": publish 0 for both so a consumer renders "—" instead of
            // dividing by zero (this used to produce Int.MAX_VALUE as a beat interval).
            val intervalMs = if (bpm > 0f) (60000f / bpm).toInt() else 0
            Settings.Global.putFloat(cr, "miku_live_bpm", if (bpm > 0f) bpm else 0f)
            Settings.Global.putInt(cr, "miku_beat_interval_ms", intervalMs)
            Settings.Global.putInt(cr, "miku_is_playing", if (playing) 1 else 0)
            Settings.Global.putInt(cr, "miku_album_dominant_color", dominantColor)
            // Now-playing metadata for OS surfaces (launcher AOD face, BPM observatory).
            Settings.Global.putString(cr, "miku_now_playing_title", npTitle)
            Settings.Global.putString(cr, "miku_now_playing_artist", npArtist)
        } catch (_: Throwable) {}

        val intent = Intent(ACTION_BPM_UPDATE).apply {
            putExtra(EXTRA_BPM, if (bpm > 0f) bpm else 0f)
            putExtra(EXTRA_BEAT_INTERVAL_MS, if (bpm > 0f) (60000f / bpm).toLong() else 0L)
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_DOMINANT_COLOR, dominantColor)
        }
        context.sendBroadcast(intent)
    }
}
