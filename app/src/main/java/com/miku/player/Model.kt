package com.miku.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Ghost-row guard for MediaStore rows that outlived the actual file (deletes/moves done outside
 * the scanner) — but only reliable for the PRIMARY storage volume. Confirmed live on-device
 * (2026-08-17, `adb shell run-as com.miku.player test -e <sd-card-path>` → "MISSING" on a file
 * that genuinely exists and that MediaStore itself correctly lists) that `java.io.File.exists()`
 * cannot see removable/secondary storage (SD cards) at all from this app's process on API 33+
 * without MANAGE_EXTERNAL_STORAGE — scoped storage blocks raw filesystem access to anything
 * outside the app's own sandbox and primary external storage; only MediaStore/ContentResolver
 * (a privileged system service) can still see removable media. Applying the ghost check
 * unconditionally there was silently dropping THOUSANDS of perfectly real SD-card tracks from
 * the library (reported live: MediaStore had ~5970 audio rows on the SD card, the app surfaced
 * only ~4000) — worse than the rare stale-ghost-row case this check exists to catch. So: verify
 * via File I/O only for paths under primary storage (where it's reliable); trust MediaStore's own
 * row for everything else rather than risk mass-dropping a removable-media library.
 */
fun mediaStoreRowLikelyValid(path: String): Boolean {
    if (path.isBlank()) return true
    // With MANAGE_EXTERNAL_STORAGE granted (see requestAllFilesAccessIfNeeded in MainActivity),
    // java.io.File works reliably on every volume — verify everywhere, which is what actually
    // catches real ghost rows. Without it, only primary storage is checkable; anything else falls
    // back to trusting MediaStore's row rather than mass-dropping a removable-media library.
    if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
        return java.io.File(path).exists()
    }
    val primaryRoot = android.os.Environment.getExternalStorageDirectory()?.absolutePath
    if (primaryRoot == null || !path.startsWith(primaryRoot)) return true // not on primary storage — can't verify via File I/O, trust MediaStore
    return java.io.File(path).exists()
}

data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val durationMs: Long, val sizeBytes: Long, val bitrateKbps: Int, val mime: String,
    val path: String = "", val year: Int = 0, val albumId: Long = 0L, val trackNumber: Int = 0,
    val albumArtist: String = "", val dateAddedSec: Long = 0L,
    /** Disc number for multi-disc sets (0 = unknown/single disc). MediaStore packs it as
     *  disc*1000+track in the TRACK column; kept separately so disc order survives grouping. */
    val discNumber: Int = 0,
    /** Whole-CD image rip (one file = the entire disc). See DiscImage. */
    val isDiscImage: Boolean = false,
    /** Sibling .cue sheet for a disc image, "" if none. */
    val cuePath: String = "",
    /** For a VIRTUAL track cut from a disc image: the image's MediaStore id (0 = a real file). */
    val parentId: Long = 0L,
    /** Virtual track clip window inside the parent image (ms); clipEndMs 0 = to the end. */
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
)

data class ArtistGroup(val name: String, val tracks: List<Track>) {
    val albumCount get() = tracks.map { it.album }.distinct().size
    /** Most recent add-time across the artist's tracks — an artist reads as "new" from the moment
     *  its newest track landed, not its oldest. */
    val dateAddedSec: Long get() = tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
}

/** The track whose art represents this artist wherever a single piece of cover art is shown
 *  (list rows, hero headers, "Newly Added" cards) — the user's own pin if they've set one and
 *  it's still in this artist's tracks, else just the first track (the old, arbitrary default).
 *  Keyed on canonicalArtistKey(), NOT the raw display name — `name` here is already
 *  formatArtistDisplayName()'d (PREFIX/SUFFIX/STRIP "The" placement), which changes if the user
 *  ever flips the "sort ignoring The" display mode; a pin keyed on that mutable display string
 *  would silently stop matching (and leak an orphaned prefs entry) the moment they did. */
fun ArtistGroup.coverTrack(ctx: android.content.Context): Track? {
    val pinnedId = PlayerPreferences.loadArtistCoverTrack(ctx, canonicalArtistKey(name, ctx))
    if (pinnedId != null) tracks.firstOrNull { it.id == pinnedId }?.let { return it }
    return tracks.firstOrNull()
}

data class AlbumGroup(val name: String, val artist: String, val tracks: List<Track>) {
    /** True when this album is (at least partly) a whole-CD image rip. */
    val hasDiscImage: Boolean get() = tracks.any { it.isDiscImage }
    /** Cue-less images still shown as ONE file (no real track list). */
    val unsplitImageCount: Int get() = tracks.count { it.isDiscImage && it.parentId == 0L }
    /** Number of physical disc images behind this album (multi-file ".1/.2" images count each). */
    val discImageFiles: Int get() = tracks.filter { it.isDiscImage }.map { if (it.parentId != 0L) it.parentId else it.id }.distinct().size
    /** Earliest year present on the album (0 if none) — used to order albums chronologically. */
    val year: Int get() = tracks.mapNotNull { it.year.takeIf { y -> y > 0 } }.minOrNull() ?: 0
    /** Most recent add-time across the album's tracks (same reasoning as ArtistGroup above). */
    val dateAddedSec: Long get() = tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
}

/**
 * "New" indicator prominence for a track/album/artist, per the exact rule requested: subtle
 * within 7 days of being added, LESS subtle (louder) within the first 24 hours, and MORE subtle
 * (quieter) once the item has actually been played — the freshness signal fades once it's served
 * its purpose. Nothing shown past the 7-day window.
 */
object Newness {
    private const val DAY_SEC = 86_400L
    const val WINDOW_SEC = 7 * DAY_SEC

    /** True while an item is still inside the 7-day "new" window — the same cutoff `prominence`
     *  itself uses, exposed separately for callers (e.g. Home's "Newly Added" section) that just
     *  need a yes/no filter rather than a prominence value. */
    fun isNew(dateAddedSec: Long, nowSec: Long): Boolean =
        dateAddedSec > 0L && (nowSec - dateAddedSec) in 0..WINDOW_SEC

