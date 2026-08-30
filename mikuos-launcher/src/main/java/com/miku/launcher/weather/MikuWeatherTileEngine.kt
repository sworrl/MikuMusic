package com.miku.launcher.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.miku.launcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * State + refresh policy for the Miku Weather tile.
 *
 * Battery-smart by design:
 *  - Location is a one-shot LocationManager.getLastKnownLocation() read (network → passive → gps).
 *    NO location updates are ever requested by this engine.
 *  - One fetch per [REFRESH_MS] (30 min) while the launcher is visible; manual refresh any time.
 *  - The last snapshot is cached as JSON in SharedPreferences and restored on cold start, so the
 *    tile shows real (possibly STALE — and clearly marked as such) data when offline. It never
 *    fabricates a reading.
 */
object MikuWeatherTileEngine {
    private const val TAG = "MikuWxTile"
    private const val PREFS = "miku_weather_tile"
    private const val K_SNAPSHOT = "snapshot_json"
    private const val K_UNITS = "units"
    private const val K_WINDY = "windy_key"
    private const val K_MAN_LAT = "manual_lat"
    private const val K_MAN_LON = "manual_lon"
    private const val K_MAN_NAME = "manual_name"
    private const val K_GEO_CACHE = "geo_name_"

    const val REFRESH_MS = 30 * 60 * 1000L
    /** Data older than this is flagged STALE in the tile (still shown — it is the last real reading). */
    const val STALE_MS = 90 * 60 * 1000L

    data class State(
        val snapshot: WxSnapshot? = null,
        val location: WxLocation? = null,
        val manualLocation: WxLocation? = null,
        val units: WxUnits = WxUnits.IMPERIAL,   // default °F / mph / in (user-switchable in the tile sheet)
        val windyKey: String = "",
        val windyKeyFromBuild: Boolean = false,
        val isRefreshing: Boolean = false,
        val lastError: String? = null,
        val lastAttemptMs: Long = 0L,
        val online: Boolean = true,
        val loaded: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private val refreshMutex = Mutex()
    @Volatile private var started = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Idempotent. Restores prefs + cached snapshot, then runs the 30-minute refresh loop. */
    fun ensureStarted(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            loadPrefs(app)
            loopJob = launch {
                while (isActive) {
                    runCatching { com.miku.launcher.ui.MikuPowerProfile.awaitVisible() }
                    val s = _state.value
                    val now = System.currentTimeMillis()
                    val age = now - (s.snapshot?.fetchedMs ?: 0L)
                    val sinceAttempt = now - s.lastAttemptMs
                    // Due when the data is old; back off failed attempts to every 5 minutes.
                    if (age >= REFRESH_MS && sinceAttempt >= 5 * 60_000L) refreshNow(app)
                    delay(60_000L)
                }
            }
        }
    }

    private fun loadPrefs(app: Context) {
        val p = prefs(app)
        val units = runCatching { WxUnits.valueOf(p.getString(K_UNITS, WxUnits.IMPERIAL.name) ?: WxUnits.IMPERIAL.name) }.getOrDefault(WxUnits.IMPERIAL)
        val storedKey = p.getString(K_WINDY, null)
        val buildKey = BuildConfig.MIKU_WINDY_KEY
        val key = if (!storedKey.isNullOrBlank()) storedKey else buildKey
        val manual = if (p.contains(K_MAN_LAT) && p.contains(K_MAN_LON)) {
            WxLocation(
                lat = java.lang.Double.longBitsToDouble(p.getLong(K_MAN_LAT, 0L)),
                lon = java.lang.Double.longBitsToDouble(p.getLong(K_MAN_LON, 0L)),
                name = p.getString(K_MAN_NAME, "") ?: "",
                provider = "Manual"
            )
        } else null
        val snap = WxJson.decode(p.getString(K_SNAPSHOT, null))
        _state.update {
            it.copy(
                units = units,
                windyKey = key,
                windyKeyFromBuild = storedKey.isNullOrBlank() && buildKey.isNotBlank(),
                manualLocation = manual,
                snapshot = snap,
                location = it.location ?: manual ?: snap?.let { s -> WxLocation(s.lat, s.lon, s.placeName, "Cached") },
                loaded = true
            )
        }
    }

    // ------------------------------------------------------------------ user settings

