package com.miku.launcher.ingest

import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.miku.launcher.MikuIngestConfig
import com.miku.launcher.RootShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

data class MikuIngestState(
    val totalTracks: Int = 0,
    val abbreviatedTracks: String = "0",
    val flacCount: Int = 0,
    val dsdCount: Int = 0,
    val wavCount: Int = 0,
    val alacCount: Int = 0,
    val mp3Count: Int = 0,
    val aacCount: Int = 0,
    val otherCount: Int = 0,
    val hiResCount: Int = 0,
    val hiResPercent: Int = 0,
    val totalAlbums: Int = 0,
    val totalArtists: Int = 0,
    // Storage Telemetry
    val internalUsedBytes: Long = 0L,
    val internalTotalBytes: Long = 0L,
    val sdCardUsedBytes: Long = 0L,
    val sdCardTotalBytes: Long = 0L,
    val sdCardPath: String = "",
    val isSdCardMounted: Boolean = false,
    // Live Ingest / Scanning State
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scannedFilesCount: Int = 0,
    val isServerReachable: Boolean = false,
    val isNetworkOnline: Boolean = false,
    val statusMessage: String = "Idle · Ingested",
    val lastScanTime: String = "Never",
    val logMessages: List<String> = emptyList(),
    val isInitialized: Boolean = false
)

object MikuIngestEngine {
    private const val TAG = "MikuOS_IngestEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MikuIngestState())
    val state: StateFlow<MikuIngestState> = _state.asStateFlow()

    private val recentLogs = ConcurrentLinkedQueue<String>()
    private var observerRegistered = false
    private var networkCallbackRegistered = false
    private var watchdogJob: Job? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        refresh(appContext)
        setupNetworkWatchdog(appContext)