    /** 0f = show nothing; otherwise a 0..1 prominence driving the indicator's alpha/size/pulse. */
    fun prominence(dateAddedSec: Long, nowSec: Long, played: Boolean): Float {
        if (dateAddedSec <= 0L) return 0f
        val age = nowSec - dateAddedSec
        if (age < 0 || age > WINDOW_SEC) return 0f
        val boosted = age <= DAY_SEC
        return when {
            boosted && !played -> 1.0f    // brand new, untouched — the loudest state
            boosted && played -> 0.35f    // brand new but already played — dampened
            !played -> 0.55f              // within the week, unplayed — the baseline "subtle"
            else -> 0.16f                 // within the week AND already played — as quiet as it gets
        }
    }
}

// Real single-artist names that would otherwise be false-positively torn apart by the
// collaboration-delimiter splitting below, because the ACTUAL name happens to contain a word or
// symbol also used as a collab separator elsewhere. No generic rule can tell "Denzel Curry and
// Kingpin Skinny Pimp" (a collab tag, should split) apart from "Fear, and Loathing in Las Vegas"
// (one real band's actual name, must NOT split) — found by manually auditing the on-device
// library, where both of these were confirmed genuinely mis-split. Checked case-insensitively,
// so add more here as they turn up rather than trying to out-clever the regex.
private val NO_SPLIT_ARTIST_NAMES = setOf(
    "author & punisher",
    "fear, and loathing in las vegas"
)

// All artist-normalization regexes are hoisted to top-level vals — compiled ONCE, not on every
// call. These run per-track (thousands of times) inside a synchronous `remember{}` grouping pass
// on the main thread; recompiling a handful of regexes (plus java.text.Normalizer) per call on
// every track was slow enough on a 3000+ track library to trip Android's ANR watchdog and make
// the app fail to launch — verified live, this is not a hypothetical.
private val PAREN_FEAT_RE = Regex("(?i)\\s*[\\[\\(](feat|ft|featuring)\\b.*?[\\)\\]]")
private val ARTIST_SPLIT_RE =
    Regex("(?i)\\s*(?:\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b|\\band\\b|\\bw/|[&/+,;|]|\\bx\\b|\\bvs\\.?\\b|[–—])\\s*")
private val DISC_FOLDER_RE = Regex("(?i)^(cd|disc|disk|vol\\.?|volume)\\s*\\.?\\s*\\d{1,3}$|^\\d{1,2}$")
private val YEAR_ALBUM_RE = Regex("^(19|20)\\d{2}\\s*[-–—:].+")
private val YEAR_ONLY_RE = Regex("^(?:19|20)\\d{2}(?:[-/]\\d{2,4})?$|^\\[(?:19|20)\\d{2}\\]$|^\\((?:19|20)\\d{2}\\)$")
private val TRAILING_BRACKET_RE = Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$")
private val TRAILING_THE_RE = Regex("(?i),?\\s+the$")
private val COMBINING_MARKS_RE = Regex("\\p{Mn}+")
private val NON_LETTER_DIGIT_RE = Regex("[^\\p{L}\\p{Nd}\\s]")
private val WHITESPACE_RUN_RE = Regex("\\s+")

private val normalizeArtistCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun normalizeArtistName(rawArtist: String): String {
    normalizeArtistCache[rawArtist]?.let { return it }
    val res = normalizeArtistNameUncached(rawArtist)
    normalizeArtistCache[rawArtist] = res
    return res
}

private fun normalizeArtistNameUncached(rawArtist: String): String {
    if (rawArtist.isBlank()) return "Unknown Artist"
    if (rawArtist.trim().lowercase() in NO_SPLIT_ARTIST_NAMES) return rawArtist.trim()

    // 1. Remove parenthetical feature tags e.g. (feat. XYZ), [ft. ABC]
    var clean = PAREN_FEAT_RE.replace(rawArtist, "")

    // 2. Split on collaboration delimiters: feat., ft., featuring, with, and, &, /, +, x, vs.,
    val parts = clean.split(ARTIST_SPLIT_RE).filter { it.isNotBlank() }

    if (parts.isNotEmpty()) {
        clean = parts[0].trim()
    }

    // 3. Trim extra quotes, hyphens, and whitespace
    clean = clean.trim(' ', '"', '\'', '-', ',')

    return clean.ifBlank { rawArtist.trim() }
}

// Folder names that mean "you've walked off the top of the music tree" — a storage volume root
// (exFAT/FAT volume ids like "EAFF-98FE"), the emulated-storage scaffolding, or a mount point.
// Confirmed live (2026-08-25): an album folder dropped FLAT at the SD root ("Music/2 Unlimited -
// Get Ready (1992) (FLAC)/…") walked past the generic "Music" folder and returned the volume id
// "EAFF-98FE" as the ARTIST — hundreds of tracks landed under a phantom artist named after the
// card. Path inference must stop dead here, never climb above it.
private val STORAGE_ROOT_NAMES = setOf("storage", "emulated", "0", "sdcard", "self", "media_rw", "mnt", "primary", "external_sd", "sdcard1", "")
private val VOLUME_ID_RE = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
private fun isStorageRootFolder(f: java.io.File): Boolean {
    val n = f.name.trim()
    return n.lowercase() in STORAGE_ROOT_NAMES || VOLUME_ID_RE.matches(n) || f.parentFile == null
}

