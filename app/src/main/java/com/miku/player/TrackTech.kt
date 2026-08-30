package com.miku.player

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File

/**
 * Per-track technical info (bit depth + sample rate) that MediaStore doesn't provide. FLAC/WAV
 * headers are parsed directly (a few hundred bytes of IO); everything else falls back to
 * MediaExtractor's track format. Results live in a Compose state map (badges appear as they
 * resolve) and persist to a JSON cache so each file is only ever inspected once. Lossy formats
 * resolve to 0/0 = no badge.
 */
object TrackTech {
    data class Tech(val bits: Int, val sampleRateHz: Int)

    private val cache = mutableStateMapOf<Long, Tech>()
    private val inFlight = HashSet<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioGate = Semaphore(2)   // never hammer the SD card
    private var cacheFile: File? = null
    @Volatile private var loaded = false

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // New filename/schema (bits+sampleRate per track, was bits-only) — old cache is just
            // silently left behind and rebuilt in the background like a first run, same as always.
            cacheFile = File(ctx.filesDir, "track_tech2.json")
            runCatching {
                val o = JSONObject(cacheFile!!.readText())
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    cache[k.toLong()] = Tech(v.optInt("b", 0), v.optInt("sr", 0))
                }
            }
            loaded = true
        }
    }

    // Confirmed live (2026-08-17, 10k+ track library): persist() re-serializes and rewrites the
    // WHOLE cache file — every entry probed so far, not just the new one. Calling it after EVERY
    // single track probe (the old behavior) is an O(n²) I/O blowup at library scale: probing track
    // 9,000 of 10,000 doesn't just write one new entry, it rewrites a file already containing 8,999
    // others, every single time. This is very likely the actual cause of the reported 15-20s stall
    // on a fresh/rebuilt cache (a schema change to this file's format — see ensureLoaded's comment —
    // silently forces a full-library reprobe for every existing install once). Debounce instead:
    // batch real disk writes to at most once per ~2s or every 50 new entries, whichever comes
    // first, and expose flushPending() for a clean write on backgrounding (called from MainActivity
    // onPause) so a burst of probes right before the app closes doesn't just get dropped.
    @Volatile private var lastPersistAt = 0L
    @Volatile private var dirtySincePersist = 0

    private fun schedulePersist() {
        dirtySincePersist++
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 2000L && dirtySincePersist < 50) return
        lastPersistAt = now
        dirtySincePersist = 0
        persist()
    }

    fun flushPending() {
        if (dirtySincePersist > 0) { dirtySincePersist = 0; lastPersistAt = System.currentTimeMillis(); persist() }
    }

    private fun persist() {
        val f = cacheFile ?: return
        runCatching {
            val o = JSONObject()
            cache.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("b", v.bits).put("sr", v.sampleRateHz)) }
            f.writeText(o.toString())
        }
    }

    /** Bit depth for a track, or null while unknown / 0 for not-applicable (lossy). */
    fun bitsFor(ctx: Context, track: Track): Int? = techFor(ctx, track)?.bits
    /** Sample rate in Hz, or null while unknown / not yet resolved. */
    fun sampleRateFor(ctx: Context, track: Track): Int? = techFor(ctx, track)?.sampleRateHz?.takeIf { it > 0 }

    /** Cached (already-probed) sample rate by track id - no file I/O; null when never probed. */
    fun cachedSampleRateFor(ctx: Context, trackId: Long): Int? {
        ensureLoaded(ctx)
        return cache[trackId]?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun techFor(ctx: Context, track: Track): Tech? {
        ensureLoaded(ctx)
        cache[track.id]?.let { return it }
        val lossless = track.mime.contains("flac", true) || track.mime.contains("wav", true) ||
            track.mime.contains("x-wav", true) || track.mime.contains("aiff", true) ||
            track.mime.contains("alac", true) || track.mime.contains("mp4", true) ||
            track.path.endsWith(".flac", true) || track.path.endsWith(".wav", true) ||
            track.path.endsWith(".aif", true) || track.path.endsWith(".aiff", true) ||
            track.path.endsWith(".m4a", true)
        if (!lossless) { cache[track.id] = Tech(0, 0); return cache[track.id] }
        synchronized(inFlight) { if (!inFlight.add(track.id)) return null }
        scope.launch {
            ioGate.withPermit {
                val t = runCatching { probe(ctx, track) }.getOrNull() ?: Tech(0, 0)
                cache[track.id] = t
                synchronized(inFlight) { inFlight.remove(track.id) }
                schedulePersist()
            }
        }
        return null
    }

    private fun probe(ctx: Context, track: Track): Tech {
        if (track.path.isNotBlank()) {
            val f = File(track.path)
            if (f.canRead() && f.length() > 24) {
                runCatching {
                    f.inputStream().use { stream ->
                        val head = ByteArray(4096.coerceAtMost(f.length().toInt()))
                        val readBytes = stream.read(head)
                        if (readBytes > 22) {
                            flacTech(head, readBytes)?.let { return it }
                            wavTech(head, readBytes)?.let { return it }
                        }
                    }
                }
            }
        }
        // MediaExtractor fallback (m4a/ALAC and anything unreadable directly). Prefer the real
        // file path so a track that isn't in MediaStore (freshly synced, SD-card, or a stale id)
        // can still get its hi-res badge; only fall back to the content:// id if the path fails.
        val ex = MediaExtractor()
        return try {
            val opened = track.path.isNotBlank() && runCatching { ex.setDataSource(track.path) }.isSuccess
            if (!opened) {
                ex.setDataSource(ctx, Uri.parse("content://media/external/audio/media/${track.id}"), null)
            }
            var bits = 0
            var sr = 0
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                for (key in listOf("bits-per-sample", "bit-width")) {
                    runCatching { if (fmt.containsKey(key)) bits = fmt.getInteger(key) }
                }
                runCatching {
                    if (fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) sr = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
                if (bits > 0 && sr > 0) break
            }
            // PCM-encoding hint (2=16bit, 3=8bit, 4=float32, 21/22 = 24/32-bit packed)
            if (bits == 0) {
                runCatching {
                    val fmt = ex.getTrackFormat(0)
                    if (fmt.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING))
                        bits = when (fmt.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                            else -> 0
                        }
                }
            }
            Tech(bits, sr)
        } catch (_: Throwable) { Tech(0, 0) } finally { runCatching { ex.release() } }
    }

    /**
     * FLAC STREAMINFO parser: searches for "fLaC" marker (accounting for optional ID3v2 prepended header),
     * then extracts 20-bit sample rate and 5-bit bit depth.
     */
    private fun flacTech(h: ByteArray, len: Int = h.size): Tech? {
        var offset = 0
        // Check for ID3v2 tag prefix
        if (len >= 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
            val id3Size = ((h[6].toInt() and 0x7F) shl 21) or
                          ((h[7].toInt() and 0x7F) shl 14) or
                          ((h[8].toInt() and 0x7F) shl 7) or
                          (h[9].toInt() and 0x7F)
            offset = 10 + id3Size
        }
        // Find "fLaC" marker
        while (offset + 22 <= len) {
            if (h[offset] == 'f'.code.toByte() && h[offset + 1] == 'L'.code.toByte() &&
                h[offset + 2] == 'a'.code.toByte() && h[offset + 3] == 'C'.code.toByte()) {
                val streamInfoOffset = offset + 4 // after fLaC marker
                // STREAMINFO block header is 4 bytes, so STREAMINFO data starts at streamInfoOffset + 4
                // Offset in STREAMINFO data: 10 bytes in is sample rate (20 bits) and bits per sample (5 bits)
                val d = streamInfoOffset + 4
                if (d + 14 <= len) {
                    val b18 = h[d + 10].toInt() and 0xFF
                    val b19 = h[d + 11].toInt() and 0xFF
                    val b20 = h[d + 12].toInt() and 0xFF
                    val b21 = h[d + 13].toInt() and 0xFF
                    val sr = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
                    val bits = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1
                    if (sr > 0 && bits in 8..64) return Tech(bits, sr)
                }
            }
            offset++
        }
        return null
    }

    /**
     * WAV RIFF chunk parser: searches for 'fmt ' subchunk and extracts LE sample rate and bit depth.
     */
    private fun wavTech(h: ByteArray, len: Int = h.size): Tech? {
        if (len < 36 || h[0] != 'R'.code.toByte() || h[1] != 'I'.code.toByte() ||
            h[2] != 'F'.code.toByte() || h[3] != 'F'.code.toByte()) return null
        var offset = 12
        while (offset + 16 <= len) {
            if (h[offset] == 'f'.code.toByte() && h[offset + 1] == 'm'.code.toByte() &&
                h[offset + 2] == 't'.code.toByte() && h[offset + 3] == ' '.code.toByte()) {
                val chunkSize = (h[offset + 4].toInt() and 0xFF) or
                                ((h[offset + 5].toInt() and 0xFF) shl 8) or
                                ((h[offset + 6].toInt() and 0xFF) shl 16) or
                                ((h[offset + 7].toInt() and 0xFF) shl 24)
                val sr = (h[offset + 12].toInt() and 0xFF) or
                         ((h[offset + 13].toInt() and 0xFF) shl 8) or
                         ((h[offset + 14].toInt() and 0xFF) shl 16) or
                         ((h[offset + 15].toInt() and 0xFF) shl 24)
                val bits = if (offset + 23 <= len) {
                    (h[offset + 22].toInt() and 0xFF) or ((h[offset + 23].toInt() and 0xFF) shl 8)
                } else 16
                if (sr > 0 && bits in 8..64) return Tech(bits, sr)
                offset += 8 + chunkSize
            } else {
                val chunkSize = if (offset + 8 <= len) {
                    (h[offset + 4].toInt() and 0xFF) or
                    ((h[offset + 5].toInt() and 0xFF) shl 8) or
                    ((h[offset + 6].toInt() and 0xFF) shl 16) or
                    ((h[offset + 7].toInt() and 0xFF) shl 24)
                } else 4
                offset += 8 + chunkSize.coerceAtLeast(1)
            }
        }
        return null
    }

    /** Badge color per depth: 16 teal, 24 gold, 32 pink / rainbow. */
    fun color(bitsPerSample: Int): Color = when {
        bitsPerSample >= 32 -> MikuPink
        bitsPerSample >= 24 -> MikuGold
        bitsPerSample >= 16 -> MikuTeal
        else -> Muted
    }

    /** Sample-rate badge color — Miku teal-mint baseline, radiant gold for 96k, violet-pink for 192k, electric magenta for DXD. */
    fun rateColor(hz: Int): Color = when {
        hz >= 352_800 -> Color(0xFFFF3399) // DXD / extreme (352.8kHz+) — Miku Neon Magenta
        hz >= 176_400 -> Color(0xFFB388FF) // ultra hi-res (176.4/192kHz) — Holographic Violet / Rainbow
        hz >= 88_200 -> MikuGold           // hi-res (88.2/96kHz) — Radiant Gold
        hz > 0 -> MikuTealBright           // CD-standard (44.1/48kHz) — Miku Bright Teal / Mint
        else -> Muted
    }

    /** 1..3 — which bit-depth tier (1 = 16-bit Cyan Frost, 2 = 24-bit Radiant Gold, 3 = 32-bit Rainbow Heart). */
    fun bitTier(bitsPerSample: Int): Int = when {
        bitsPerSample >= 32 -> 3
        bitsPerSample >= 24 -> 2
        else -> 1
    }

    /** 1..4 — which sample-rate tier (1 = 44/48k Mint, 2 = 88/96k Gold Crystal, 3 = 176/192k Rainbow Heart, 4 = 352k+ Supernova). */
    fun rateTier(hz: Int): Int = when {
        hz >= 352_800 -> 4
        hz >= 176_400 -> 3
        hz >= 88_200 -> 2
        else -> 1
    }

    /** Kawaii micro-glyph icon prefix per bit-depth tier. */
    fun glyphForBitTier(tier: Int): String = when {
        tier >= 3 -> "♥"
        tier == 2 -> "✦"
        else -> "✧"
    }

    /** Kawaii micro-glyph icon prefix per sample-rate tier. */
    fun glyphForRateTier(tier: Int): String = when {
        tier >= 4 -> "♥✦"
        tier == 3 -> "♥"
        tier == 2 -> "✦"
        else -> "✧"
    }

    /** Tier-escalating brush for bit-depth (Prismatic Rainbow for Tier 3, Radiant Gold for Tier 2, Miku Teal for Tier 1). */
    fun bitBrush(bitsPerSample: Int): Brush = when {
        bitsPerSample >= 32 -> MikuBadgePalette.RainbowBrush
        bitsPerSample >= 24 -> MikuBadgePalette.HiResBrush
        else -> MikuBadgePalette.StandardBrush
    }

    /** Tier-escalating brush for sample-rate (Prismatic Rainbow for 192k+, Radiant Gold for 96k, Miku Mint for 44.1k). */
    fun rateBrush(hz: Int): Brush = when {
        hz >= 176_400 -> MikuBadgePalette.RainbowBrush
        hz >= 88_200 -> MikuBadgePalette.HiResBrush
        else -> MikuBadgePalette.StandardBrush
    }

    /** 1..3 — which format tier (1 = plain lossy MP3/OGG/OPUS Cyan Frost, 2 = higher-effort lossy
     *  AAC/M4A Radiant Gold, 3 = true lossless FLAC/WAV/ALAC/DSD Rainbow Heart) — same 3-tier
     *  escalation as bitTier/rateTier so the format chip finally reads with the same "this one's
     *  special" weight a 24-bit or 96kHz badge already carries, instead of always sitting flat at
     *  tier 1 regardless of whether the file is an MP3 or a lossless FLAC. */
    fun formatTier(mime: String): Int = when (mime.substringAfterLast('/').lowercase()) {
        "flac", "x-flac", "wav", "x-wav", "alac", "dsd", "dff", "dsf" -> 3
        "mp4", "m4a", "aac" -> 2
        else -> 1
    }

    /** Tier-escalating brush for format — mirrors bitBrush/rateBrush exactly. */
    fun formatBrush(mime: String): Brush = when (formatTier(mime)) {
        3 -> MikuBadgePalette.RainbowBrush
        2 -> MikuBadgePalette.HiResBrush
        else -> MikuBadgePalette.StandardBrush
    }

    /** Bit-depth badge shape — always the rounded diamond silhouette (4 points), unmistakably identifying bit depth at a glance. */
    fun bitShape(bitsPerSample: Int): Shape = BadgeShapes.diamond

    /** Sample-rate badge shape — always the rounded pentagon silhouette (5 points, point-up), unmistakably identifying sample rate at a glance. */
    fun rateShape(hz: Int): Shape = BadgeShapes.pentagon

    /** "44.1kHz" / "96kHz" / "192kHz" / "DSD64" — clean audiophile formatting. */
    fun formatSampleRate(hz: Int): String {
        val khz = hz / 1000.0
        return if (khz == kotlin.math.floor(khz)) "${khz.toInt()}kHz" else "%.1fkHz".format(khz)
    }

    /** Weighted 0..1 quality score for a whole artist/album, for the at-a-glance gauge on list rows. */
    fun weightedQuality(ctx: Context, tracks: List<Track>, sampleSize: Int = 8): Float? {
        if (tracks.isEmpty()) return null
        var sum = 0f
        var n = 0
        for (t in tracks.take(sampleSize)) {
            val bits = bitsFor(ctx, t) ?: continue
            if (bits <= 0) continue
            val sr = sampleRateFor(ctx, t)
            val bitScore = (bitTier(bits) - 1) / 2f
            val rateScore = if (sr != null && sr > 0) (rateTier(sr) - 1) / 3f else bitScore
            sum += bitScore * 0.5f + rateScore * 0.5f
            n++
        }
        return if (n == 0) null else (sum / n).coerceIn(0f, 1f)
    }

    /**
     * Determines whether an audio track is sourced from a Vinyl / LP rip based on
     * path, folder naming, title, and album metadata tags.
     */
    fun isVinyl(track: Track): Boolean = isVinyl(track.path, track.album, track.title)

    fun isVinyl(path: String, album: String = "", title: String = ""): Boolean {
        val target = "$path $album $title".lowercase()
        return target.contains("vinyl") ||
               target.contains("needledrop") ||
               target.contains("needle drop") ||
               target.contains("lp rip") ||
               target.contains("lp_rip") ||
               target.contains("lp-rip") ||
               target.contains("analog rip") ||
               target.contains("analog_rip") ||
               target.contains("[lp]") ||
               target.contains("(lp)") ||
               target.contains("12\" single") ||
               target.contains("12\" vinyl") ||
               target.contains("7\" single") ||
               target.contains("7\" vinyl") ||
               target.contains("turntable") ||
               target.contains("dmm rip")
    }

    data class QualityBreakdown(
        val maxBits: Int,
        val maxSampleRateHz: Int,
        val maxBitrateKbps: Int,
        val dominantFormat: String,
        val totalTracks: Int,
        val masterCount: Int,      // 176.4k+, 32-bit, DSD
        val studioHiResCount: Int, // 24-bit or 88.2k-96k
        val cdLosslessCount: Int,  // 16-bit 44.1k/48k FLAC/WAV/ALAC
        val lossyCount: Int,       // MP3, AAC, OGG
        val masterFraction: Float,
        val studioHiResFraction: Float,
        val cdLosslessFraction: Float,
        val lossyFraction: Float,
        val highestTier: Int,      // 1..4
        val summaryTag: String,
        val detailSummary: String,
        val specTag: String = "16-BIT · 44.1kHz FLAC",
        val badgeSymbol: String = "✧",
        val isVinylRip: Boolean = false
    )

    /**
     * Calculates the comprehensive relative quality distribution across an entire Album or Artist.
     * Computes exact breakdown fractions for Master (192k+/32-bit/DSD), Studio Hi-Res (24-bit/96k),
     * CD Lossless (16-bit/44.1k), and Lossy (MP3/AAC).
     */
    fun computeQualityBreakdown(ctx: Context, tracks: List<Track>): QualityBreakdown {
        if (tracks.isEmpty()) {
            return QualityBreakdown(
                maxBits = 0, maxSampleRateHz = 0, maxBitrateKbps = 0, dominantFormat = "FLAC",
                totalTracks = 0, masterCount = 0, studioHiResCount = 0, cdLosslessCount = 0,
                lossyCount = 0, masterFraction = 0f, studioHiResFraction = 0f, cdLosslessFraction = 0f,
                lossyFraction = 0f, highestTier = 1, summaryTag = "Standard", detailSummary = "No tracks",
                specTag = "No audio", badgeSymbol = "♪", isVinylRip = false
            )
        }

        var maxBits = 0
        var maxSr = 0
        var maxBr = 0
        var master = 0
        var studio = 0
        var cd = 0
        var lossy = 0
        var vinylCount = 0

        val formatCounts = mutableMapOf<String, Int>()

        for (t in tracks) {
            val bits = bitsFor(ctx, t) ?: 16
            val sr = sampleRateFor(ctx, t) ?: 44100
            val fmt = t.mime.substringAfterLast('/').uppercase().ifBlank { "AUDIO" }
            formatCounts[fmt] = (formatCounts[fmt] ?: 0) + 1

            if (bits > maxBits) maxBits = bits
            if (sr > maxSr) maxSr = sr
            if (t.bitrateKbps > maxBr) maxBr = t.bitrateKbps
            if (isVinyl(t)) vinylCount++

            val isLossless = formatTier(t.mime) >= 3
            when {
                sr >= 176400 || bits >= 32 || fmt in listOf("DSD", "DSF", "DFF") -> master++
                sr >= 88200 || bits >= 24 -> studio++
                isLossless -> cd++
                else -> lossy++
            }
        }

        val total = tracks.size.toFloat().coerceAtLeast(1f)
        val mFrac = master / total
        val sFrac = studio / total
        val cdFrac = cd / total
        val lFrac = lossy / total
        val isVinylRip = vinylCount > 0

        val dominantFmt = formatCounts.maxByOrNull { it.value }?.key ?: "FLAC"

        val highestTier = when {
            master > 0 -> 4
            studio > 0 -> 3
            cd > 0 -> 2
            else -> 1
        }

        val losslessPct = ((master + studio + cd) / total * 100).toInt()

        val summaryTag = when {
            isVinylRip && master > 0 -> "VINYL MASTER · ${formatSampleRate(maxSr)}"
            isVinylRip && studio > 0 -> "VINYL HI-RES · 24-BIT"
            isVinylRip -> "VINYL RIP · $dominantFmt"
            master > 0 && master == tracks.size -> "100% STUDIO MASTER"
            master > 0 -> "UP TO ${if (maxBits > 0) "$maxBits-BIT " else ""}${formatSampleRate(maxSr)}"
            studio > 0 && studio == tracks.size -> "100% HI-RES 24-BIT"
            studio > 0 -> "UP TO ${formatSampleRate(maxSr)} 24-BIT"
            cd == tracks.size -> "100% LOSSLESS $dominantFmt"
            losslessPct > 0 -> "$losslessPct% LOSSLESS"
            else -> "$dominantFmt ${maxBr}k"
        }

        val detailSummary = when {
            isVinylRip -> "$vinylCount of ${tracks.size} tracks Analog Vinyl Rip (${if (maxBits > 0) "$maxBits-bit/" else ""}${formatSampleRate(maxSr)})"
            master > 0 -> "$master of ${tracks.size} tracks Master Tier (${formatSampleRate(maxSr)}/24+ bit)"
            studio > 0 -> "$studio of ${tracks.size} tracks Studio Hi-Res (24-bit/96kHz)"
            cd > 0 -> "$cd of ${tracks.size} tracks 16-bit/44.1kHz Bit-Perfect"
            else -> "${tracks.size} tracks standard audio"
        }

        val baseSpecTag = when {
            master > 0 -> "${if (maxBits > 0) "$maxBits-BIT · " else ""}${formatSampleRate(maxSr)} $dominantFmt"
            studio > 0 -> "${if (maxBits > 0) "$maxBits-BIT · " else "24-BIT · "}${formatSampleRate(maxSr)} $dominantFmt"
            cd > 0 -> "16-BIT · ${formatSampleRate(maxSr)} $dominantFmt"
            else -> "$dominantFmt · ${maxBr}kbps"
        }
        val specTag = if (isVinylRip) "$baseSpecTag · ⊚ VINYL" else baseSpecTag

        val badgeSymbol = when {
            isVinylRip -> "⊚"
            highestTier == 4 -> "💎"
            highestTier == 3 -> "👑"
            highestTier == 2 -> "✧"
            else -> "♪"
        }

        return QualityBreakdown(
            maxBits = maxBits,
            maxSampleRateHz = maxSr,
            maxBitrateKbps = maxBr,
            dominantFormat = dominantFmt,
            totalTracks = tracks.size,
            masterCount = master,
            studioHiResCount = studio,
            cdLosslessCount = cd,
            lossyCount = lossy,
            masterFraction = mFrac,
            studioHiResFraction = sFrac,
            cdLosslessFraction = cdFrac,
            lossyFraction = lFrac,
            highestTier = highestTier,
            summaryTag = summaryTag,
            detailSummary = detailSummary,
            specTag = specTag,
            badgeSymbol = badgeSymbol,
            isVinylRip = isVinylRip
        )
    }
}

