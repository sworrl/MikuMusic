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

    /** All SnapshotStateList mutations must happen on the MAIN thread and NEVER during
     *  composition. A state list mutated from a binder/IO thread while a composable pass also
     *  touched it crashes the Recomposer ("Unsupported concurrent change during composition" -
     *  crash-looped the app 2026-09-10 when resolveLiked's self-heal ran inside remember{}). */
    private fun mutateOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
    val likedAlbums = mutableStateListOf<String>()   // canonicalAlbumKey values
    /**
     * Tracks explicitly REFUSED. Liking an album means liking everything on it, UNLESS the user
     * said otherwise about one track, and this is where "otherwise" lives. It is deliberately not
     * the same thing as "absent from [liked]": absent means no opinion and follows the album, while
     * refused survives the album being un-liked and re-liked.
     */
    val refusedTracks = mutableStateListOf<Long>()
    val likedArtists = mutableStateListOf<String>()  // canonicalArtistKey values
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        // init is reached from binder threads too (media session custom commands) - the state
        // lists may only ever be mutated on main (see mutateOnMain).
        val t = PlayerPreferences.loadLikedTracks(ctx)
        val al = PlayerPreferences.loadLikedAlbums(ctx)
        val ar = PlayerPreferences.loadLikedArtists(ctx)
        val rf = PlayerPreferences.loadRefusedTracks(ctx)
        mutateOnMain {
            for (id in t) if (!liked.contains(id)) liked.add(id)
            for (k in al) if (!likedAlbums.contains(k)) likedAlbums.add(k)
            for (k in ar) if (!likedArtists.contains(k)) likedArtists.add(k)
            for (id in rf) if (!refusedTracks.contains(id)) refusedTracks.add(id)
        }
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
     * Register a heart for a track. ALWAYS allowed — the user can like anytime (incl. before 94%
     * and for songs heard before). The 94%-without-skip is NOT a gate; it's a WEIGHT recorded on
     * the like ("how much of it they'd heard, and WHEN"), which the taste algo reads (TasteEngine).
     * Each tap is one like event (timestamp + play-fraction + qualified). Returns the new count.
     */
    fun heart(ctx: Context, track: Track): Int {
        val fraction = MikuPlayQualifier.currentFraction(track.id)
        val qualified = MikuPlayQualifier.isQualified(track.id)
        PlayerPreferences.appendHeartEvent(ctx, track.id, System.currentTimeMillis(), (fraction * 100f).toInt(), qualified)
        val n = PlayerPreferences.getHeartCount(ctx, track.id) + 1
        PlayerPreferences.setHeartCount(ctx, track.id, n)         // also flips the boolean liked set
        mutateOnMain { if (!liked.contains(track.id)) liked.add(track.id) }
        PlayerPreferences.saveLikedTrackMeta(ctx, track.id, track.title, track.artist)
        MikuPlayQualifier.markHearted(track.id)
        PulsarLight.indicateHearted(ctx)
        broadcastLike(ctx, track.id, true, n)
        if (n == 1 && track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { LastFm.setLoved(it, track.artist, track.title, true) }
        }
        return n
    }

    /** Weighted affinity from the like-event log: base + how-much-heard, with mild recency decay,
     *  saturating so a heavily-loved track outranks a one-tap like. Read by TasteEngine. */
    fun heartAffinity(ctx: Context, id: Long): Float {
        val events = PlayerPreferences.getHeartEvents(ctx, id)
        if (events.isEmpty()) return if (isLiked(id)) 0.6f else 0f
        val now = System.currentTimeMillis()
        var acc = 0f
        for ((epoch, frac, q) in events) {
            val ageDays = ((now - epoch).coerceAtLeast(0L)) / 86_400_000f
            val recency = (1f - ageDays / 365f).coerceIn(0.4f, 1f)   // never below 0.4 — a like still counts
            val strength = 0.45f + 0.45f * frac + (if (q) 0.10f else 0f)  // full-listen like ~= 1.0
            acc += strength * recency
        }
        return (acc / 2.5f).coerceIn(0f, 1f)   // ~3 solid likes saturates
    }

    /** Remove ALL hearts for a track (un-like). Long-press / explicit clear. */
    fun clearHearts(ctx: Context, track: Track) {
        PlayerPreferences.setHeartCount(ctx, track.id, 0)          // flips liked off
        PlayerPreferences.clearHeartEvents(ctx, track.id)
        mutateOnMain { liked.remove(track.id) }
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
        val nowLiked = !liked.contains(id)
        mutateOnMain { if (nowLiked) { if (!liked.contains(id)) liked.add(id) } else liked.remove(id) }
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
    private data class Heal(val oldId: Long, val newId: Long, val title: String, val artist: String)

    /**
     * @param heal ONLY the canonical caller (MainActivity's liked shelf, whose [tracks] includes
     * disc-image virtual tracks) may pass true. The media session resolves against a DIFFERENT
     * track list (FastLibraryStore/MediaStore fallback, no virtual tracks) - when both healed,
     * likes ping-ponged between the two id spaces forever: liked churned every frame ->
     * recomposition/GC storm -> ANR or snapshot crash (the 2026-09-10 launch loop).
     */
    fun resolveLiked(ctx: Context, tracks: List<Track>, heal: Boolean = false): List<Track> {
        val ids = PlayerPreferences.loadLikedTracks(ctx)
        if (ids.isEmpty()) return emptyList()
        val byId = tracks.associateBy { it.id }
        val meta by lazy { PlayerPreferences.loadLikedTrackMeta(ctx) }
        val result = ArrayList<Track>(ids.size)
        val heals = ArrayList<Heal>()
        for (id in ids) {
            val direct = byId[id]
            if (direct != null) { result.add(direct); continue }
            val (title, artist) = meta[id] ?: continue
            val recovered = tracks.find { it.title == title && it.artist == artist } ?: continue
            result.add(recovered)
            heals.add(Heal(id, recovered.id, title, artist))
        }
        // Self-heal: fold likes onto the tracks' real current ids - but NEVER inline. This function
        // is called from remember{} during composition (MainActivity liked shelf) AND from binder
        // threads (media session); mutating the state list here was the 2026-09-10 crash-loop.
        // All heals apply as a normal main-thread event after the current frame.
        if (heal && heals.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                for (h in heals) {
                    PlayerPreferences.saveLikedTrack(ctx, h.oldId, false)
                    PlayerPreferences.removeLikedTrackMeta(ctx, h.oldId)
                    PlayerPreferences.saveLikedTrack(ctx, h.newId, true)
                    PlayerPreferences.saveLikedTrackMeta(ctx, h.newId, h.title, h.artist)
                    if (!liked.contains(h.newId)) liked.add(h.newId)
                    liked.remove(h.oldId)
                }
            }
        }
        return result
    }

    // ================================================== inherited likes
    /**
     * Where a track's like comes from.
     *
     *   TRACK    — liked on its own.
     *   ALBUM    — not liked on its own, but the album is, and the user has not refused it.
     *   NONE     — not liked.
     *   REFUSED  — the album is liked but the user said no to THIS track.
     */
    enum class LikeOrigin { NONE, TRACK, ALBUM, REFUSED }

    fun likeOrigin(ctx: Context, track: Track): LikeOrigin = when {
        liked.contains(track.id) -> LikeOrigin.TRACK
        refusedTracks.contains(track.id) -> LikeOrigin.REFUSED
        isAlbumLiked(track.artist.ifBlank { track.albumArtist }, track.album, ctx) -> LikeOrigin.ALBUM
        else -> LikeOrigin.NONE
    }

    /** Is this track liked, counting the album it belongs to? This is what the UI should ask. */
    fun isLikedEffective(ctx: Context, track: Track): Boolean =
        when (likeOrigin(ctx, track)) { LikeOrigin.TRACK, LikeOrigin.ALBUM -> true; else -> false }

    /**
     * Heart tap. Turns the EFFECTIVE state over, and records it explicitly so it beats the album:
     * liking a track the album already covered promotes it to its own like, un-liking one the album
     * covered records a refusal rather than silently doing nothing.
     */
    fun toggleEffective(ctx: Context, track: Track): Boolean {
        val wasLiked = isLikedEffective(ctx, track)
        if (wasLiked) {
            if (liked.contains(track.id)) clearHearts(ctx, track)
            mutateOnMain { if (!refusedTracks.contains(track.id)) refusedTracks.add(track.id) }
            PlayerPreferences.saveRefusedTrack(ctx, track.id, true)
        } else {
            mutateOnMain { refusedTracks.remove(track.id) }
            PlayerPreferences.saveRefusedTrack(ctx, track.id, false)
            heart(ctx, track)
        }
        return !wasLiked
    }

    /** Long press: drop every per-track opinion so the track follows its album again. */
    fun clearOverride(ctx: Context, track: Track) {
        if (liked.contains(track.id)) clearHearts(ctx, track)
        mutateOnMain { refusedTracks.remove(track.id) }
        PlayerPreferences.saveRefusedTrack(ctx, track.id, false)
    }

    fun isAlbumLiked(artist: String, album: String, ctx: Context? = null): Boolean =
        likedAlbums.contains(canonicalAlbumKey(artist, album, ctx))

    /**
     * Liking an album likes every track on it. Nothing is written per track: the tracks INHERIT it
     * through [likeOrigin], so the album stays one row instead of N, un-liking it takes the whole
     * set back with it, and a track the user explicitly liked on its own survives that.
     * Per-track refusals are deliberately left alone here too, so re-liking an album does not
     * quietly undo "except that one".
     */
    fun toggleAlbum(ctx: Context, artist: String, album: String): Boolean {
        val key = canonicalAlbumKey(artist, album, ctx)
        val now = if (likedAlbums.contains(key)) { likedAlbums.remove(key); false } else { likedAlbums.add(key); true }
        PlayerPreferences.saveLikedAlbum(ctx, key, now)
        if (now) PulsarLight.indicateHearted(ctx)
        return now
    }
}