    fun setUnits(ctx: Context, u: WxUnits) {
        prefs(ctx).edit().putString(K_UNITS, u.name).apply()
        _state.update { it.copy(units = u) }
    }

    /** Empty key = Open-Meteo only (and falls back to the build-time key if one was baked in). */
    fun setWindyKey(ctx: Context, key: String) {
        val trimmed = key.trim()
        prefs(ctx).edit().putString(K_WINDY, trimmed).apply()
        val buildKey = BuildConfig.MIKU_WINDY_KEY
        val effective = if (trimmed.isNotBlank()) trimmed else buildKey
        _state.update { it.copy(windyKey = effective, windyKeyFromBuild = trimmed.isBlank() && buildKey.isNotBlank()) }
        refresh(ctx)
    }

    fun setManualLocation(ctx: Context, lat: Double, lon: Double, name: String) {
        val label = name.ifBlank { String.format(Locale.US, "%.3f, %.3f", lat, lon) }
        prefs(ctx).edit()
            .putLong(K_MAN_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(K_MAN_LON, java.lang.Double.doubleToRawLongBits(lon))
            .putString(K_MAN_NAME, label)
            .apply()
        val loc = WxLocation(lat, lon, label, "Manual")
        _state.update { it.copy(manualLocation = loc, location = loc) }
        refresh(ctx)
    }

    fun clearManualLocation(ctx: Context) {
        prefs(ctx).edit().remove(K_MAN_LAT).remove(K_MAN_LON).remove(K_MAN_NAME).apply()
        _state.update { it.copy(manualLocation = null) }
        refresh(ctx)
    }

    // ------------------------------------------------------------------ refresh

    /** Manual refresh (tile/sheet button). Coalesces with an in-flight refresh. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        ensureStarted(app)
        scope.launch { refreshNow(app) }
    }

    private suspend fun refreshNow(app: Context) {
        if (!refreshMutex.tryLock()) return
        try {
            _state.update { it.copy(isRefreshing = true, lastAttemptMs = System.currentTimeMillis()) }

            val loc = resolveLocation(app)
            if (loc == null) {
                _state.update { it.copy(isRefreshing = false, lastError = "No location yet — set a city in the weather sheet") }
                return
            }
            _state.update { it.copy(location = loc) }

            if (!isOnline(app)) {
                _state.update { it.copy(isRefreshing = false, online = false, lastError = "Offline — showing last cached reading") }
                return
            }
            _state.update { it.copy(online = true) }

            // 1. Open-Meteo baseline (throws → whole refresh fails; cached snapshot stays as-is).
            var snap = MikuWeatherTileSources.fetchOpenMeteo(loc.lat, loc.lon)
            val notes = mutableListOf<String>()

            // 2. Real AQI from Open-Meteo air quality (null → "—", never a placeholder).
            val air = MikuWeatherTileSources.fetchOpenMeteoAir(loc.lat, loc.lon)
            if (air == null) notes.add("air quality unavailable")

            // 3. Windy point forecast when a key is present; Open-Meteo remains for daily/astro/AQI.
            val key = _state.value.windyKey
            if (key.isNotBlank()) {
                try {
                    val w = MikuWeatherTileSources.fetchWindy(loc.lat, loc.lon, key)
                    val om = snap
                    val hourly = w.hourly.map { h -> h.copy(isDay = om.isDayAt(h.epochMs)) }
                    val current = w.current.copy(
                        isDay = om.current.isDay ?: om.isDayAt(System.currentTimeMillis()),
                        uvIndex = om.current.uvIndex
                    )
                    val detail = buildString {
                        append("Windy Point Forecast v2 (${w.model.uppercase(Locale.US)}, 3-hourly) — current & hourly; ")
                        append("Open-Meteo — daily, sunrise/sunset, UV, AQI. Condition derived from GFS precip/cloud fields.")
                        if (!w.warning.isNullOrBlank()) append(" Windy: ${w.warning}")
                    }
                    snap = om.copy(current = current, hourly = hourly, source = "Windy·GFS", sourceDetail = detail)
                } catch (t: Throwable) {
                    Log.w(TAG, "Windy failed: ${t.message}")
                    notes.add("Windy failed (${t.message ?: "error"}) — Open-Meteo used")
                }
            }

            snap = snap.copy(
                placeName = loc.name,
                air = air,
                sourceDetail = if (notes.isEmpty()) snap.sourceDetail else snap.sourceDetail + " · " + notes.joinToString(" · ")
            )
            prefs(app).edit().putString(K_SNAPSHOT, WxJson.encode(snap)).apply()
            _state.update { it.copy(snapshot = snap, isRefreshing = false, lastError = null) }
        } catch (t: Throwable) {
            Log.w(TAG, "Weather refresh failed: ${t.message}")
            _state.update { it.copy(isRefreshing = false, lastError = t.message ?: t.javaClass.simpleName) }
        } finally {
            refreshMutex.unlock()
        }
    }

    // ------------------------------------------------------------------ location

    private fun isOnline(app: Context): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) {
        true // unknown → let the fetch decide
    }

    /**
     * Manual override → the Observatory's manual city (shared UX) → LocationManager last-known
     * (no update requests) → the Observatory's already-resolved fix → null (honest "no location").
     */
    @SuppressLint("MissingPermission")
    private suspend fun resolveLocation(app: Context): WxLocation? {
        _state.value.manualLocation?.let { return it }

        // Respect the Weather Observatory's manual city so the two surfaces agree.
        runCatching {
            val p = app.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
            if (p.getBoolean("manual_loc_enabled", false)) {
                val lat = java.lang.Double.longBitsToDouble(p.getLong("manual_lat", 0L))
                val lon = java.lang.Double.longBitsToDouble(p.getLong("manual_lon", 0L))
                if (lat != 0.0 || lon != 0.0) {
                    val city = p.getString("manual_city", "") ?: ""
                    val region = p.getString("manual_region", "") ?: ""
                    val name = listOf(city, region).filter { it.isNotBlank() }.joinToString(", ")
                    return WxLocation(lat, lon, name.ifBlank { String.format(Locale.US, "%.3f, %.3f", lat, lon) }, "Observatory manual")
                }
            }
        }

        // One-shot last-known read. Newest fix wins; no provider is asked for updates.
        var best: android.location.Location? = null
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                for (prov in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    val l = runCatching { lm.getLastKnownLocation(prov) }.getOrNull() ?: continue
                    if (best == null || l.time > best!!.time) best = l
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "last-known location read failed: ${t.message}")
        }
        best?.let { l ->
            val name = placeNameFor(app, l.latitude, l.longitude)
            return WxLocation(l.latitude, l.longitude, name, "Last known (${l.provider ?: "?"})")
        }

        // The Observatory may already hold a fix (persisted last-good / IP geolocation).
        val g = MikuWeatherService.state.value.gps
        if (g.isLocked && (g.latitude != 0.0 || g.longitude != 0.0)) {
            val name = listOf(g.city, g.state).filter { it.isNotBlank() && it != "Detecting Location..." }.joinToString(", ")
            return WxLocation(g.latitude, g.longitude, name.ifBlank { placeNameFor(app, g.latitude, g.longitude) }, g.provider)
        }
        return null
    }

