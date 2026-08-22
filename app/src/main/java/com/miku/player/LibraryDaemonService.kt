package com.miku.player

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

/**
 * Background Library Daemon that runs independently of the Miku Music UI.
 * Keeps the music library and FastLibraryStore binary cache synchronized in real-time,
 * monitoring filesystem changes and MediaStore events even when the player app is closed.
 */
class LibraryDaemonService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var contentObserver: ContentObserver? = null
    private var isSyncing = false
    private var lastTrackCount = 0
    private var cachedBannerArt: android.graphics.Bitmap? = null
    private var lastNotificationText = ""
    private var lastNotificationPostMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cachedBannerArt = try {
            android.graphics.BitmapFactory.decodeResource(resources, R.drawable.miku_banner_cyber_stage)
        } catch (_: Throwable) { null }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring audio library · Real-time sync"))
        registerMediaObserver()
        MikuSyncTransceiver.startMonitoring(this)

        scope.launch {
            MikuSyncTransceiver.state.collect { sync ->
                val transportBadge = sync.transport.badge
                val status = if (sync.isTransferring) {
                    "🚀 SYNC IN PROGRESS (${String.format("%.1f", sync.transferRateMBs)} MB/s) · $transportBadge"
                } else {
                    "✨ Monitoring ${lastTrackCount.coerceAtLeast(0)} tracks · $transportBadge"
                }
                updateNotification(status, sync.isTransferring)
            }
        }

        syncLibrary("Initial background sync")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORCE_SYNC -> {
                syncLibrary("Manual trigger")
            }
        }
        return START_STICKY
    }

    private fun registerMediaObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var lastChangeMs = 0L
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val now = SystemClock.elapsedRealtime()
                if (now - lastChangeMs > 3000L) { // Debounce rapid filesystem churn
                    lastChangeMs = now
                    syncLibrary("Storage event detected")
                }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
    }

    private fun syncLibrary(triggerReason: String) {
        if (isSyncing) return
        isSyncing = true

        scope.launch {
            try {
                updateNotification("Syncing audio library…")
                val tracks = queryTracksFast()
                lastTrackCount = tracks.size

                FastLibraryStore.saveAsync(applicationContext, tracks)
                ScanProgress.generation.incrementAndGet()
                Log.i(TAG, "Daemon synced ${tracks.size} tracks ($triggerReason)")

                updateNotification("Monitoring ${tracks.size} tracks · Live audio sync")
            } catch (e: Throwable) {
                Log.e(TAG, "Error in daemon sync", e)
                updateNotification("Monitoring ${lastTrackCount.coerceAtLeast(0)} tracks · Active")
            } finally {
                isSyncing = false
            }
        }
    }

    private fun queryTracksFast(): List<Track> {
        val out = ArrayList<Track>()
        val hasBitrate = Build.VERSION.SDK_INT >= 30
        val proj = arrayListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DATE_ADDED,
        ).apply { if (hasBitrate) add(MediaStore.Audio.Media.BITRATE) }.toTypedArray()

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val iTrackNo = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iDateAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val iBr = if (hasBitrate) c.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val rawTrackNo = if (iTrackNo >= 0 && !c.isNull(iTrackNo)) c.getInt(iTrackNo) else 0
                val parsedTrackNo = if (rawTrackNo >= 1000) rawTrackNo % 1000 else rawTrackNo
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""

                if (!mediaStoreRowLikelyValid(path)) continue

                val albumArtist = if (iAlbumArtist >= 0 && !c.isNull(iAlbumArtist)) c.getString(iAlbumArtist) ?: "" else ""
                out.add(
                    Track(
                        id = id,
                        title = c.getString(iT) ?: "Unknown",
                        artist = c.getString(iA) ?: "Unknown artist",
                        album = c.getString(iAl) ?: "",
                        durationMs = c.getLong(iD),
                        sizeBytes = c.getLong(iS),
                        bitrateKbps = if (iBr >= 0 && !c.isNull(iBr)) c.getInt(iBr) / 1000 else 0,
                        mime = c.getString(iM) ?: "",
                        path = path,
                        year = if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear) else 0,
                        albumId = if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else 0L,
                        trackNumber = parsedTrackNo,
                        albumArtist = albumArtist,
                        dateAddedSec = if (iDateAdded >= 0 && !c.isNull(iDateAdded)) c.getLong(iDateAdded) else 0L
                    )
                )
            }
        }
        return out
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Miku Library Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps music library continuously synced in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val appIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rescanIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LibraryDaemonService::class.java).apply { action = ACTION_FORCE_SYNC },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wideBackgroundArt = cachedBannerArt

        val sync = MikuSyncTransceiver.state.value
        val syncDetail = if (sync.isTransferring) {
            "Active ingress at ${String.format("%.1f", sync.transferRateMBs)} MB/s to /storage/EAFF-98FE/MUSIC."
        } else {
            "Daemon listening on port ${MikuSyncTransceiver.RSYNC_PORT}. FastLibraryStore index is current."
        }

        val bigStyle = if (wideBackgroundArt != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(wideBackgroundArt)
                .setBigContentTitle("Miku Monitor")
                .setSummaryText("$lastTrackCount tracks on SD card")
        } else {
            NotificationCompat.BigTextStyle()
                .setBigContentTitle("Miku Monitor")
                .setSummaryText("Local Storage and Ingress")
                .bigText("$status\n\n$syncDetail")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_miku_monitor_status)
            .setColor(0xFF00E5FF.toInt())
            .setColorized(true)
            .setContentTitle("Miku Monitor")
            .setSubText("rsyncd :${MikuSyncTransceiver.RSYNC_PORT}")
            .setContentText(status)
            .setStyle(bigStyle)
            .setContentIntent(appIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Sync Now", rescanIntent)
            .addAction(0, "Open Player", appIntent)
            .build()
    }

    private fun updateNotification(status: String, forceOrActive: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val minInterval = if (forceOrActive) 3000L else 15000L
        if (status == lastNotificationText && now - lastNotificationPostMs < minInterval) {
            return
        }
        lastNotificationText = status
        lastNotificationPostMs = now
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LibraryDaemonService"
        const val CHANNEL_ID = "miku_library_daemon_channel"
        const val NOTIFICATION_ID = 8839
        const val ACTION_FORCE_SYNC = "com.miku.player.action.DAEMON_FORCE_SYNC"

        fun start(ctx: Context) {
            try {
                val intent = Intent(ctx, LibraryDaemonService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w("LibraryDaemonService", "Could not start daemon", e)
            }
        }
    }
}

/**
 * Boot receiver to start LibraryDaemonService automatically when the M500 boots up.
 */
class DaemonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("DaemonBootReceiver", "Starting LibraryDaemonService on boot")
            LibraryDaemonService.start(context)
        }
    }
}
