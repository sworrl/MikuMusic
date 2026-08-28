package com.miku.player

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf

/**
 * Single reactive source of truth for liked tracks/albums/artists so every heart (rows, mini-bar,
 * now-playing, artist/album headers) stays in sync. Backed by Compose snapshot lists, persisted
 * via PlayerPreferences.
 *
 * Three independent tiers, deliberately NOT cascaded into each other's boolean state — liking an
 * album does not flip every track's heart, liking a track does not flip its album's heart. Each
 * tier is its own explicit signal. What DOES combine them is [TasteEngine], which reads all three
 * tiers to compute a soft, continuous affinity score per track/album/artist (an album you liked
 * implies you probably like its tracks somewhat, even ones you never individually hearted — see
 * that file for the actual weighting). This file only owns the three raw boolean signals.
 *
 * Albums/artists are keyed by [canonicalAlbumKey]/[canonicalArtistKey] (Model.kt) rather than raw
 * display strings — display names are formatted (and for artists, user-configurable via the
 * "ignore The" setting), so keying likes on them directly meant a like could silently orphan
 * itself the moment the display mode changed, and two different artists' identically-titled
 * albums would collide onto one shared like. (2026-08-17: switched from raw-name keys to this —
 * existing album/artist likes from before this change won't carry over, tracks aren't affected.)
 */
