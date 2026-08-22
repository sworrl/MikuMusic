package com.miku.launcher.track

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MikuLibraryState(
    val totalTracks: Int = 0,
    val totalArtists: Int = 0,
    val totalAlbums: Int = 0,
    val hiResCount: Int = 0,
    val abbreviatedTracks: String = "0",
    val isInitialized: Boolean = false
)

/**
 * System-Level Audio Library & Track Telemetry Engine.
 * Queries MediaStore in real-time and observes storage additions/deletions.
 */
object MikuLibraryEngine {
    private val _state = MutableStateFlow(MikuLibraryState())
    val state: StateFlow<MikuLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        refresh(appContext)

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

    fun refresh(context: Context) {
        scope.launch {
            try {
                val cr = context.contentResolver
                var trackCount = 0
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_MUSIC)
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

                cr.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null
                )?.use { cursor ->
                    trackCount = cursor.count
                }

                val abbrev = when {
                    trackCount >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", trackCount / 1_000_000f).removeSuffix(".0M") + "M"
                    trackCount >= 10_000 -> "${trackCount / 1000}k"
                    trackCount >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", trackCount / 1000f).removeSuffix(".0k") + "k"
                    else -> trackCount.toString()
                }

                _state.value = MikuLibraryState(
                    totalTracks = trackCount,
                    abbreviatedTracks = abbrev,
                    isInitialized = true
                )
            } catch (_: Throwable) {}
        }
    }
}
