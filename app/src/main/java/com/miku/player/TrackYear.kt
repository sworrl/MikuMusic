package com.miku.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Release year fallback for when MediaStore's own `YEAR` column is empty — verified live against
 * the on-device library: 3284 of 3455 tracks came back with a NULL year straight from MediaStore,
 * almost entirely FLAC files. MediaStore's tag scanner just doesn't reliably map the Vorbis
 * comment `DATE`/`YEAR` field into that column. Same architecture as TrackTech.kt (bit depth):
 * trust MediaStore first, parse the file directly as a fallback, cache the result so each file is
 * only ever inspected once.
 */
object TrackYear {
    // THREADING FIX (same defect TrackTech.kt already carried and fixed): `cache` is object-scope
    // Compose state (a SnapshotStateMap) that composition READS, and it was also being WRITTEN from
    // the IO probe coroutine — the exact "modified by composition as well as outside composition"
    // condition Recomposer.applyAndCheck crashes on — and persist() traversed it from that same IO
    // thread. `data` is now the authoritative plain map every thread uses; `cache` is only a mirror,
    // written from a fresh main-looper message purely so a composable recomposes when a year lands.
    private val data = ConcurrentHashMap<Long, Int>()
    private val cache = mutableStateMapOf<Long, Int>()   // 0 = probed, nothing found
    private val mainH = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioGate = Semaphore(2)
    private var cacheFile: File? = null
    @Volatile private var loaded = false

    // Debounced persistence: probing thousands of null-year FLACs used to rewrite the ENTIRE
    // track_year.json once per file (O(n²) writes — the same stall TrackTech was throttled to fix).
    // Instead we mark the cache dirty and coalesce into a single delayed flush.
    private val dirty = AtomicBoolean(false)
    @Volatile private var flushJob: Job? = null

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Store a result: the authoritative map immediately (any thread), the Compose mirror from a
     *  fresh main-looper message so composition neither races it nor is the one writing it. */
    private fun publish(id: Long, year: Int) {
        data[id] = year
        mainH.post { cache[id] = year }
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            cacheFile = File(ctx.filesDir, "track_year.json")
            val fromDisk = HashMap<Long, Int>()
            runCatching {
                val o = JSONObject(cacheFile!!.readText())
                o.keys().forEach { k -> fromDisk[k.toLong()] = o.getInt(k) }
            }
            data.putAll(fromDisk)
            loaded = true
            // One bulk mirror write on the main looper, never thousands of entries written from
            // whichever background thread happened to call in first.
            if (fromDisk.isNotEmpty()) mainH.post { cache.putAll(fromDisk) }
        }
    }

    /** Mark the cache changed and schedule a single coalesced flush ~1.5s out. Repeated calls
     *  while a flush is already pending are free — they just keep the dirty flag set. */
    private fun schedulePersist() {
        dirty.set(true)
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(1500)
            if (dirty.getAndSet(false)) persist()
        }
    }

    private fun persist() {
        val f = cacheFile ?: return
        runCatching {
            val o = JSONObject()
            // Iterate the plain map: persist() runs on the IO probe coroutine, and a
            // SnapshotStateMap must not be traversed from there.
            for ((k, v) in data) o.put(k.toString(), v)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }
    }

    /** Best release year for a track: MediaStore's own tag if it has one, else the direct-parse
     *  fallback. Returns null while a fallback probe is still in flight, 0 once resolved-but-
     *  nothing-found (so it's never re-probed). */
    fun yearFor(ctx: Context, track: Track): Int? {
        if (track.year > 0) return track.year
        ensureLoaded(ctx)
        // Touch the Compose mirror on the main thread ONLY to register the snapshot read — that is
        // what wakes the row when publish() later fills this id in. The value itself always comes
        // from the plain map, so background callers never touch Compose state at all.
        if (isMain()) cache[track.id]
        data[track.id]?.let { return it }
        if (track.path.isBlank() || !track.path.endsWith(".flac", ignoreCase = true)) {
            publish(track.id, 0)
            return 0
        }
        synchronized(inFlight) { if (!inFlight.add(track.id)) return null }
        scope.launch {
            ioGate.withPermit {
                val y = runCatching { flacYear(track.path) }.getOrNull() ?: 0
                publish(track.id, y)
                synchronized(inFlight) { inFlight.remove(track.id) }
                schedulePersist()
            }
        }
        return null
    }

    /**
     * Walks a FLAC file's metadata block chain looking for the VORBIS_COMMENT block, then its
     * DATE/YEAR/ORIGINALDATE field. Streams block-by-block (seeking past anything that isn't the
     * comment block, e.g. a multi-MB embedded cover) rather than reading the whole file.
     */
    private fun flacYear(path: String): Int? {
        RandomAccessFile(path, "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) < 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null
            while (true) {
                val header = ByteArray(4)
                if (raf.read(header) < 4) return null
                val isLast = (header[0].toInt() and 0x80) != 0
                val blockType = header[0].toInt() and 0x7F
                val blockLen = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (blockType == 4) {   // VORBIS_COMMENT
                    if (blockLen <= 0 || blockLen > 1_000_000) return null   // sanity cap
                    val data = ByteArray(blockLen)
                    raf.readFully(data)
                    var pos = 0
                    fun u32le(): Int {
                        if (pos + 4 > data.size) return -1
                        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
                            ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() and 0xFF) shl 24)
                        pos += 4
                        return v
                    }
                    val vendorLen = u32le(); if (vendorLen < 0) return null
                    pos += vendorLen
                    val commentCount = u32le(); if (commentCount < 0 || commentCount > 10_000) return null
                    repeat(commentCount) {
                        val len = u32le()
                        if (len < 0 || pos + len > data.size) return@repeat
                        val comment = String(data, pos, len, Charsets.UTF_8)
                        pos += len
                        val eq = comment.indexOf('=')
                        if (eq > 0) {
                            val key = comment.substring(0, eq).uppercase()
                            if (key == "DATE" || key == "YEAR" || key == "ORIGINALDATE" || key == "ORIGINALYEAR") {
                                val y = Regex("(\\d{4})").find(comment.substring(eq + 1))?.groupValues?.get(1)?.toIntOrNull()
                                if (y != null && y in 1900..2100) return y
                            }
                        }
                    }
                    return null
                } else {
                    raf.seek(raf.filePointer + blockLen)
                }
                if (isLast) return null
            }
            @Suppress("UNREACHABLE_CODE")
            return null
        }
    }
}