// Scene-release / ingest-staging folder names are NOT artist names: "1988-2022 Lords Of Acid",
// "(1998) Eiffel 65 - Blue (First Press)", "2.Unlimited-Get.Ready-(BYTE.5006)-CDS-FLAC-1991-WRE",
// "[24bit 96kHz Vinyl Rip]". Strip the leading year/year-range decoration first; if what's left is
// empty, or the name carries codec/format/source tokens, it's a release folder and yields no artist.
private val LEADING_YEAR_DECOR_RE = Regex("^[\\[(]?(?:19|20)\\d{2}(?:\\s*[-–—/]\\s*(?:19|20)?\\d{2,4})?[\\])]?\\s*[-–—:.]?\\s*")
private val SCENE_TOKEN_RE = Regex("(?i)(?:\\b(?:flac|alac|mp3|aac|ogg|opus|wav|dsd|dsf|dff|web|cds?|cdm|cdr|rip|vinyl|remaster(?:ed)?|bootleg|lossless|hi-?res|scene|proper|repack|retail|promo)\\b|\\b\\d{1,2}\\s*-?\\s*bit\\b|\\b\\d{2,3}(?:\\.\\d)?\\s*k(?:hz)?\\b|\\b24-?(?:44|48|88|96|176|192)\\b|\\b16-?44\\b|[\\[(][^\\])]*[\\])])")
private fun cleanArtistCandidate(raw: String): String? {
    var c = raw.trim()
    c = LEADING_YEAR_DECOR_RE.replace(c, "").trim()
    if (c.isBlank()) return null
    // Flat "Artist - Album…" names: only the ARTIST half is a candidate.
    if (c.contains(" - ")) c = c.substringBefore(" - ").trim()
    c = TRAILING_BRACKET_RE.replace(c, "").trim().trim('-', '_', '.', ' ')
    if (c.isBlank() || c.length < 2) return null
    if (YEAR_ONLY_RE.matches(c)) return null
    if (c.lowercase() in GENERIC_FOLDER_NAMES) return null
    // Dotted scene names ("2.Unlimited-Get.Ready.For.This-(BYTE.5006)-CDS-FLAC-1991-WRE") and
    // anything still carrying format/source tags are release names, not artists.
    if (SCENE_TOKEN_RE.containsMatchIn(c)) return null
    if (c.count { it == '.' } >= 2 && !c.contains(' ')) return null
    return c
}

private val GENERIC_FOLDER_NAMES = setOf(
    "music", "download", "downloads", "audio", "sdcard", "internal storage", "0", "media",
    "files", "unknown", "albums", "album", "recordings", "recording", "tracks", "songs",
    "singles", "single", "eps", "ep", "discography", "discographies", "author", "artists", "artist",
    "flac", "mp3", "lossless", "hires", "hi-res", "cd", "cds", "vinyl", "cassette"
)

private val inferPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun inferAlbumArtistFromPath(path: String, albumTitle: String): String? {
    if (path.isBlank()) return null
    val cacheKey = "$path::$albumTitle"
    inferPathCache[cacheKey]?.let { return if (it.isEmpty()) null else it }
    val res = inferAlbumArtistFromPathUncached(path, albumTitle)
    inferPathCache[cacheKey] = res ?: ""
    return res
}

private fun inferAlbumArtistFromPathUncached(path: String, albumTitle: String): String? {
    try {
        val file = java.io.File(path)
        var curr = file.parentFile ?: return null

        // 1. Walk past disc/volume subfolders (CD 1, Disc 2, etc.)
        var hops = 0
        while (hops < 4 && DISC_FOLDER_RE.matches(curr.name.trim())) {
            curr = curr.parentFile ?: return null
            hops++
        }
        if (isStorageRootFolder(curr)) return null

        // Now `curr` is the Album folder (e.g. "1999 - The Gift Of Game [JP]")
        val albumFolder = curr
        var artistFolder = albumFolder.parentFile

        // Walk past generic intermediate container folders (e.g. "Albums", "Discography", "Singles", "EP")
        // — but NEVER past a storage root: "Music" directly under the card root means the album folder
        // sits flat at the top of the tree and there simply is no artist folder to read.
        hops = 0
        while (artistFolder != null && hops < 4 && !isStorageRootFolder(artistFolder) &&
            artistFolder.name.trim().lowercase() in GENERIC_FOLDER_NAMES) {
            artistFolder = artistFolder.parentFile
            hops++
        }
        if (artistFolder != null && isStorageRootFolder(artistFolder)) artistFolder = null

        // 2. Check artistFolder (e.g. "Crazy Town - Discography 1999-2015 [FLAC]" or "Crazy Town")
        if (artistFolder != null) {
            val aName = artistFolder.name.trim()
            if (aName.lowercase() !in GENERIC_FOLDER_NAMES && aName.length > 1 && !YEAR_ALBUM_RE.matches(aName)) {
                cleanArtistCandidate(aName)?.let { return it }
            }
        }

        // 3. Check if the albumFolder itself is "Artist - Album" (e.g. "Crazy Town - The Gift of Game")
        val pName = albumFolder.name.trim()
        if (pName.contains(" - ") && !YEAR_ALBUM_RE.matches(pName)) {
            cleanArtistCandidate(pName)?.let { return it }
        }
    } catch (_: Throwable) {}
    return null
}

object ArtistTransliterationStore {
    private var file: java.io.File? = null
    private val cache = HashMap<String, String>()
    @Volatile private var loaded = false

    @Synchronized private fun ensure(ctx: android.content.Context) {
        if (loaded) return
        file = java.io.File(ctx.filesDir, "artist_trans.json")
        runCatching {
            val f = file!!
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                o.keys().forEach { k -> cache[k] = o.getString(k) }
            }
        }
        loaded = true
    }

    @Volatile private var lastPersistAtMs = 0L

    // getEnglishName() calls persist() on every single new cache entry — on a library with K
    // distinct non-cached artist strings (the whole first grouping pass on a multi-script library),
    // that used to mean K full-cache-serialize-and-rewrite calls, each one writing a file that's
    // grown by one more entry than the last: O(K²) total. Throttled to at most once per 2s here —
    // this cache is a regenerable transliteration lookup (cheap ICU call), so worst case losing the
    // last couple of seconds of new entries to a process death is a non-issue, and it's a much
    // better trade than blocking the grouping pass with dozens of rewrites of a growing file.
    @Synchronized private fun persist() {
        val f = file ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPersistAtMs < 2000L) return
        runCatching {
            val o = org.json.JSONObject()
            cache.forEach { (k, v) -> o.put(k, v) }
            f.writeText(o.toString())
            lastPersistAtMs = now
        }
    }

    fun getEnglishName(ctx: android.content.Context?, rawArtist: String): String {
        if (ctx == null) return ""
        val trimmed = rawArtist.trim()
        if (trimmed.isBlank()) return ""
        ensure(ctx)

        synchronized(this) {
            if (cache.containsKey(trimmed)) {
                return cache[trimmed] ?: ""
            }
        }

        // Check if string contains non-Latin / CJK / Japanese / Cyrillic / Extended characters
        val hasNonLatin = trimmed.any { c ->
            val code = c.code
            code > 0x024F || (code in 0x00C0..0x017F)
        }

        if (!hasNonLatin) {
            synchronized(this) {
                cache[trimmed] = ""
                persist()
            }
            return ""
        }

        val transliterated = try {
            val trans = android.icu.text.Transliterator.getInstance("Any-Latin; Title")
            val res = trans.transliterate(trimmed).trim()
            if (res.equals(trimmed, ignoreCase = true)) "" else res
        } catch (_: Throwable) {
            ""
        }

        synchronized(this) {
            cache[trimmed] = transliterated
            persist()
        }
        return transliterated
    }
}