/**
 * Palette definitions for the Kawaii Miku audio quality badge ecosystem.
 */
object MikuBadgePalette {
    // Iridescent Rainbow for Pinnacle Tier (192kHz+, 32-bit, DSD, DXD)
    val RainbowStops = listOf(
        Color(0xFF00F5D4), // Miku Neon Cyan / Mint
        Color(0xFF00B4D8), // Electric Sky Blue
        Color(0xFF9D4EDD), // Kawaii Holographic Violet
        Color(0xFFFF3399), // Miku Electric Pink / Magenta
        Color(0xFFFFD166), // Luminous Gold
        Color(0xFF00F5D4)  // Loop back
    )
    val RainbowBrush = Brush.linearGradient(RainbowStops)

    // Radiant Gold & Cyan Sparkle for Studio Master Tier (96kHz, 88.2kHz, 24-bit). Was briefly
    // recolored green/yellow to match the Pulsar Light's supposed stock HAL auto-behavior — that
    // behavior turned out not to exist at all (see [[m500-pulsar-light-dead-end]]/PulsarLight.kt's
    // history), and now that root gives full RGB control of the light, the causality runs the
    // OTHER way: the badges are the source of truth for "fancy," and PulsarLight reads ITS colors
    // from this palette (MikuBadgePalette), not the other way around. Reverted to the original.
    val HiResGoldStops = listOf(
        Color(0xFFFFD166), // Radiant Gold
        Color(0xFFFFA726), // Amber Glow
        Color(0xFF7FE6DE), // Cyan Specular Glint
        Color(0xFFFFD166)
    )
    val HiResBrush = Brush.linearGradient(HiResGoldStops)

