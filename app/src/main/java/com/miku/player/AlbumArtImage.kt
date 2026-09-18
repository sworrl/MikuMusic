package com.miku.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private const val THUMB_PX = 320
private const val HIRES_PX = 800

/**
 * Two-tier album-art cache built for INSTANT display and 100% ACCURATE album art matching:
 *  - Memory LruCache holding decoded thumbs + hi-res
 *  - Lossless WebP disk cache stored in `artcache_v3` (purges old buggy MediaStore cache)
 *  - Primary source: Embedded ID3 / FLAC APIC picture frames directly inside the audio file
 *  - Secondary source: Local folder art (cover.jpg, folder.jpg) in the track directory
 *  - Last fallback: MediaStore thumbnail API
 */
object AlbumArtCache {
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    private val mem = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, v: ImageBitmap) = (v.width * v.height * 4) / 1024
    }
    // A "miss" used to be permanent for the whole process lifetime — one transient failure (file
    // busy mid-ingest, MediaStore row not yet backfilled, storage permission granted a second
    // later) meant that track showed the placeholder until the app was killed. Misses now expire
    // after MISS_TTL_MS and are wiped outright whenever a library scan lands (clearMisses()).
    private const val MISS_TTL_MS = 5 * 60_000L
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // PERF: this limiter existed but was only ever applied around [prewarm]'s own loop — the path
    // every on-screen AlbumArtImage actually takes (loadArtThumb) was completely UNBOUNDED. On a
    // cold start that meant the prewarm batch plus every art tile the first screen composes all
    // firing MediaMetadataRetriever + full-size bitmap decodes + WebP encodes at once on the
    // shared coroutine worker pool: heavy CPU and heavy short-lived allocation exactly while the
    // app is trying to draw. It is enforced inside loadArtThumb now (see `artGate` usage there),
    // so it actually bounds the bulk work instead of one caller. There is only ever one prewarm
    // coroutine, so it can hold at most one of the four permits — visible art is never starved.
    internal val gate = Semaphore(4)
    @Volatile private var prewarmed = false

    fun get(id: Long): ImageBitmap? = mem.get("$id")
    fun getHi(id: Long): ImageBitmap? = mem.get("$id#hi") ?: mem.get("$id")
    internal fun put(key: String, b: ImageBitmap) { mem.put(key, b) }
    internal fun isMiss(key: String): Boolean {
        val at = misses[key] ?: return false
        if (android.os.SystemClock.elapsedRealtime() - at > MISS_TTL_MS) { misses.remove(key); return false }
        return true
    }
    internal fun markMiss(key: String) { misses[key] = android.os.SystemClock.elapsedRealtime() }
    /** Forget every recorded miss — after a rescan, after storage access is granted, etc. */
    fun clearMisses() { misses.clear() }

    fun prewarm(ctx: Context, tracks: List<Track>, memWarm: Int = 30) {
        if (prewarmed || tracks.isEmpty()) return
        prewarmed = true
        val app = ctx.applicationContext
        scope.launch {
            // PERF: this speculative batch used to start the instant the library list was known —
            // i.e. in the middle of cold start, competing with the art the user can actually SEE
            // and with the library grouping passes. The sample is the first `memWarm` tracks by
            // title, which is almost never what the first screen shows, so nothing on screen
            // depends on it. Same work, same tracks, just held until the launch burst is over.
            // (loadArtThumb now takes the concurrency permit itself — see `gate` above.)
            kotlinx.coroutines.delay(PREWARM_DELAY_MS)
            val sample = tracks.take(memWarm)
            sample.forEach { tr ->
                try {
                    loadArtThumb(app, tr.id, tr.path)
                } catch (_: Throwable) {}
            }
        }
    }

    /** How long the speculative art prewarm waits so it lands after the cold-start burst. */
    private const val PREWARM_DELAY_MS = 5_000L
}