// Confirmed source-tag typos/variant spellings, found by manually auditing the on-device library
// (e.g. "Breaking Bejamin" vs "Breaking Benjamin" — 12 tracks tagged with the typo). Hardcoded
// and hand-verified rather than fuzzy-matched: fuzzy string matching here would risk silently
// merging genuinely different artists with similar names. Keys are the fully-normalized form
// (lowercase, punctuation-stripped, whitespace-collapsed) — add more here as they're found.
private val ARTIST_KEY_ALIASES = mapOf(
    "breaking bejamin" to "breaking benjamin",
    "the beastie boys" to "beastie boys",
    "beastie boys the" to "beastie boys"
)

// canonicalArtistKey does several regex passes + Unicode normalization + (for non-Latin names) an
// ArtistTransliterationStore lookup — real work, not free. .artists() below calls it 3-4 times per
// TRACK just to compare/group the same handful of candidate strings (tag artist, album artist,
// path-inferred artist), and the exact same string values get recomputed yet again by the
// trailing .groupBy. On a small library that's noise; on a large one (tens of thousands of
// tracks) it's real, avoidable CPU — memoized here so every caller benefits, not just .artists().
private val canonicalArtistKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun canonicalArtistKey(artistName: String, ctx: android.content.Context? = null): String {
    canonicalArtistKeyCache[artistName]?.let { return it }
    val result = canonicalArtistKeyUncached(artistName, ctx)
    canonicalArtistKeyCache[artistName] = result
    return result
}

private fun canonicalArtistKeyUncached(artistName: String, ctx: android.content.Context? = null): String {
    var clean = normalizeArtistName(artistName).trim()
    if (clean.startsWith("the ", ignoreCase = true)) {
        clean = clean.substring(4).trim()
    }
    // Also strip a TRAILING ", The" / " The" (a common alternate/"sort name" convention — some
    // rips tag "Crystal Method, The" or "Crystal Method The" instead of "The Crystal Method").
    // Without this, that variant keyed as "...method the" and never merged with the prefixed form.
    clean = TRAILING_THE_RE.replace(clean, "").trim()
    clean = clean.trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    if (clean.any { it.code > 127 }) {
        // If the tag already mixes non-Latin script with a human-provided Latin reading (e.g.
        // "花冷え hanabie" vs a plain "花冷え" elsewhere), trust that existing Latin text rather
        // than re-transliterating the whole mixed string — ICU run on the mixed form doesn't
        // reliably land on the same output as ICU run on the pure-CJK form, which was the
        // difference between those two staying as separate groups.
        val asciiOnly = clean.filter { it.code in 32..126 }.trim()
        if (asciiOnly.any { it.isLetter() }) {
            clean = asciiOnly
        } else {
            // Transliterate non-Latin CJK (Japanese, Chinese, Korean, Cyrillic) to Latin so
            // Japanese & English spellings merge into one group.
            val latin = ArtistTransliterationStore.getEnglishName(ctx, clean)
            if (latin.isNotBlank()) {
                clean = latin.trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
            }
        }
    }
    // Fold accents (é -> e) so differently-tagged rips of the same artist (with/without
    // diacritics is a common inconsistency across sources) collapse to one key. Normalizer.
    // normalize can throw IllegalArgumentException on malformed/unpaired UTF-16 surrogates —
    // plausible in a library with scene-release/bootleg tags — and every OTHER risky call in this
    // file is defensively wrapped except this one was not. Root-caused live (2026-08-17): this
    // being unguarded meant one bad artist string anywhere in the library could throw uncaught out
    // of the whole eager .artists() flatMap/groupBy/map chain, and since MainActivity's
    // LaunchedEffect around that call had no try/catch either, artistGroups silently froze on
    // whatever it last computed successfully — "The Crystal Method" (and its whole SD-card tree)
    // never appeared as a searchable artist because the entire grouping pass had stopped updating
    // well before that content was even scanned in, not because of any actual grouping-logic bug.
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    // Strip everything that isn't a letter/digit/space (smart quotes, stray punctuation, zero-
    // width/invisible characters some taggers inject) and collapse whitespace runs to one space —
    // "Denzel  Curry" (double space) and "Denzel Curry" must key identically, and currently didn't.
    clean = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
    return ARTIST_KEY_ALIASES[clean] ?: clean
}

// Stable album identity for anything that needs to persist an album reference across sessions
// (liked-albums, taste affinity) — didn't exist anywhere before this. Album display names/artists
// are just formatted strings (AlbumGroup.name/.artist), the same kind of unstable identity that
// caused both the LazyGrid key-collision crash (two albums formatting to the same display string)
// and the artist-like desync (raw display name changing when the "ignore The" display setting
// flips). Built the same way: artist half goes through the existing canonicalArtistKey, album half
// gets the same accent-fold/punctuation-strip treatment, both guarded against the same
// Normalizer-on-malformed-surrogates crash class already fixed once in canonicalArtistKeyUncached.
fun canonicalAlbumKey(artist: String, album: String, ctx: android.content.Context? = null): String {
    var clean = album.trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    clean = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
    return "${canonicalArtistKey(artist, ctx)}::$clean"
}