    /** Reverse-geocode with a per-0.01° cache; falls back to "lat, lon" text — never a guessed city. */
    private suspend fun placeNameFor(app: Context, lat: Double, lon: Double): String {
        val key = K_GEO_CACHE + String.format(Locale.US, "%.2f,%.2f", lat, lon)
        val p = prefs(app)
        p.getString(key, null)?.let { if (it.isNotBlank()) return it }

        // If the Observatory already resolved a name for (roughly) this spot, reuse it.
        val g = MikuWeatherService.state.value.gps
        if (g.city.isNotBlank() && g.city != "Detecting Location..." &&
            kotlin.math.abs(g.latitude - lat) < 0.05 && kotlin.math.abs(g.longitude - lon) < 0.05
        ) {
            val n = listOf(g.city, g.state).filter { it.isNotBlank() }.joinToString(", ")
            p.edit().putString(key, n).apply()
            return n
        }

        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val addr = Geocoder(app, Locale.US).getFromLocation(lat, lon, 1)?.firstOrNull()
                val city = addr?.locality ?: addr?.subAdminArea ?: ""
                val region = addr?.adminArea ?: ""
                listOf(city, region).filter { it.isNotBlank() }.joinToString(", ")
            }.getOrDefault("")
        }
        if (resolved.isNotBlank()) {
            p.edit().putString(key, resolved).apply()
            return resolved
        }
        return String.format(Locale.US, "%.3f, %.3f", lat, lon)
    }
}