@Composable
fun AlbumArtImage(
    trackId: Long,
    modifier: Modifier = Modifier,
    trackPath: String = "",
    contentScale: ContentScale = ContentScale.Crop,
    fallbackIcon: @Composable () -> Unit = {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(MikuArt.audiophile),
            contentDescription = "Miku Art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
) {
    val cached = AlbumArtCache.get(trackId)
    if (cached != null) {
        Image(bitmap = cached, contentDescription = "Album art", modifier = modifier, contentScale = contentScale)
    } else {
        val ctx = LocalContext.current
        var art by remember(trackId) { mutableStateOf<ImageBitmap?>(null) }

        if (trackId > 0 && !AlbumArtCache.isMiss("$trackId")) {
            LaunchedEffect(trackId, trackPath) {
                art = loadArtThumb(ctx, trackId, trackPath)
            }
        }

        if (art != null) {
            Image(bitmap = art!!, contentDescription = "Album art", modifier = modifier, contentScale = contentScale)
        } else {
            Box(modifier = modifier.background(Color(0xFF0A2022)), contentAlignment = Alignment.Center) { fallbackIcon() }
        }
    }
}

private fun artDir(ctx: Context): File {
    val dir = File(ctx.filesDir, "artcache_v3")
    if (!dir.exists()) {
        dir.mkdirs()
        // Wipe old corrupted/mismatched MediaStore art cache folders
        runCatching { File(ctx.filesDir, "artcache").deleteRecursively() }
        runCatching { File(ctx.filesDir, "artcache_v2").deleteRecursively() }
    }
    return dir
}

private fun thumbFile(ctx: Context, id: Long) = File(artDir(ctx), "$id.webp")
private fun hiresFile(ctx: Context, id: Long) = File(artDir(ctx), "${id}_hi.webp")

private fun writeWebp(bmp: Bitmap, f: File) {
    try {
        f.outputStream().use {
            if (Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
            else @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, 95, it)
        }
    } catch (_: Throwable) { runCatching { f.delete() } }
}

private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var s = 1
    while (opts.outWidth / s > target || opts.outHeight / s > target) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
}

private fun findFolderArt(trackPath: String): Bitmap? {
    if (trackPath.isBlank()) return null
    try {
        val file = File(trackPath)
        val dir = file.parentFile ?: return null
        if (!dir.exists() || !dir.isDirectory) return null

        val candidates = listOf(
            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
            "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
            "front.jpg", "front.jpeg", "front.png", "front.webp",
            "album.jpg", "album.jpeg", "album.png", "album.webp"
        )
        for (name in candidates) {
            val imgFile = File(dir, name)
            if (imgFile.exists() && imgFile.isFile && imgFile.length() > 0) {
                BitmapFactory.decodeFile(imgFile.absolutePath)?.let { return it }
            }
        }
        val imageFiles = dir.listFiles { f ->
            f.isFile && (f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) ||
                         f.extension.equals("png", true) || f.extension.equals("webp", true))
        }
        if (imageFiles != null) {
            // "AlbumArt_{…}_Large.jpg", "cover.1.jpg", "Front Cover.png" — any obvious art name.
            imageFiles.firstOrNull { FOLDER_ART_NAME_RE.containsMatchIn(it.name) }
                ?.let { f -> BitmapFactory.decodeFile(f.absolutePath)?.let { return it } }
            if (imageFiles.size == 1) BitmapFactory.decodeFile(imageFiles[0].absolutePath)?.let { return it }
        }
    } catch (_: Throwable) {}
    return null
}

private val FOLDER_ART_NAME_RE = Regex("(?i)^(albumart|cover|folder|front|album)[^/]*\\.(jpe?g|png|webp)$")

/**
 * Folder art WITHOUT raw file access: scoped storage hides the SD card from java.io.File until
 * "All files access" is held, but MediaStore.Images has already indexed the cover.jpg sitting next
 * to the FLACs — so ask MediaStore for images in the track's directory and open them by URI.
 * (Confirmed live 2026-08-25: the app's manage-external-storage op was still "default", so every
 * album whose art lives only as a folder cover.jpg rendered the placeholder.)
 */