fun formatArtistDisplayName(rawName: String, mode: String, englishName: String = ""): String {
    val trimmed = rawName.trim()
    val isThe = trimmed.startsWith("The ", ignoreCase = true)
    val baseDisplay = if (isThe) {
        val baseName = trimmed.substring(4).trim()
        when (mode) {
            "SUFFIX" -> "$baseName, The"
            "STRIP" -> baseName
            else -> "The $baseName"
        }
    } else trimmed

    return if (englishName.isNotBlank() && !englishName.equals(trimmed, ignoreCase = true)) {
        "$baseDisplay ($englishName)"
    } else {
        baseDisplay
    }
}

fun formatArtistSortKey(rawName: String, ignoreThe: Boolean, ctx: android.content.Context? = null): String {
    var key = rawName.trim()
    if (ignoreThe) {
        if (key.startsWith("the ", ignoreCase = true)) key = key.substring(4).trim()
        // Also ignore a trailing ", The"/" The" sort-name convention (mirrors canonicalArtistKey)
        // — otherwise a group whose chosen display happened to be the suffixed form would sort
        // under "T" while every "The X"-prefixed neighbor sorted under "X".
        key = TRAILING_THE_RE.replace(key, "").trim()
    }
    // If the name has English in parentheses e.g. "花冷え。 (Hanabie)", or starts with non-ASCII:
    // extract the Latin transliteration so it sorts under 'H', not '#' at the bottom!
    if (key.contains("(") && key.contains(")")) {
        val insideParen = key.substringAfter("(").substringBefore(")").trim()
        if (insideParen.isNotBlank() && insideParen.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            key = insideParen
        }
    } else if (key.any { it.code > 127 }) {
        // Same mixed-script preference as canonicalArtistKey: if the name already carries a
        // human-provided Latin reading alongside non-Latin script (no parens this time, e.g.
        // "花冷え hanabie"), sort on that rather than a fresh ICU transliteration of the whole
        // mixed string, which doesn't reliably match the pure-CJK form's transliteration.
        val asciiOnly = key.filter { it.code in 32..126 }.trim()
        key = if (asciiOnly.any { it.isLetter() }) {
            asciiOnly
        } else {
            ArtistTransliterationStore.getEnglishName(ctx, key).ifBlank { key }
        }
    }
    // Fold accents and collapse whitespace/punctuation noise the same way the grouping key does —
    // two artists that now MERGE into one group only sort consistently if this matches; it also
    // keeps standalone (ungrouped) names from landing in a slightly-off position relative to
    // their plain-ASCII neighbors just because of a stray accent or double space.
    // Same defensive wrap as canonicalArtistKeyUncached — Normalizer.normalize can throw on
    // malformed/unpaired UTF-16 surrogates, and this call was the other unguarded one that let a
    // single bad string abort the whole eager sortedBy() pass. See that function's comment for the
    // full story (this is the fix for the live-confirmed "Crystal Method missing from Artists" bug).
    key = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFD), "") }.getOrDefault(key)
    key = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(key, " ").trim(), " ")
    return key.lowercase()
}

/** True for any of the several spellings a "no real artist tag" ends up as by the time it
 *  reaches here: MediaStore's own placeholder, queryTracks()'s null-cursor fallback text, or
 *  normalizeArtistName's blank-input sentinel. Used to be a single exact string compare against
 *  ONE of these — missed the others, so genuinely untagged-but-well-organized-by-folder tracks
 *  (verified live: 57 tracks, e.g. a whole Godsmack album) got dumped into one catch-all "artist"
 *  instead of falling through to path inference like they should have. */
private fun isBlankArtistTag(s: String): Boolean {
    val t = s.trim().lowercase()
    return t.isEmpty() || t == "unknown" || t == "unknown artist" || t == "<unknown>" || YEAR_ONLY_RE.matches(t)
}

