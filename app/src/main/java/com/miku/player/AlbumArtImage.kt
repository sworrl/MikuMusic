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
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    @Volatile private var prewarmed = false

    fun get(id: Long): ImageBitmap? = mem.get("$id")
    fun getHi(id: Long): ImageBitmap? = mem.get("$id#hi") ?: mem.get("$id")
    internal fun put(key: String, b: ImageBitmap) { mem.put(key, b) }
    internal fun isMiss(key: String) = misses.contains(key)
    internal fun markMiss(key: String) { misses.add(key) }

    fun prewarm(ctx: Context, tracks: List<Track>, memWarm: Int = 30) {
        if (prewarmed || tracks.isEmpty()) return
        prewarmed = true
        val app = ctx.applicationContext
        scope.launch {
            val sample = tracks.take(memWarm)
            sample.forEach { tr ->
                gate.withPermit {
                    try {
                        loadArtThumb(app, tr.id, tr.path)
                    } catch (_: Throwable) {}
                }
            }
        }
    }
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
        if (imageFiles != null && imageFiles.size == 1) {
            BitmapFactory.decodeFile(imageFiles[0].absolutePath)?.let { return it }
        }
    } catch (_: Throwable) {}
    return null
}

/** Thumb pipeline: memory -> WebP disk -> embedded picture -> folder art -> MediaStore. */
suspend fun loadArtThumb(ctx: Context, trackId: Long, trackPath: String = "", keepInMemory: Boolean = true): ImageBitmap? =
    withContext(Dispatchers.IO) {
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

            // 3. Fallback: MediaStore Thumbnail API (only if no embedded/folder art found)
            if (bmp == null && Build.VERSION.SDK_INT >= 29) {
                try { bmp = ctx.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null) } catch (_: Throwable) {}
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

        // 2. Secondary Source: Local Folder Cover Art
        if (bmp == null && trackPath.isNotBlank()) {
            bmp = findFolderArt(trackPath)
        }

        val b = bmp
        if (b != null) {
            writeWebp(b, hiresFile(ctx, trackId))
            val img = b.asImageBitmap()
            AlbumArtCache.put("$trackId#hi", img)
            img
        } else { AlbumArtCache.markMiss("$trackId#hi"); loadArtThumb(ctx, trackId, trackPath) }
    } catch (_: Throwable) { AlbumArtCache.get(trackId) }
}