    // Miku Teal & Mint Glass for Standard Tier (44.1kHz, 48kHz, 16-bit)
    val StandardCyanStops = listOf(
        Color(0xFF39C5BB),
        Color(0xFF7FE6DE),
        Color(0xFF39C5BB)
    )
    val StandardBrush = Brush.linearGradient(StandardCyanStops)

    // Miku Twin-Tail Ribbon Gradient for Audio Format Tags (FLAC, WAV, ALAC)
    val FormatRibbonStops = listOf(
        Color(0xFF7FE6DE),
        Color(0xFFFF5FA2),
        Color(0xFF7FE6DE)
    )
    val FormatBrush = Brush.linearGradient(FormatRibbonStops)
}

/**
 * Bespoke Kawaii Geometric Silhouettes:
 *  - diamond: 4-point Diamond Gem for Bit Depth (16-bit, 24-bit, 32-bit)
 *  - pentagon: 5-point Pentagon Crest for Sample Rate (44.1k, 96k, 192k)
 *  - hexagon: 6-point Hexagon Ribbon for Audio Format (FLAC, WAV, DSD)
 */
object BadgeShapes {
    val pill: Shape = RoundedCornerShape(50)

    /**
     * Symmetrical rounded polygon generator with soft kawaii rounded corners.
     */
    fun roundedPolygon(sides: Int, cornerFraction: Float = 0.28f, rotationDeg: Float = -90f): Shape =
        GenericShape { size, _ ->
            val cx = size.width / 2f
            val cy = size.height / 2f
            // The polygon is built on the chip's HEIGHT and then stretched to its full WIDTH:
            // the two halves are pushed outward by `ext` and any vertex sitting on the vertical
            // centre line is split into a left+right pair (flat top/bottom edge). Previously the
            // polygon was inscribed in min(w,h) — a tiny square gem floating behind wide text,
            // which read as a badge drawn on top of another badge.
            val r = size.height / 2f * 0.94f
            val ext = ((size.width - size.height) / 2f).coerceAtLeast(0f)
            val corner = r * cornerFraction
            val angleStep = (2.0 * Math.PI) / sides
            val rot = Math.toRadians(rotationDeg.toDouble())
            val pts = ArrayList<Offset>(sides * 2)
            for (i in 0 until sides) {
                val a = rot + angleStep * i
                val px = (r * kotlin.math.cos(a)).toFloat()
                val py = (r * kotlin.math.sin(a)).toFloat()
                val onAxis = kotlin.math.abs(px) < r * 0.02f
                if (onAxis && ext > 0.5f) {
                    // Winding is clockwise (angle increasing, y-down): a top vertex is entered
                    // from the left, a bottom vertex from the right.
                    if (py < 0f) { pts += Offset(cx - ext, cy + py); pts += Offset(cx + ext, cy + py) }
                    else { pts += Offset(cx + ext, cy + py); pts += Offset(cx - ext, cy + py) }
                } else {
                    val shift = if (px < 0f) -ext else ext
                    pts += Offset(cx + shift + px, cy + py)
                }
            }
            for (i in pts.indices) {
                val prev = pts[(i - 1 + pts.size) % pts.size]
                val curr = pts[i]
                val next = pts[(i + 1) % pts.size]
                val towardPrev = prev - curr
                val towardNext = next - curr
                val d1 = towardPrev.getDistance().coerceAtLeast(0.001f)
                val d2 = towardNext.getDistance().coerceAtLeast(0.001f)
                val inCorner = corner.coerceAtMost(minOf(d1, d2) * 0.5f)
                val p1 = curr + towardPrev * (inCorner / d1)
                val p2 = curr + towardNext * (inCorner / d2)
                if (i == 0) moveTo(p1.x, p1.y) else lineTo(p1.x, p1.y)
                quadraticTo(curr.x, curr.y, p2.x, p2.y)
            }
            close()
        }

    /** Bit-depth shape: Rounded 4-point Diamond, point-up */
    val diamond: Shape = roundedPolygon(sides = 4, cornerFraction = 0.30f, rotationDeg = -90f)

    /** Sample-rate shape: Rounded 5-point Pentagon, point-up */
    val pentagon: Shape = roundedPolygon(sides = 5, cornerFraction = 0.26f, rotationDeg = -90f)

    /** File-format shape: Rounded 6-point Hexagon, flat-top */
    val hexagon: Shape = roundedPolygon(sides = 6, cornerFraction = 0.24f, rotationDeg = 0f)

    // Aliases
    val kawaiiCapsule: Shape = diamond
    val kawaiiFacetedGem: Shape = diamond
    val kawaiiHeartCrest: Shape = pentagon
    val kawaiiHexGem: Shape = hexagon
}