object LikeStore {
    val liked = mutableStateListOf<Long>()
    val likedAlbums = mutableStateListOf<String>()   // canonicalAlbumKey values
    val likedArtists = mutableStateListOf<String>()  // canonicalArtistKey values
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        liked.addAll(PlayerPreferences.loadLikedTracks(ctx))
        likedAlbums.addAll(PlayerPreferences.loadLikedAlbums(ctx))
        likedArtists.addAll(PlayerPreferences.loadLikedArtists(ctx))
        loaded = true
    }

    fun isArtistLiked(artist: String, ctx: Context? = null): Boolean =
        likedArtists.contains(canonicalArtistKey(artist, ctx))

    fun toggleArtist(ctx: Context, artist: String): Boolean {
        val key = canonicalArtistKey(artist, ctx)
        val now = if (likedArtists.contains(key)) { likedArtists.remove(key); false } else { likedArtists.add(key); true }
        PlayerPreferences.saveLikedArtist(ctx, key, now)
        if (now) PulsarLight.indicateHearted(ctx)
        return now
    }

    fun isLiked(id: Long): Boolean = liked.contains(id)

    /** Cumulative heart score for a track (0 = not hearted). Feeds TasteEngine and ranking. */
    fun heartCount(ctx: Context, id: Long): Int = PlayerPreferences.getHeartCount(ctx, id)

    /**
     * Earn a heart for the CURRENT play. Only succeeds when the play qualified (>=94%, no skip)
     * and the user hasn't already spent this play's heart — that is the whole new model: hearts
     * are earned by listening. Returns the new cumulative count, or -1 when not currently earnable.
     */
    fun heart(ctx: Context, track: Track): Int {
        if (!MikuPlayQualifier.isHeartable(track.id)) return -1
        val n = PlayerPreferences.getHeartCount(ctx, track.id) + 1
        PlayerPreferences.setHeartCount(ctx, track.id, n)         // also flips the boolean liked set
        if (!liked.contains(track.id)) liked.add(track.id)
        PlayerPreferences.saveLikedTrackMeta(ctx, track.id, track.title, track.artist)
        MikuPlayQualifier.markHearted(track.id)
        PulsarLight.indicateHearted(ctx)
        broadcastLike(ctx, track.id, true, n)
        if (n == 1 && track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { LastFm.setLoved(it, track.artist, track.title, true) }
        }
        return n
    }

    /** Remove ALL hearts for a track (un-like). Long-press / explicit clear. */
    fun clearHearts(ctx: Context, track: Track) {
        PlayerPreferences.setHeartCount(ctx, track.id, 0)          // flips liked off
        liked.remove(track.id)
        PlayerPreferences.removeLikedTrackMeta(ctx, track.id)
        broadcastLike(ctx, track.id, false, 0)
        if (track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { LastFm.setLoved(it, track.artist, track.title, false) }
        }
    }

    private fun broadcastLike(ctx: Context, id: Long, liked: Boolean, count: Int) {
        try {
            android.provider.Settings.Global.putString(ctx.contentResolver, "miku_current_track_liked", if (liked) "1" else "0")
            android.provider.Settings.Global.putLong(ctx.contentResolver, "miku_current_liked_track_id", id)
            android.provider.Settings.Global.putInt(ctx.contentResolver, "miku_current_track_heart_count", count)
            ctx.sendBroadcast(Intent("com.miku.player.action.LIKE_STATE_CHANGED").apply {
                putExtra("track_id", id); putExtra("is_liked", liked); putExtra("heart_count", count)
            })
        } catch (_: Throwable) {}
    }

    /** id-only overload — kept for any call site that genuinely doesn't have the Track object
     *  handy. Prefer the Track overload below when it's available: it also mirrors the like onto
     *  Last.fm's "loved tracks" (see LastFm.setLoved), which this one can't do without a title/
     *  artist to send. */
    fun toggle(ctx: Context, id: Long): Boolean {
        val nowLiked = if (liked.contains(id)) { liked.remove(id); false } else { liked.add(id); true }
        PlayerPreferences.saveLikedTrack(ctx, id, nowLiked)
        // Keep the heart score consistent with the boolean: turning it on seeds at least one heart,
        // turning it off zeroes the score. (Earning extra hearts goes through heart() per play.)
        PlayerPreferences.setHeartCount(ctx, id, if (nowLiked) PlayerPreferences.getHeartCount(ctx, id).coerceAtLeast(1) else 0)
        if (nowLiked) PulsarLight.indicateHearted(ctx)
        try {
            android.provider.Settings.Global.putString(
                ctx.contentResolver,
                "miku_current_track_liked",
                if (nowLiked) "1" else "0"
            )
            android.provider.Settings.Global.putLong(
                ctx.contentResolver,
                "miku_current_liked_track_id",
                id
            )
            val out = Intent("com.miku.player.action.LIKE_STATE_CHANGED").apply {
                putExtra("track_id", id)
                putExtra("is_liked", nowLiked)
            }
            ctx.sendBroadcast(out)
        } catch (_: Throwable) {}
        return nowLiked
    }

    /** Every real UI call site already has the Track in hand — use this one. Mirrors the like
     *  state onto Last.fm's "loved tracks" when connected (best-effort, never blocks the local
     *  toggle on network success — see LastFm.setLoved). */
    fun toggle(ctx: Context, track: Track): Boolean {
        val nowLiked = toggle(ctx, track.id)
        // Best-effort (title, artist) backup so this like can self-heal if MediaStore ever
        // reassigns this track's _id across a rescan — see PlayerPreferences.saveLikedTrackMeta.
        if (nowLiked) PlayerPreferences.saveLikedTrackMeta(ctx, track.id, track.title, track.artist)
        else PlayerPreferences.removeLikedTrackMeta(ctx, track.id)
        if (track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { sk ->
                LastFm.setLoved(sk, track.artist, track.title, nowLiked)
            }
        }
        return nowLiked
    }

    /** Resolves the liked-track id set against the CURRENT library, self-healing any id that no
     *  longer matches (a MediaStore rescan reassigned it) by falling back to a (title, artist)
     *  match against the saved metadata and re-persisting the corrected id. Without this, an
     *  orphaned like just silently vanishes from every "Liked Songs" view with no explanation —
     *  confirmed this is a real, not theoretical, failure mode on this app's rescan-heavy library. */
    fun resolveLiked(ctx: Context, tracks: List<Track>): List<Track> {
        val ids = PlayerPreferences.loadLikedTracks(ctx)
        if (ids.isEmpty()) return emptyList()
        val byId = tracks.associateBy { it.id }
        val meta by lazy { PlayerPreferences.loadLikedTrackMeta(ctx) }
        val result = ArrayList<Track>(ids.size)
        for (id in ids) {
            val direct = byId[id]
            if (direct != null) { result.add(direct); continue }
            val (title, artist) = meta[id] ?: continue
            val recovered = tracks.find { it.title == title && it.artist == artist } ?: continue
            result.add(recovered)
            // Self-heal: fold the like onto the track's real current id so future lookups hit the
            // fast path directly, and carry the metadata forward under the new id.
            PlayerPreferences.saveLikedTrack(ctx, id, false)
            PlayerPreferences.removeLikedTrackMeta(ctx, id)
            PlayerPreferences.saveLikedTrack(ctx, recovered.id, true)
            PlayerPreferences.saveLikedTrackMeta(ctx, recovered.id, title, artist)
            if (!liked.contains(recovered.id)) liked.add(recovered.id)
            liked.remove(id)
        }
        return result
    }

    fun isAlbumLiked(artist: String, album: String, ctx: Context? = null): Boolean =
        likedAlbums.contains(canonicalAlbumKey(artist, album, ctx))

    fun toggleAlbum(ctx: Context, artist: String, album: String): Boolean {
        val key = canonicalAlbumKey(artist, album, ctx)
        val now = if (likedAlbums.contains(key)) { likedAlbums.remove(key); false } else { likedAlbums.add(key); true }
        PlayerPreferences.saveLikedAlbum(ctx, key, now)
        if (now) PulsarLight.indicateHearted(ctx)
        return now
    }
}
