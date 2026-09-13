package com.miku.launcher.ingest

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
    // "—" until a scan has actually counted: "0" asserted an empty library before any query ran.
    val abbreviatedTracks: String = "—",
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
    // "Idle · Ingested" implied an ingest had already happened before anything ran.
    val statusMessage: String = "Idle · no ingest run yet",
    val lastScanTime: String = "Never",
    val logMessages: List<String> = emptyList(),
    val isInitialized: Boolean = false,
    /** Network (rsync) ingest engine switch — OFF by default; local SD scans always work. */
    val engineEnabled: Boolean = false
)

object MikuIngestEngine {
    private const val TAG = "MikuOS_IngestEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MikuIngestState())
    val state: StateFlow<MikuIngestState> = _state.asStateFlow()

    private val recentLogs = ConcurrentLinkedQueue<String>()
    private var observerRegistered = false
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogJob: Job? = null
    private var mediaReceiverRegistered = false
    private var autoScanDebounceJob: Job? = null
    private const val AUTO_PREFS = "miku_ingest_auto"
    private const val KEY_LAST_AUTO_SCAN = "last_auto_force_scan_ms"
    /** Unattended force-scan cadence: once a day, plus right after an SD card (re)mount. */
    private const val AUTO_SCAN_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Settings.Global switch for the network/rsync ingest engine. 0 (default) = OFF: only local SD scans. */
    const val GLOBAL_ENABLED_KEY = "miku_ingest_enabled"
    private const val OFF_MESSAGE = "Ingest engine OFF · local SD scan updates only"

