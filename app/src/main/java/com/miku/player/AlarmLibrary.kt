package com.miku.player

import android.content.Context
import android.provider.MediaStore

/** Lean, standalone library query for alarm playback — deliberately NOT reusing MainActivity's
 *  full queryTracks() (track-number inference, bitrate lookups, etc.) since none of that matters
 *  for "what plays when the alarm fires"; this only needs enough fields to build MediaItems. Runs
 *  from [[AlarmRingService]], which has no Activity to query through. */
object AlarmLibrary {
    fun tracksFor(ctx: Context, source: AlarmSource, sourceRef: String?): List<Track> {
        val all = queryAll(ctx)
        val picked = when (source) {
            AlarmSource.SHUFFLE_ALL, AlarmSource.DAILY_MIX -> all
            AlarmSource.LIKED_SONGS -> {
                val liked = PlayerPreferences.loadLikedTracks(ctx)
                all.filter { it.id in liked }
            }
            AlarmSource.ARTIST -> all.filter { it.artist.equals(sourceRef, ignoreCase = true) }
            AlarmSource.ALBUM -> all.filter { it.album.equals(sourceRef, ignoreCase = true) }
        }
        val pool = picked.ifEmpty { all } // never fire an alarm into silence just because a saved artist/album vanished
        return pool.shuffled()
    }

    private fun queryAll(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID
        )
        ctx.contentResolver.safeQuery(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""
                if (!mediaStoreRowLikelyValid(path)) continue // same ghost-row guard as MainActivity.queryTracks (see Model.kt doc comment — SD-card paths are trusted, not File-checked)
                out.add(
                    Track(
                        c.getLong(iId), c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown artist",
                        c.getString(iAl) ?: "", c.getLong(iD), c.getLong(iS),
                        0, c.getString(iM) ?: "", path, 0,
                        if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else 0L
                    )
                )
            }
        }
        return out
    }
}