fun List<Track>.artists(
    ctx: android.content.Context? = null,
    ignoreThe: Boolean = true,
    displayMode: String = "PREFIX"
): List<ArtistGroup> =
    // Multi-membership: the tag artist always counts (when it's a real one); album_artist and the
    // folder-inferred artist ALSO count as separate memberships when they canonicalize to
    // something different — so a DJ-mix-album track keeps showing under its own credited artist
    // AND under the compiling artist's page (verified live: Orbital's & P.O.D.'s tracks on The
    // Crystal Method's "Community Service" mix CD now correctly show under both). This used to be
    // a strict priority chain (tag, else album_artist, else path) instead — safer against phantom
    // duplicate artists from a wrong path guess, but it meant a compilation/mix track could ONLY
    // ever show under its own tag, never under the album it's actually part of. The phantom-
    // duplicate risk that priority-chaining was protecting against is handled at the source now
    // (inferAlbumArtistFromPath's disc-folder/year-album/bracket-annotation fixes above), so it's
    // safe to let all three signals contribute again. Album grouping ([albums]) is unaffected —
    // it already prefers album_artist/path independently of this.
    let { all ->
    // Every canonical artist key that a real TAG (artist / album_artist) vouches for. The folder-
    // inferred name may only ADD a membership when it resolves to one of these — it can confirm a
    // compilation's compiling artist, it can never invent a brand-new artist out of a folder name.
    // (Untagged tracks are the one exception below: with no tag at all, the folder is all we have.)
    val knownKeys = HashSet<String>(all.size / 4 + 16)
    for (tr in all) {
        val a = normalizeArtistName(tr.artist)
        if (!isBlankArtistTag(a)) knownKeys.add(canonicalArtistKey(a, ctx))
        val aa = tr.albumArtist.trim()
        if (aa.isNotBlank()) { val n = normalizeArtistName(aa); if (!isBlankArtistTag(n)) knownKeys.add(canonicalArtistKey(n, ctx)) }
    }
    all.flatMap { tr ->
        val list = mutableListOf<Pair<String, Track>>()
        val art = normalizeArtistName(tr.artist)
        val artBlank = isBlankArtistTag(art)
        if (!artBlank) list.add(art to tr)

        val albArtRaw = tr.albumArtist.trim()
        val albArt = if (albArtRaw.isNotBlank()) normalizeArtistName(albArtRaw) else ""
        val albArtBlank = isBlankArtistTag(albArt)
        if (!albArtBlank && (artBlank || canonicalArtistKey(albArt, ctx) != canonicalArtistKey(art, ctx))) {
            list.add(albArt to tr)
        }

        val pathArtist = inferAlbumArtistFromPath(tr.path, tr.album)
        if (!pathArtist.isNullOrBlank()) {
            val normPathArt = normalizeArtistName(pathArtist)
            if (!isBlankArtistTag(normPathArt)) {
                val pathKey = canonicalArtistKey(normPathArt, ctx)
                val untagged = artBlank && albArtBlank
                val confirmsKnown = pathKey in knownKeys
                if ((untagged || confirmsKnown) &&
                    (artBlank || pathKey != canonicalArtistKey(art, ctx)) &&
                    (albArtBlank || pathKey != canonicalArtistKey(albArt, ctx))
                ) {
                    list.add(normPathArt to tr)
                }
            }
        }
        if (list.isEmpty()) list.add("Unknown Artist" to tr)
        list
    }
    }
    .groupBy { (artistName, _) -> canonicalArtistKey(artistName, ctx) }
    .map { (_, pairs) ->
        val tracks = pairs.map { it.second }.distinctBy { it.id }.sortedWith(
            compareBy<Track> { it.album.lowercase() }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
        val rawDisplay = pairs.map { it.first }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: tracks.first().artist
        val englishName = ArtistTransliterationStore.getEnglishName(ctx, rawDisplay)
        val formattedDisplay = formatArtistDisplayName(rawDisplay, displayMode, englishName)
        ArtistGroup(formattedDisplay, tracks)
    }
    .sortedBy { formatArtistSortKey(it.name, ignoreThe, ctx) }

fun sortAlbumTracks(tracks: List<Track>): List<Track> {
    return tracks.sortedWith(
        compareBy<Track> { tr -> if (tr.discNumber > 0) tr.discNumber else 1 }
        .thenBy { tr ->
            if (tr.trackNumber > 0) tr.trackNumber else Int.MAX_VALUE
        }
        .thenBy { tr ->
            // Fallback to filename if track numbers are missing or identical
            if (tr.path.isNotBlank()) java.io.File(tr.path).name.lowercase() else tr.title.lowercase()
        }
        .thenBy { it.title.lowercase() }
    )
}

// A real album name essentially never starts with a bare leading track number ("01 This And
// That", "04. Some Song") — that pattern is the signature of a bad rip/tag write where the
// ALBUM field ended up holding a filename instead of the actual album title. Caught live: albums
// list polluted with these as their own bogus one-off "albums".
private val ALBUM_LOOKS_LIKE_FILENAME_RE = Regex("^(\\d{1,3})[\\s.\\-_]+")

/** Best-effort clean album name from a parent folder when the tag itself looks broken — strips a
 *  leading "Artist - " prefix (common flat-folder naming) and any trailing "(Year) [tags]" junk,
 *  same idea as the artist path-inference logic already applied elsewhere. */
private fun albumNameFromFolder(path: String): String? {
    if (path.isBlank()) return null
    val parent = java.io.File(path).parentFile?.name ?: return null
    var name = parent.trim()
    if (DISC_FOLDER_RE.matches(name)) {
        // A disc-number subfolder holds no album name of its own — climb one more level.
        name = java.io.File(path).parentFile?.parentFile?.name?.trim() ?: return null
    }
    if (" - " in name) name = name.substringAfter(" - ")
    name = TRAILING_BRACKET_RE.replace(name, "").trim()
    return name.takeIf { it.isNotBlank() && !ALBUM_LOOKS_LIKE_FILENAME_RE.containsMatchIn(it) }
}

/** The folder an album's files live in, with any "CD 1"/"Disc 2" subfolder collapsed away — so a
 *  multi-disc set keyed by folder is ONE album, not one per disc. */
private fun albumFolderKey(path: String): String {
    if (path.isBlank()) return ""
    var dir = java.io.File(path).parentFile ?: return path.lowercase()
    var hops = 0
    while (hops < 3 && DISC_FOLDER_RE.matches(dir.name.trim())) { dir = dir.parentFile ?: break; hops++ }
    return dir.absolutePath.lowercase()
}

/** Normalized album-title half of an identity key: copy-suffix stripped ("[2132]", "(1)", "copy"),
 *  accent-folded, punctuation-stripped, lowercase. */
private fun albumTitleKey(album: String): String {
    var clean = ALBUM_COPY_SUFFIX_RE.replace(album.trim(), "").trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    return WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
}

fun List<Track>.albums(
    ctx: android.content.Context? = null,
    ignoreThe: Boolean = true
): List<AlbumGroup> =
    // Album identity is TAG-first now, folder-second. The old key was purely the parent folder,
    // which is exactly what an ingest pipeline dumping releases into flat/scene-named/per-disc
    // folders breaks: one boxset became ten "albums" (one per "Disc N" folder), duplicate copies in
    // "[2132]"-suffixed folders became separate tiles, and a single album split across two drops
    // showed up twice. Now:
    //   • album tag + album_artist tag present → key = albumArtist :: albumTitle (folder-independent;
    //     a release tag on the folder — "[Vinyl 24-192]" vs "[Master]" — is appended so two
    //     different pressings of the same title stay distinct tiles),
    //   • album tag present, no album_artist → key = albumTitle :: disc-collapsed folder (a VA
    //     compilation mustn't fragment by per-track artist, and two different "Greatest Hits" by
    //     different unnamed artists mustn't merge — the folder is the tiebreaker),
    //   • no album tag at all → the disc-collapsed folder, as before.
    groupBy { tr ->
        val titleKey = albumTitleKey(tr.album)
        val folderKey = albumFolderKey(tr.path)
        val folderName = if (folderKey.isNotBlank()) java.io.File(folderKey).name else ""
        val pressing = releaseTag(folderName) ?: releaseTag(tr.album) ?: ""
        val albArt = tr.albumArtist.trim()
        when {
            titleKey.isBlank() -> if (folderKey.isNotBlank()) "dir:$folderKey" else "id:${tr.albumId}"
            albArt.isNotBlank() && !isBlankArtistTag(albArt) ->
                "tag:" + canonicalArtistKey(normalizeArtistName(albArt), ctx) + "::" + titleKey + "::" + pressing
            else -> "dir:$folderKey::$titleKey::$pressing"
        }
    }
    .map { (_, tracks) ->
        val sortedTracks = sortAlbumTracks(tracks)
        val firstTrack = sortedTracks.first()

        // 1. Tagged Album Artist
        val taggedAlbumArt = tracks.firstOrNull { it.albumArtist.isNotBlank() }?.albumArtist
        
        // 2. Folder Hierarchy Inferred Artist
        val inferredArtist = tracks.mapNotNull { inferAlbumArtistFromPath(it.path, it.album) }.firstOrNull()

        val counts = tracks.map { normalizeArtistName(it.artist) }.filter { !isBlankArtistTag(it) }.groupingBy { it }.eachCount()
        val top = counts.maxByOrNull { it.value }
        // A clear per-track majority (≥ 70%) is the album's artist — it beats folder inference, which
        // is only a guess about how the files were dropped on the card, not what they are.
        val majority = top?.takeIf { it.value * 10 >= tracks.size * 7 }?.key
        val artistDisplay = when {
            !taggedAlbumArt.isNullOrBlank() && !isBlankArtistTag(taggedAlbumArt) -> normalizeArtistName(taggedAlbumArt)
            majority != null -> majority
            !inferredArtist.isNullOrBlank() && !isBlankArtistTag(inferredArtist) -> normalizeArtistName(inferredArtist)
            else -> {
                if (top != null && top.value * 10 >= tracks.size * 4) {
                    top.key
                } else if (tracks.size > 2) {
                    "Various Artists"
                } else {
                    val fallback = tracks.firstOrNull { !isBlankArtistTag(it.artist) }?.artist ?: firstTrack.artist
                    if (isBlankArtistTag(fallback)) "Unknown Artist" else fallback
                }
            }
        }

        // Attach resolved albumArtist to all tracks in this album if missing
        val finalTracks = sortedTracks.map { tr ->
            if (tr.albumArtist.isBlank()) tr.copy(albumArtist = artistDisplay) else tr
        }

        // If the tag itself looks like a mistagged filename ("01 This And That"), prefer a name
        // recovered from the folder it actually lives in — same "don't trust a metadata field
        // that's clearly wrong" principle as the bit-depth/year/artist fallbacks elsewhere.
        val rawAlbumName = firstTrack.album.trim()
        val displayName = if (rawAlbumName.isNotBlank() && !ALBUM_LOOKS_LIKE_FILENAME_RE.containsMatchIn(rawAlbumName)) {
            rawAlbumName
        } else {
            albumNameFromFolder(firstTrack.path) ?: rawAlbumName.ifBlank { "Unknown album" }
        }
        AlbumGroup(displayName, artistDisplay, finalTracks)
    }
    .let { mergeDuplicateAlbumCopies(it) }
    .sortedBy { formatArtistSortKey(it.name, ignoreThe, ctx) }

/** Strip folder-copy noise from an album title so duplicate rips collapse: a trailing "[1234]",
 *  "(1)", "copy", or "- copy" that an ingest/dedup appended, plus case/whitespace. The album TAG is
 *  usually clean ("Something Wild") while only the FOLDER carries the suffix, but normalize the
 *  title too in case the name was recovered from a suffixed folder. */
private val ALBUM_COPY_SUFFIX_RE =
    Regex("""\s*(?:\[[0-9]{1,6}\]|\((?:disc\s*)?[0-9]{1,3}\)|-?\s*copy(?:\s*[0-9]+)?)\s*$""", RegexOption.IGNORE_CASE)
private fun normalizeAlbumTitleForDedup(name: String): String =
    name.trim().let { ALBUM_COPY_SUFFIX_RE.replace(it, "") }.trim().lowercase()

/** Merge album groups that are the SAME release duplicated across folders — e.g. an ingest that
 *  copied "Something Wild", "Something Wild [2312]", "Something Wild [2313]". The base grouping is
 *  folder-keyed (which correctly separates distinct albums) but that also splits genuine duplicate
 *  copies into 3 tiles; this collapses copies sharing the same normalized album name + album artist
 *  into one, deduping tracks by track-number + title so each song appears once. Distinct albums that
 *  merely share a name are NOT merged unless the album artist also matches. */
private fun mergeDuplicateAlbumCopies(groups: List<AlbumGroup>): List<AlbumGroup> {
    if (groups.size < 2) return groups
    val byKey = LinkedHashMap<String, MutableList<AlbumGroup>>()
    for (g in groups) {
        val key = normalizeAlbumTitleForDedup(g.name) + " " + g.artist.trim().lowercase()
        byKey.getOrPut(key) { mutableListOf() }.add(g)
    }
    return byKey.values.map { copies ->
        if (copies.size == 1) copies[0]
        else {
            val primary = copies.maxByOrNull { it.tracks.size } ?: copies[0]
            val mergedTracks = sortAlbumTracks(
                copies.flatMap { it.tracks }
                    .distinctBy { "${it.discNumber}/${it.trackNumber} ${it.title.trim().lowercase()}" }
            )
            AlbumGroup(primary.name, primary.artist, mergedTracks)
        }
    }
}

// ---- Miku palette ----
val MikuTeal = Color(0xFF39C5BB)
val MikuTealBright = Color(0xFF7FE6DE)
val MikuPink = Color(0xFFFF5FA2)
val MikuGold = Color(0xFFFFCF6B)
val Ground = Color(0xFF04161A)
val Surface1 = Color(0xFF0C2B2E)
val Muted = Color(0xFF89ACA7)

/** Dynamic per-level metric color: hotter = better. */
fun bitrateColor(kbps: Int): Color = when {
    kbps <= 0 -> Muted
    kbps < 320 -> Color(0xFF7E8C8A)      // lossy
    kbps < 1000 -> MikuTeal              // lossless-ish
    kbps < 2500 -> MikuTealBright        // hi-res
    else -> MikuGold                     // top-tier / DSD-class
}

// Recognizes the pressing/edition disambiguation tags an ingest pipeline stamps into an album's
// own title/folder when the SAME album exists in more than one form — "Led Zeppelin IV [Vinyl
// 24-192]" vs "[Master 24-192]", "Jagged Little Pill (Collector Edition)", "Complete Discography
// (Audiophile Vinyl FLAC)". First match wins, checked most-specific-first so e.g. "Audiophile
// Vinyl" doesn't just register as plain "Vinyl".
private val RELEASE_TAG_PATTERNS = listOf(
    Regex("(?i)audiophile\\s+vinyl") to "AUDIOPHILE VINYL",
    Regex("(?i)\\bvinyl\\b") to "VINYL RIP",
    Regex("(?i)studio\\s+master") to "STUDIO MASTER",
    Regex("(?i)\\bmaster\\b") to "MASTER",
    Regex("(?i)\\bstudio\\b") to "STUDIO",
    Regex("(?i)collector'?s?\\s+edition") to "COLLECTOR'S ED.",
    Regex("(?i)anniversary\\s+edition") to "ANNIVERSARY ED.",
    Regex("(?i)\\bdeluxe\\b") to "DELUXE",
    Regex("(?i)\\bremaster(ed)?\\b") to "REMASTER",
    Regex("(?i)\\bbootleg\\b") to "BOOTLEG",
)

/** The pressing/edition tag for an album, if its title/folder carries one — null for a plain
 *  single-release album (the common case; most albums don't need this). */
fun releaseTag(albumName: String): String? =
    RELEASE_TAG_PATTERNS.firstOrNull { (re, _) -> re.containsMatchIn(albumName) }?.second

/** Color for a release-tag chip — a distinct violet/lavender family, separate from every other
 *  badge dimension's palette (bit-depth teal/gold/pink, sample-rate silver/sky/violet/red, format
 *  green) so a "which pressing is this" tag never gets mistaken for a quality-tier badge. */
val ReleaseTagColor = Color(0xFFCE93D8)

/** Whole-CD image rip badge — warm amber, distinct from every quality/pressing badge family. */
val DiscImageColor = Color(0xFFFFB74D)

fun formatColor(mime: String): Color = when (mime.substringAfterLast('/').lowercase()) {
    "flac", "x-flac", "wav", "x-wav", "dsd", "dff", "dsf" -> MikuTealBright
    "mp4", "m4a", "aac", "alac" -> MikuTeal
    "mpeg", "mp3", "ogg", "opus" -> Color(0xFF7E8C8A)
    else -> Muted
}

private val metaSpanCache = android.util.LruCache<Long, AnnotatedString>(1000)
private val metricSpanCache = android.util.LruCache<Long, AnnotatedString>(1000)

/** Primary line: Artist · Album · Year */
fun trackMetaSpan(t: Track, resolvedYear: Int? = null): AnnotatedString {
    val yr = (resolvedYear?.takeIf { it > 0 }) ?: (t.year.takeIf { it > 0 })
    val cacheKey = if (yr != null) t.id * 31 + yr else t.id
    metaSpanCache.get(cacheKey)?.let { return it }
    val res = buildAnnotatedString {
        fun sep() = withStyle(SpanStyle(color = Color(0xFF3A5450))) { append("  ·  ") }
        DiscImage.rowLabel(t)?.let { lbl ->
            withStyle(SpanStyle(color = DiscImageColor, fontWeight = FontWeight.Bold)) { append("💿 $lbl") }
            sep()
        }
        withStyle(SpanStyle(color = Color(0xFFD4E8E5), fontWeight = FontWeight.Normal)) { append(t.artist) }
        if (t.album.isNotBlank()) { sep(); withStyle(SpanStyle(color = Muted)) { append(t.album) } }
        if (yr != null) { sep(); withStyle(SpanStyle(color = MikuGold, fontWeight = FontWeight.SemiBold)) { append("$yr") } }
    }
    metaSpanCache.put(cacheKey, res)
    return res
}

/** Secondary line: Bitrate · Duration · Size · Format */
fun metricSpans(t: Track): AnnotatedString {
    metricSpanCache.get(t.id)?.let { return it }
    val res = buildAnnotatedString {
        fun sep() = withStyle(SpanStyle(color = Color(0xFF3A5450))) { append("  ·  ") }
        var added = false
        if (t.bitrateKbps > 0) {
            withStyle(SpanStyle(color = bitrateColor(t.bitrateKbps), fontWeight = FontWeight.Medium)) { append("${t.bitrateKbps} kbps") }
            added = true
        }
        val fmt = t.mime.substringAfterLast('/').uppercase()
        if (fmt.isNotEmpty()) {
            if (added) sep()
            withStyle(SpanStyle(color = formatColor(t.mime), fontWeight = FontWeight.SemiBold)) { append(fmt) }
            added = true
        }
        if (t.durationMs > 0) {
            if (added) sep()
            val s = t.durationMs / 1000
            withStyle(SpanStyle(color = Muted)) { append("${s / 60}:${(s % 60).toString().padStart(2, '0')}") }
            added = true
        }
        if (t.sizeBytes > 0) {
            if (added) sep()
            withStyle(SpanStyle(color = Muted)) { append("${"%.1f".format(t.sizeBytes / 1e6)} MB") }
        }
    }
    metricSpanCache.put(t.id, res)
    return res
}