private fun findFolderArtViaMediaStore(ctx: Context, trackPath: String): Bitmap? {
    if (trackPath.isBlank()) return null
    val dir = trackPath.substringBeforeLast('/', "")
    if (dir.isBlank()) return null
    try {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATA)
        val sel = "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATA} NOT LIKE ?"
        val args = arrayOf("$dir/%", "$dir/%/%")
        ctx.contentResolver.safeQuery(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            var best: Long = -1L; var bestRank = Int.MAX_VALUE; var count = 0; var only: Long = -1L
            while (c.moveToNext()) {
                count++
                val id = c.getLong(iId); only = id
                val name = c.getString(iName) ?: continue
                val lower = name.lowercase()
                val rank = when {
                    lower.startsWith("cover.") -> 0
                    lower.startsWith("folder.") -> 1
                    lower.startsWith("front") -> 2
                    lower.startsWith("album") -> 3
                    FOLDER_ART_NAME_RE.containsMatchIn(name) -> 4
                    else -> 9
                }
                if (rank < bestRank) { bestRank = rank; best = id }
            }
            val pick = if (best >= 0 && bestRank < 9) best else if (count == 1) only else -1L
            if (pick >= 0) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pick)
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    return BitmapFactory.decodeStream(ins)
                }
            }
        }
    } catch (_: Throwable) {}
    return null
}

/** MediaProvider's own per-album art (content://media/external/audio/albumart/<albumId>) — built by
 *  the platform scanner from ANY track's embedded picture or the folder cover, with full file
 *  access we may not have. Last resort before giving up. */
private fun loadMediaStoreAlbumArt(ctx: Context, trackId: Long): Bitmap? {
    try {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        var albumId = -1L
        ctx.contentResolver.safeQuery(uri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) albumId = c.getLong(0)
        }
        if (albumId <= 0) return null
        val artUri = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), albumId)
        ctx.contentResolver.openInputStream(artUri)?.use { ins -> return BitmapFactory.decodeStream(ins) }
    } catch (_: Throwable) {}
    return null
}

/** Thumb pipeline: memory -> WebP disk -> embedded picture -> folder art -> MediaStore. */
suspend fun loadArtThumb(ctx: Context, trackId: Long, trackPath: String = "", keepInMemory: Boolean = true): ImageBitmap? =
    withContext(Dispatchers.IO) {
        AlbumArtCache.get(trackId)?.let { return@withContext it }
        if (AlbumArtCache.isMiss("$trackId")) return@withContext null
        // PERF: everything below is the expensive part (MediaMetadataRetriever on an SD-card FLAC,
        // full-size bitmap decode, rescale, lossless-WebP encode). It used to run with NO
        // concurrency limit at all, so a freshly composed screen plus the prewarm batch could have
        // dozens of these in flight at once. Bounded to AlbumArtCache.gate's four permits — the
        // limiter that already existed for prewarm, now applied where the work actually is. Not
        // applied to loadArtHiRes, which calls this function at the end and would self-deadlock.
        AlbumArtCache.gate.withPermit {
        // Re-check after waiting for a permit: another coroutine may have loaded the very same
        // track meanwhile (the same id is commonly requested by a list row and the mini player at
        // once), in which case this whole probe is redundant. Cheap, exact, no bookkeeping.
        AlbumArtCache.get(trackId)?.let { return@withContext it }
        if (AlbumArtCache.isMiss("$trackId")) return@withContext null
        try {
            thumbFile(ctx, trackId).takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath)?.let { b ->
                    val img = b.asImageBitmap()
                    if (keepInMemory) AlbumArtCache.put("$trackId", img)
                    return@withContext img
                }
            }

            var bmp: Bitmap? = null
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

            // 1. Primary Source: Embedded Picture directly inside the Audio File
            val mmr = MediaMetadataRetriever()
            try {
                if (trackPath.isNotBlank() && File(trackPath).exists()) {
                    mmr.setDataSource(trackPath)
                } else {
                    mmr.setDataSource(ctx, uri)
                }
                mmr.embeddedPicture?.let { bmp = decodeSampled(it, THUMB_PX) }
            } catch (_: Throwable) {
            } finally { try { mmr.release() } catch (_: Throwable) {} }

            // 2. Secondary Source: Local Folder Cover Art
            if (bmp == null && trackPath.isNotBlank()) {
                bmp = findFolderArt(trackPath)?.let { full ->
                    val s = maxOf(1, maxOf(full.width, full.height) / THUMB_PX)
                    if (s > 1) Bitmap.createScaledBitmap(full, full.width / s, full.height / s, true) else full
                }
            }

            // 2b. Folder art through MediaStore.Images (works without raw SD-card file access)
            if (bmp == null && trackPath.isNotBlank()) {
                bmp = findFolderArtViaMediaStore(ctx, trackPath)?.let { full ->
                    val s = maxOf(1, maxOf(full.width, full.height) / THUMB_PX)
                    if (s > 1) Bitmap.createScaledBitmap(full, full.width / s, full.height / s, true) else full
                }
            }

            // 3. Fallback: MediaStore Thumbnail API (only if no embedded/folder art found)
            if (bmp == null && Build.VERSION.SDK_INT >= 29) {
                try { bmp = ctx.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null) } catch (_: Throwable) {}
            }
            // 4. Platform album art for the whole album (another track's embedded picture may carry it)
            if (bmp == null) {
                bmp = loadMediaStoreAlbumArt(ctx, trackId)?.let { full ->
                    val s = maxOf(1, maxOf(full.width, full.height) / THUMB_PX)
                    if (s > 1) Bitmap.createScaledBitmap(full, full.width / s, full.height / s, true) else full
                }
            }

            val b = bmp
            if (b != null) {
                writeWebp(b, thumbFile(ctx, trackId))
                val img = b.asImageBitmap()
                if (keepInMemory) AlbumArtCache.put("$trackId", img)
                img
            } else { AlbumArtCache.markMiss("$trackId"); null }
        } catch (_: Throwable) { null }
        }
    }