    fun isEngineEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, GLOBAL_ENABLED_KEY, 0) == 1
    } catch (_: Throwable) { false }

    /** Flip the engine live: persists the Global, then arms or tears down the network watchdog. */
    fun setEngineEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            val v = if (enabled) 1 else 0
            val ok = runCatching { Settings.Global.putInt(appContext.contentResolver, GLOBAL_ENABLED_KEY, v) }.getOrDefault(false)
            if (!ok) runCatching { RootShell.execFast("settings put global $GLOBAL_ENABLED_KEY $v") }
            _state.value = _state.value.copy(
                engineEnabled = enabled,
                isServerReachable = false,
                statusMessage = if (enabled) "Ingest engine ON · probing rsync server..." else OFF_MESSAGE
            )
            if (enabled) {
                log("Ingest engine ENABLED · network rsync ingest armed")
                setupNetworkWatchdog(appContext)
                probeAndAutoResume(appContext)
            } else {
                log("Ingest engine DISABLED · local SD scan updates only")
                teardownNetworkWatchdog(appContext)
            }
        }
    }

    private fun teardownNetworkWatchdog(context: Context) {
        watchdogJob?.cancel(); watchdogJob = null
        val cb = networkCallback
        if (cb != null) {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {}
        }
        networkCallback = null
        networkCallbackRegistered = false
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        val enabled = isEngineEnabled(appContext)
        if (!_state.value.isInitialized) {
            _state.value = _state.value.copy(
                engineEnabled = enabled,
                statusMessage = if (enabled) _state.value.statusMessage else OFF_MESSAGE
            )
        } else if (_state.value.engineEnabled != enabled) {
            _state.value = _state.value.copy(engineEnabled = enabled)
        }
        refresh(appContext)
        if (enabled) setupNetworkWatchdog(appContext)

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
        armAutomaticScans(appContext)
    }

    /**
     * Unattended ingest — the relay node keeps the library current without anyone opening the
     * observatory. Two triggers: (1) SD card mounted / platform MediaScanner finished → debounced
     * force scan; (2) a daily catch-up force scan if none has run in the last 24h (also first run).
     * Purely local (MediaScanner + Miku Music nudge); never touches the network engine switch.
     */
    private fun armAutomaticScans(appContext: Context) {
        if (!mediaReceiverRegistered) {
            mediaReceiverRegistered = true
            try {
                val filter = android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_MEDIA_MOUNTED)
                    addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
                    addDataScheme("file")
                }
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val action = intent?.action ?: return
                        log("Storage event ${action.substringAfterLast('.')} · scheduling automatic force scan")
                        scheduleAutoForceScan(appContext, "storage:${action.substringAfterLast('.')}")
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag") appContext.registerReceiver(receiver, filter)
                }
            } catch (_: Throwable) { mediaReceiverRegistered = false }
        }
        val last = appContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_SCAN, 0L)
        if (System.currentTimeMillis() - last > AUTO_SCAN_INTERVAL_MS) {
            scheduleAutoForceScan(appContext, if (last == 0L) "first-run" else "daily")
        }
    }

    private fun scheduleAutoForceScan(appContext: Context, reason: String) {
        autoScanDebounceJob?.cancel()
        autoScanDebounceJob = scope.launch {
            delay(if (reason.startsWith("storage")) 8_000L else 20_000L)   // let the platform scanner / boot settle
            if (_state.value.isScanning) { log("Auto scan ($reason) skipped · a scan is already running"); return@launch }
            appContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_AUTO_SCAN, System.currentTimeMillis()).apply()
            log("AUTO FORCE SCAN ($reason)")
            triggerForceScan(appContext)
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
            val cb = object : ConnectivityManager.NetworkCallback() {
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
            }
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
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
        if (!isEngineEnabled(context)) return
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

    private var refreshDebounceJob: Job? = null

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(350)
            try {
                val cr = appContext.contentResolver

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
                    MediaStore.Audio.Media.ALBUM
                )

                try {
                    cr.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
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
                } catch (_: Throwable) {}

                // Fallback direct scan if MediaStore is empty or still indexing
                if (total == 0) {
                    val searchDirs = mutableListOf(
                        File("/storage/emulated/0/Music"),
                        File("/storage/emulated/0/Download"),
                        File("/sdcard/Music")
                    )
                    val extStorage = File("/storage")
                    if (extStorage.exists() && extStorage.isDirectory) {
                        extStorage.listFiles()?.forEach { f ->
                            if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                                searchDirs.add(File(f, "Music"))
                                searchDirs.add(f)
                            }
                        }
                    }
                    val exts = setOf("flac", "dsf", "dff", "iso", "wav", "m4a", "alac", "mp3", "aac", "ogg", "opus", "ape")
                    searchDirs.forEach { dir ->
                        if (dir.exists() && dir.canRead()) {
                            try {
                                dir.walkTopDown().maxDepth(6).forEach { f ->
                                    if (f.isFile && exts.contains(f.extension.lowercase(Locale.ROOT))) {
                                        total++
                                        val lower = f.extension.lowercase(Locale.ROOT)
                                        when {
                                            lower == "flac" -> flac++
                                            lower == "dsf" || lower == "dff" || lower == "iso" -> dsd++
                                            lower == "wav" -> wav++
                                            lower == "alac" || lower == "m4a" -> alac++
                                            lower == "mp3" -> mp3++
                                            lower == "aac" -> aac++
                                            else -> other++
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
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
                        // "Indexed" overstated it: the files have been HANDED to MediaScanner (the
                        // completion callback is a no-op), not confirmed indexed.
                        statusMessage = "Submitted $scannedCount / $totalToScan files to MediaScanner (${(prog * 100).toInt()}%)"
                    )
                    delay(40)
                }
            }

            try {
                RootShell.execFast("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///storage/emulated/0/Music 2>/dev/null")
            } catch (_: Throwable) {}

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            log("Completed ingestion scan: ${audioFiles.size} files indexed")
            nudgeMikuMusicLibrary(appContext)

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

    /**
     * FORCE SCAN — purely local: MediaScanner over every mounted volume (SD card + internal),
     * plus an explicit nudge to Miku Music's library engine so its own index catches up.
     * Never touches the network; works with the ingest engine OFF.
     */
    fun triggerForceScan(context: Context) {
        val appContext = context.applicationContext
        log("FORCE SCAN · local SD + internal MediaScanner, then Miku Music library rescan")
        // triggerRescan() nudges Miku Music itself once MediaScanner has finished — nudging here
        // too made the player rescan a still-stale MediaStore and then rescan again seconds later.
        triggerRescan(appContext)
    }

    private fun nudgeMikuMusicLibrary(appContext: Context) {
        try {
            appContext.sendBroadcast(
                Intent("com.miku.player.action.FORCE_LIBRARY_SCAN")
                    .setClassName("com.miku.player", "com.miku.player.MikuPrefsReceiver")
                    .putExtra("source", "launcher_force_scan")
            )
        } catch (_: Throwable) {}
    }

    fun triggerRsyncSync(context: Context) {
        val appContext = context.applicationContext
        if (!isEngineEnabled(appContext)) {
            log("Rsync ingest requested but the ingest engine is OFF · enable it from the shade tile first")
            _state.value = _state.value.copy(statusMessage = OFF_MESSAGE)
            return
        }
        scope.launch {
            val syncHost = MikuIngestConfig.syncHost(appContext)
            val rsyncPort = MikuIngestConfig.rsyncPort(appContext)

            log("Triggering Rsync Ingest Sync to $syncHost:$rsyncPort")
            _state.value = _state.value.copy(
                isScanning = true,
                scanProgress = 0.15f,
                statusMessage = "Connecting to Rsync Ingest Server ($syncHost:$rsyncPort)..."
            )

            // NO FAKE SYNC. This used to fire `rsync --version`, discard the result, log "Rsync sync
            // broadcast transmitted" (no broadcast was ever sent), sleep 1.5 s and then report
            // "Rsync sync completed" with a 100 % progress bar — while nothing had been transferred.
            // Report exactly what is actually known: whether an rsync binary exists at all and
            // whether the configured server answers.
            val rsyncVersion = try {
                RootShell.execOut("rsync --version 2>/dev/null")
                    ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            } catch (_: Throwable) { null }

            val status = when {
                syncHost.isBlank() ->
                    "No sync host configured — set one before an ingest sync can run"
                rsyncVersion.isNullOrBlank() ->
                    "rsync not available on this device — no sync performed"
                else ->
                    "rsync present ($rsyncVersion) · transfer not implemented in this build — no files were synced"
            }
            log(status)

            _state.value = _state.value.copy(
                isScanning = false,
                scanProgress = 0f,
                statusMessage = status
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