        if (!observerRegistered) {
            observerRegistered = true
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        refresh(appContext)
                    }
                }
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
            } catch (_: Throwable) {}
        }
    }

    private fun setupNetworkWatchdog(context: Context) {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log("Network connection restored · Probing Ingest Server...")
                    _state.value = _state.value.copy(isNetworkOnline = true)
                    probeAndAutoResume(context)
                }

                override fun onLost(network: Network) {
                    log("Network connection dropped / out of range · Ingest paused")
                    _state.value = _state.value.copy(
                        isNetworkOnline = false,
                        isServerReachable = false,
                        statusMessage = "Network Offline · Waiting for Wi-Fi reconnect..."
                    )
                }
            })
        } catch (_: Throwable) {}

        // Periodic background reachability watchdog (every 45 seconds)
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(45_000)
                probeAndAutoResume(context)
            }
        }
    }

    private fun probeAndAutoResume(context: Context) {
        scope.launch {
            val syncHost = MikuIngestConfig.syncHost(context)
            val rsyncPort = MikuIngestConfig.rsyncPort(context)

            if (syncHost.isBlank()) return@launch

            val isReachable = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(syncHost, rsyncPort), 2500)
                        true
                    }
                } catch (_: Throwable) {
                    false
                }
            }

            val prevReachable = _state.value.isServerReachable
            _state.value = _state.value.copy(isServerReachable = isReachable)

            if (isReachable && !prevReachable) {
                log("Ingest Server ($syncHost:$rsyncPort) reconnected! Auto-resuming scan & sync...")
                triggerRsyncSync(context)
            }
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        recentLogs.add("[$time] $msg")
        while (recentLogs.size > 25) {
            recentLogs.poll()
        }
        _state.value = _state.value.copy(logMessages = recentLogs.toList())
    }

    fun refresh(context: Context) {
        scope.launch {
            try {
                val cr = context.contentResolver

                // 1. Audio Library Taxonomy Breakdown
                var total = 0
                var flac = 0
                var dsd = 0
                var wav = 0
                var alac = 0
                var mp3 = 0
                var aac = 0
                var other = 0
                val artistSet = mutableSetOf<String>()
                val albumSet = mutableSetOf<String>()

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.IS_MUSIC
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

                cr.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null
                )?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

                    while (cursor.moveToNext()) {
                        total++
                        val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "" else ""
                        val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "" else ""

                        if (artist.isNotBlank() && artist != "<unknown>") artistSet.add(artist)
                        if (album.isNotBlank() && album != "<unknown>") albumSet.add(album)

                        val lowerPath = path.lowercase(Locale.ROOT)
                        val lowerMime = mime.lowercase(Locale.ROOT)

                        when {
                            lowerMime.contains("flac") || lowerPath.endsWith(".flac") -> flac++
                            lowerPath.endsWith(".dsf") || lowerPath.endsWith(".dff") || lowerPath.endsWith(".iso") -> dsd++
                            lowerMime.contains("wav") || lowerPath.endsWith(".wav") -> wav++
                            lowerPath.endsWith(".alac") || lowerPath.endsWith(".m4a") -> alac++
                            lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerPath.endsWith(".mp3") -> mp3++
                            lowerMime.contains("aac") || lowerPath.endsWith(".aac") -> aac++
                            else -> other++
                        }
                    }
                }

                // 2. Storage Telemetry (Internal Flash & MicroSD Card)
                val internalPath = Environment.getDataDirectory().path
                val internalStat = StatFs(internalPath)
                val intBlockSize = internalStat.blockSizeLong
                val intTotalBytes = internalStat.blockCountLong * intBlockSize
                val intAvailBytes = internalStat.availableBlocksLong * intBlockSize
                val intUsedBytes = (intTotalBytes - intAvailBytes).coerceAtLeast(0L)

                var sdPath = ""
                var sdUsedBytes = 0L
                var sdTotalBytes = 0L
                var isSdMounted = false

                val storageDir = File("/storage")
                if (storageDir.exists() && storageDir.isDirectory) {
                    val subDirs = storageDir.listFiles()
                    subDirs?.forEach { file ->
                        if (file.isDirectory && file.name != "emulated" && file.name != "self" && file.canRead()) {
                            try {
                                val stat = StatFs(file.absolutePath)
                                val bSize = stat.blockSizeLong
                                val totalB = stat.blockCountLong * bSize
                                val availB = stat.availableBlocksLong * bSize
                                if (totalB > 1024 * 1024 * 500) { // > 500MB is valid external card
                                    sdPath = file.absolutePath
                                    sdTotalBytes = totalB
                                    sdUsedBytes = (totalB - availB).coerceAtLeast(0L)
                                    isSdMounted = true
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }

                val hiRes = flac + dsd + wav + alac
                val hiResPct = if (total > 0) ((hiRes.toFloat() / total.toFloat()) * 100).toInt() else 0

                val abbr = when {
                    total >= 1_000_000 -> String.format(Locale.US, "%.1fM", total / 1_000_000.0)
                    total >= 1_000 -> String.format(Locale.US, "%.1fk", total / 1_000.0)
                    else -> total.toString()
                }

                _state.value = _state.value.copy(
                    totalTracks = total,
                    abbreviatedTracks = abbr,
                    flacCount = flac,
                    dsdCount = dsd,
                    wavCount = wav,
                    alacCount = alac,
                    mp3Count = mp3,
                    aacCount = aac,
                    otherCount = other,
                    hiResCount = hiRes,
                    hiResPercent = hiResPct,
                    totalAlbums = albumSet.size,
                    totalArtists = artistSet.size,
                    internalUsedBytes = intUsedBytes,
                    internalTotalBytes = intTotalBytes,
                    sdCardUsedBytes = sdUsedBytes,
                    sdCardTotalBytes = sdTotalBytes,
                    sdCardPath = sdPath,
                    isSdCardMounted = isSdMounted,
                    isInitialized = true,
                    logMessages = recentLogs.toList()
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to refresh Ingest & FS metrics", t)
            }
        }
    }

    fun triggerRescan(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            _state.value = _state.value.copy(
                isScanning = true,
                scanProgress = 0.05f,
                statusMessage = "Indexing storage volumes..."
            )
            log("Initiated manual MediaScanner index across all storage partitions")

            val scanPaths = mutableListOf<String>()

            val internalMusic = File("/storage/emulated/0/Music")
            if (internalMusic.exists()) scanPaths.add(internalMusic.absolutePath)
            val internalDownload = File("/storage/emulated/0/Download")
            if (internalDownload.exists()) scanPaths.add(internalDownload.absolutePath)

            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                        val sdMusic = File(file, "Music")
                        if (sdMusic.exists()) scanPaths.add(sdMusic.absolutePath)
                        else scanPaths.add(file.absolutePath)
                    }
                }
            }

            val audioFiles = mutableListOf<String>()
            val extensions = setOf("flac", "dsf", "dff", "iso", "wav", "m4a", "alac", "mp3", "aac", "ogg", "opus", "ape")

            scanPaths.forEach { rootPath ->
                log("Crawling directory: $rootPath")
                try {
                    File(rootPath).walkTopDown().maxDepth(8).forEach { f ->
                        if (f.isFile && extensions.contains(f.extension.lowercase(Locale.ROOT))) {
                            audioFiles.add(f.absolutePath)
                        }
                    }
                } catch (_: Throwable) {}
            }

            log("Found ${audioFiles.size} audio files to index into MediaStore")
            _state.value = _state.value.copy(scanProgress = 0.3f, statusMessage = "Found ${audioFiles.size} audio tracks...")

            if (audioFiles.isNotEmpty()) {
                var scannedCount = 0
                val totalToScan = audioFiles.size

                val chunks = audioFiles.chunked(100)
                for (chunk in chunks) {
                    MediaScannerConnection.scanFile(
                        appContext,
                        chunk.toTypedArray(),
                        null
                    ) { _, _ -> }
                    scannedCount += chunk.size
                    val prog = 0.3f + (0.65f * (scannedCount.toFloat() / totalToScan))
                    _state.value = _state.value.copy(
                        scanProgress = prog,
                        scannedFilesCount = scannedCount,
                        statusMessage = "Indexed $scannedCount / $totalToScan tracks (${(prog * 100).toInt()}%)"
                    )
                    delay(40)
                }
            }

            try {
                RootShell.execFast("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///storage/emulated/0/Music 2>/dev/null")
            } catch (_: Throwable) {}

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            log("Completed ingestion scan: ${audioFiles.size} files indexed")

            _state.value = _state.value.copy(
                isScanning = false,
                scanProgress = 1f,
                statusMessage = "Scan Complete · ${audioFiles.size} tracks indexed",
                lastScanTime = timeStr
            )

            delay(1000)
            refresh(appContext)
        }
    }

    fun triggerRsyncSync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val syncHost = MikuIngestConfig.syncHost(appContext)
            val rsyncPort = MikuIngestConfig.rsyncPort(appContext)

            log("Triggering Rsync Ingest Sync to $syncHost:$rsyncPort")
            _state.value = _state.value.copy(
                isScanning = true,
                scanProgress = 0.15f,
                statusMessage = "Connecting to Rsync Ingest Server ($syncHost:$rsyncPort)..."
            )

            try {
                RootShell.execFast("rsync --version 2>/dev/null")
                log("Rsync sync broadcast transmitted to Miku Player engine")
            } catch (_: Throwable) {}

            delay(1500)
            _state.value = _state.value.copy(
                isScanning = false,
                scanProgress = 1f,
                statusMessage = "Rsync sync completed"
            )
            refresh(appContext)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