/** Hi-res pipeline for full Now Playing stage — same 100% accurate embedded & folder art priority. */
suspend fun loadArtHiRes(ctx: Context, trackId: Long, trackPath: String = ""): ImageBitmap? = withContext(Dispatchers.IO) {
    AlbumArtCache.getHi(trackId)?.let { if (it.width >= THUMB_PX + 1) return@withContext it }
    if (AlbumArtCache.isMiss("$trackId#hi")) return@withContext AlbumArtCache.get(trackId)
    try {
        hiresFile(ctx, trackId).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.absolutePath)?.let { b ->
                val img = b.asImageBitmap(); AlbumArtCache.put("$trackId#hi", img); return@withContext img
            }
        }

        var bmp: Bitmap? = null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

        // 1. Primary Source: Embedded Picture in Audio File
        val mmr = MediaMetadataRetriever()
        try {
            if (trackPath.isNotBlank() && File(trackPath).exists()) {
                mmr.setDataSource(trackPath)
            } else {
                mmr.setDataSource(ctx, uri)
            }
            mmr.embeddedPicture?.let { bmp = decodeSampled(it, HIRES_PX) }
        } catch (_: Throwable) {
        } finally { try { mmr.release() } catch (_: Throwable) {} }

        // 2. Secondary Source: Local Folder Cover Art (raw file, then via MediaStore.Images)
        if (bmp == null && trackPath.isNotBlank()) {
            bmp = findFolderArt(trackPath) ?: findFolderArtViaMediaStore(ctx, trackPath)
        }
        // 3. Platform album art (any track of the album)
        if (bmp == null) bmp = loadMediaStoreAlbumArt(ctx, trackId)

        val b = bmp
        if (b != null) {
            writeWebp(b, hiresFile(ctx, trackId))
            val img = b.asImageBitmap()
            AlbumArtCache.put("$trackId#hi", img)
            img
        } else { AlbumArtCache.markMiss("$trackId#hi"); loadArtThumb(ctx, trackId, trackPath) }
    } catch (_: Throwable) { AlbumArtCache.get(trackId) }
}

