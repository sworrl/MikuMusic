package com.miku.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Minimal Last.fm Audioscrobbler API 2.0 client: auth.getMobileSession (username/password → a
 * session key, done once at login), track.updateNowPlaying, track.scrobble, track.love/unlove.
 * Deliberately dependency-free — HttpURLConnection + org.json are both already on the Android
 * platform, so this adds zero new runtime dependencies beyond the separately-added encrypted-
 * prefs library ([[LastFmPreferences]]) used to store the resulting session key.
 *
 * Needs a free Last.fm API account (https://www.last.fm/api/account/create) — the key/secret are
 * NOT hardcoded here; they're read from BuildConfig, sourced from local.properties (gitignored,
 * never committed). If they're blank every call below is a silent no-op — see [isConfigured].
 */
object LastFm {
    private const val TAG = "LastFm"
    private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User-entered key/secret (Settings → Last.fm) win over the build-time local.properties
     *  values — see com.miku.player.scrobble.LastFmCredentials. */
    val isConfigured: Boolean
        get() = com.miku.player.scrobble.LastFmCredentials.isConfigured

    sealed class LoginResult {
        data class Success(val sessionKey: String, val username: String) : LoginResult()
        data class Failure(val message: String) : LoginResult()
    }

    /** One-shot: exchanges username+password for a session key. The password is used only in
     *  this call (as required by auth.getMobileSession) and is never persisted anywhere — only
     *  the returned session key is saved, via [LastFmPreferences]. */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext LoginResult.Failure("Last.fm isn't configured (missing API key)")
        if (username.isBlank() || password.isBlank()) return@withContext LoginResult.Failure("Enter a username and password")
        val params = sortedMapOf(
            "method" to "auth.getMobileSession",
            "username" to username.trim(),
            "password" to password,
            "api_key" to com.miku.player.scrobble.LastFmCredentials.apiKey()
        )
        try {
            val sig = sign(params)
            val json = post(params + mapOf("api_sig" to sig, "format" to "json"))
            val session = json.optJSONObject("session")
            if (session != null) {
                LoginResult.Success(session.getString("key"), session.getString("name"))
            } else {
                LoginResult.Failure(json.optString("message").ifBlank { "Login failed" })
            }
        } catch (e: Exception) {
            Log.w(TAG, "login failed", e)
            LoginResult.Failure(e.message ?: "Network error")
        }
    }

    /** Fire-and-forget "currently playing" ping — shown on the user's Last.fm profile immediately,
     *  does not itself count as a scrobble. */
    fun updateNowPlaying(sk: String, artist: String, track: String, album: String?, durationSec: Int?) {
        if (!isConfigured) return
        ioScope.launch {
            runCatching {
                val params = sortedMapOf(
                    "method" to "track.updateNowPlaying",
                    "artist" to artist,
                    "track" to track,
                    "api_key" to com.miku.player.scrobble.LastFmCredentials.apiKey(),
                    "sk" to sk
                )
                if (!album.isNullOrBlank()) params["album"] = album
                if (durationSec != null && durationSec > 0) params["duration"] = durationSec.toString()
                val sig = sign(params)
                post(params + mapOf("api_sig" to sig, "format" to "json"))
            }.onFailure { Log.w(TAG, "updateNowPlaying failed", it) }
        }
    }

    /** Fire-and-forget scrobble — call once per track, after Last.fm's own eligibility rule has
     *  been satisfied (see [[LastFmScrobbler]]: track >= 30s, played past min(50%, 4min)). */
    fun scrobble(sk: String, artist: String, track: String, album: String?, timestampSec: Long, durationSec: Int?) {
        if (!isConfigured) return
        ioScope.launch {
            runCatching {
                val params = sortedMapOf(
                    "method" to "track.scrobble",
                    "artist" to artist,
                    "track" to track,
                    "timestamp" to timestampSec.toString(),
                    "api_key" to com.miku.player.scrobble.LastFmCredentials.apiKey(),
                    "sk" to sk
                )
                if (!album.isNullOrBlank()) params["album"] = album
                if (durationSec != null && durationSec > 0) params["duration"] = durationSec.toString()
                val sig = sign(params)
                post(params + mapOf("api_sig" to sig, "format" to "json"))
            }.onFailure { Log.w(TAG, "scrobble failed", it) }
        }
    }

    /** Mirrors our own heart/like state onto Last.fm's "loved tracks" — best-effort, never blocks
     *  the local like toggle on network success. */
    fun setLoved(sk: String, artist: String, track: String, loved: Boolean) {
        if (!isConfigured) return
        ioScope.launch {
            runCatching {
                val params = sortedMapOf(
                    "method" to if (loved) "track.love" else "track.unlove",
                    "artist" to artist,
                    "track" to track,
                    "api_key" to com.miku.player.scrobble.LastFmCredentials.apiKey(),
                    "sk" to sk
                )
                val sig = sign(params)
                post(params + mapOf("api_sig" to sig, "format" to "json"))
            }.onFailure { Log.w(TAG, "love/unlove failed", it) }
        }
    }

    /** Last.fm's exact signing recipe: sort params alphabetically by name, concatenate as
     *  name+value with no separators, append the shared secret, MD5-hex the result. Must run
     *  BEFORE "format"/"api_sig" are added to the request — those two are excluded from signing. */
    private fun sign(params: Map<String, String>): String {
        val base = params.toSortedMap().entries.joinToString("") { (k, v) -> k + v } + com.miku.player.scrobble.LastFmCredentials.apiSecret()
        val digest = MessageDigest.getInstance("MD5").digest(base.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun post(params: Map<String, String>): JSONObject {
        val conn = URL(API_ROOT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
