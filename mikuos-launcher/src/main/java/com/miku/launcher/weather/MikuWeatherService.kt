package com.miku.launcher.weather
import com.miku.launcher.*

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hatsune Miku Real-Time GPS & Weather Observatory Engine.
 * Incorporates the complete multi-source meteorological pipeline from OnThe8s (RetroWeather):
 * 1. National Weather Service (api.weather.gov) station resolution, direct observations, & forecast lookaheads.
 * 2. Open-Meteo high-resolution hourly/daily meteograms & global satellite fallbacks.
 * 3. Look-ahead precipitation classifier ("RAIN IN 2 HRS", "TSTORM NOW", "SNOW IN 4 HRS").
 * 4. Astronomical Solar Arc calculations (sunrise/sunset, solar day progress, night tracking).
 * 5. Multi-source telemetry: Dew Point, Barometric Pressure, Cloud Cover, Visibility, AQI, UV Index.
 */
object MikuWeatherService {
    private const val TAG = "MikuWeatherService"
    private const val NWS_BASE = "https://api.weather.gov"

    data class GpsTelemetry(
        val isLocked: Boolean = false,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val altitudeM: Double = 0.0,
        val accuracyM: Float = 0f,
        val speedMph: Float = 0f,
        val bearing: Float = 0f,
        // A Location only sometimes carries speed/altitude/bearing (a NETWORK_PROVIDER fix usually
        // carries none). These mirror loc.hasSpeed()/hasAltitude()/hasBearing() so a consumer can tell
        // "measured 0" from "never measured" — otherwise a network fix renders a confident
        // "0.0 mph (0 km/h)" / "0 m altitude" for a quantity the radio never reported.
        val hasSpeed: Boolean = false,
        val hasAltitude: Boolean = false,
        val hasBearing: Boolean = false,
        val provider: String = "None",
        val lastFixTime: Long = 0L,
        // Empty = no fix / not resolved yet. Consumers render their own honest "Locating…" label;
        // nothing here pretends to be a resolved place.
        val city: String = "",
        val county: String = "",
        val state: String = "",
        val country: String = "",
        val fuzzyLocation: String = ""
    )

    // ---- Forecast models --------------------------------------------------------------------
    // Every default below is a NEUTRAL "no data" value (0 / blank), never a plausible reading.
    // A WeatherCondition is only real once lastUpdatedTime > 0 (set by a successful fetch);
    // every surface must gate on that instead of rendering these fields.
    data class HourlyPrecipPoint(
        val timeLabel: String = "",
        val precipInches: Float = 0f,
        val precipProbPct: Int = 0,
        val weatherCode: Int = 0,
        val tempF: Float = 0f,
        val summary: String = "",
        val icon: String = ""
    )

    data class HourlyMeteogramPoint(
        val timeLabel: String = "",
        val dayLabel: String = "",
        val tempF: Float = 0f,
        val feelsLikeF: Float = 0f,
        val precipInches: Float = 0f,
        val precipProbPct: Int = 0,
        val windSpeedMph: Float = 0f,
        val windGustsMph: Float = 0f,
        val windDirectionDeg: Int = 0,
        val windDirectionCompass: String = "",
        val weatherCode: Int = 0,
        val summary: String = "",
        val icon: String = "",
        val isDay: Boolean = true
    )

    data class DailyForecastPoint(
        val dayName: String = "",
        val dateFormatted: String = "",
        val maxTempF: Float = 0f,
        val minTempF: Float = 0f,
        val precipSumIn: Float = 0f,
        val precipProbMax: Int = 0,
        val maxWindSpeedMph: Float = 0f,
        val weatherCode: Int = 0,
        val summary: String = "",
        val icon: String = ""
    )

    data class MoonPhaseInfo(
        val phaseFraction: Float = 0f,
        val phaseName: String = "",
        val phaseIcon: String = "",
        val illuminationPct: Int = 0,
        val ageDays: Float = 0f
    )

    data class WeatherCondition(
        val code: Int = 0,
        val summary: String = "",
        val icon: String = "",
        val tempF: Float = 0f,
        val feelsLikeF: Float = 0f,
        val humidityPct: Int = 0,
        val windSpeedMph: Float = 0f,
        val windGustMph: Float = 0f,
        val windDirectionDeg: Int = 0,
        val windDirectionCompass: String = "",
        val precipitationIn: Float = 0f,
        val precipitationProbPct: Int = 0,
        val highTempF: Float = 0f,
        val lowTempF: Float = 0f,
        val dewPointF: Float = 0f,
        val pressureInHg: Float = 0f,
        val cloudCoverPct: Int = 0,
        val visibilityMiles: Float = 0f,
        val uvIndex: Float = 0f,
        val aqi: Int = -1,
        val aqiCategory: String = "",
        val sunrise: String = "",
        val sunset: String = "",
        val solarFraction: Float = 0f,
        val isDay: Boolean = true,
        // Moon phase is pure astronomy (computed from the clock), so it is real even before a fetch.
        val moonPhase: MoonPhaseInfo = calculateMoonPhase(),
        val nextPrecipLabel: String = "",
        val todayCond: String = "",
        val tomorrowDay: String = "",
        val tomorrowHi: Float = 0f,
        val tomorrowLo: Float = 0f,
        val tomorrowCond: String = "",
        val sourcesUsed: String = "",
        val hourlySparklineTemps: List<Int> = emptyList(),
        val nwsStationId: String = "",
        val severeWarning: String? = null,
        val lastUpdatedTime: Long = 0L,
        val hourlyPrecip6h: List<HourlyPrecipPoint> = emptyList(),
        val hourlyMeteogram: List<HourlyMeteogramPoint> = emptyList(),
        val dailyForecast: List<DailyForecastPoint> = emptyList()
    )

    fun calculateMoonPhase(timestamp: Long = System.currentTimeMillis()): MoonPhaseInfo {
        val synodicMonthMs = 29.530588853 * 86400000.0
        val diffMs = (timestamp - 1704974220000L).toDouble()
        val phase = ((diffMs % synodicMonthMs) + synodicMonthMs) % synodicMonthMs
        val phaseFrac = (phase / synodicMonthMs).toFloat()
        val ageDays = (phaseFrac * 29.530588853f)
        val illum = (0.5f * (1.0f - kotlin.math.cos(phaseFrac * 2.0 * Math.PI).toFloat()) * 100f).roundToInt()

        val (name, icon) = when {
            phaseFrac < 0.03f || phaseFrac >= 0.97f -> "New Moon" to "🌑"
            phaseFrac < 0.22f -> "Waxing Crescent" to "🌒"
            phaseFrac < 0.28f -> "First Quarter" to "🌓"
            phaseFrac < 0.47f -> "Waxing Gibbous" to "🌔"
            phaseFrac < 0.53f -> "Full Moon" to "🌕"
            phaseFrac < 0.72f -> "Waning Gibbous" to "🌖"
            phaseFrac < 0.78f -> "Last Quarter" to "🌗"
            else -> "Waning Crescent" to "🌘"
        }

        return MoonPhaseInfo(
            phaseFraction = phaseFrac,
            phaseName = name,
            phaseIcon = icon,
            illuminationPct = illum,
            ageDays = ageDays
        )
    }

    data class WeatherState(
        val gps: GpsTelemetry = GpsTelemetry(),
        val weather: WeatherCondition = WeatherCondition(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationJob: Job? = null
    private var weatherJob: Job? = null

    // Cache NWS endpoint routes
    private var cachedStationId: String? = null
    private var cachedForecastUrl: String? = null
    private var cachedHourlyForecastUrl: String? = null

    fun start(ctx: Context) {
        if (locationJob != null && weatherJob != null) return

        // Seed last-good forecast immediately so the widget/UI aren't blank before the first fetch.
        restoreLastWeather(ctx)
        startLocationTracking(ctx)

        if (weatherJob == null) {
            weatherJob = scope.launch {
                while (isActive) {
                    refreshWeather(ctx)
                    delay(8 * 60 * 1000L) // 8 minutes update cycle
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationTracking(ctx: Context, force: Boolean = false) {
        if (locationJob != null && !force) return
        locationJob?.cancel()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        locationJob = scope.launch {
            try {
                if (isManualLocationEnabled(ctx)) {
                    restoreLastGoodFix(ctx)
                    refreshWeather(ctx)
                    return@launch
                }

                // 1. Initial cached fallback
                var bestLoc: Location? = null
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach { prov ->
                    try {
                        val l = lm.getLastKnownLocation(prov)
                        if (l != null && (bestLoc == null || l.accuracy < (bestLoc?.accuracy ?: 9999f))) {
                            bestLoc = l
                        }
                    } catch (_: Throwable) {}
                }

                if (bestLoc != null) {
                    updateLocation(ctx, bestLoc!!)
                    refreshWeather(ctx)
                } else {
                    if (!restoreLastGoodFix(ctx)) {
                        fetchIpGeolocation(ctx)
                    }
                    refreshWeather(ctx)
                }

                // NOTE: deliberately NO permanent listener here. This used to register a
                // background HIGH_ACCURACY GPS listener (5 s / 5 m) for the launcher's whole life -
                // the GPS receiver ran 24/7 and was THE massive idle battery drain (found 2026-09-09).
                // Live GPS telemetry is ref-counted now (acquireLiveGps/releaseLiveGps) and runs only
                // while the GPS tactical map is open.
            } catch (t: Throwable) {
                Log.e(TAG, "Location tracking init failed", t)
            }
        }
    }

    // ---- Live GPS telemetry, ref-counted: only while a UI that shows it is open ----
    private var liveGpsListener: LocationListener? = null
    private var liveGpsRefs = 0

    fun acquireLiveGps(ctx: Context) {
        scope.launch(Dispatchers.Main) {
            liveGpsRefs++
            if (liveGpsListener != null) return@launch
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@launch
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { scope.launch { updateLocation(ctx, loc) } }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, listener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, listener)
                }
                liveGpsListener = listener
                Log.i(TAG, "live GPS acquired (refs=" + liveGpsRefs + ")")
            } catch (t: Throwable) { Log.w(TAG, "live GPS request failed: " + t.message) }
        }
    }

    fun releaseLiveGps(ctx: Context) {
        scope.launch(Dispatchers.Main) {
            liveGpsRefs = (liveGpsRefs - 1).coerceAtLeast(0)
            if (liveGpsRefs > 0) return@launch
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            liveGpsListener?.let { l -> runCatching { lm?.removeUpdates(l) } }
            liveGpsListener = null
            Log.i(TAG, "live GPS released")
        }
    }

    private fun updateLocation(ctx: Context, loc: Location) {
        val speedMph = loc.speed * 2.23694f
        val currentGps = _state.value.gps

        _state.value = _state.value.copy(
            gps = currentGps.copy(
                isLocked = true,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude,
                accuracyM = loc.accuracy,
                speedMph = speedMph,
                bearing = loc.bearing,
                hasSpeed = loc.hasSpeed(),
                hasAltitude = loc.hasAltitude(),
                hasBearing = loc.hasBearing(),
                provider = loc.provider ?: "GPS",
                lastFixTime = SystemClock.elapsedRealtime()
            )
        )

        // Asynchronously reverse geocode if location drifted > 2km or not yet resolved
        if (currentGps.city.isEmpty() || currentGps.city == "Detecting Location..." ||
            Math.abs(currentGps.latitude - loc.latitude) > 0.02 ||
            Math.abs(currentGps.longitude - loc.longitude) > 0.02) {
            reverseGeocode(ctx, loc.latitude, loc.longitude)
        }

        // A real GNSS/network fix is the best truth we ever get — persist it so future cold
        // starts (offline, indoors) begin from here instead of IP-geo guesses.
        saveLastGoodFix(ctx, loc.latitude, loc.longitude, currentGps.city, currentGps.state)
    }

    data class CitySearchResult(
        val name: String,
        val region: String,
        val country: String,
        val latitude: Double,
        val longitude: Double
    )

    fun isManualLocationEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
            .getBoolean("manual_loc_enabled", false)
    }

    fun setManualLocation(ctx: Context, lat: Double, lon: Double, city: String, region: String, country: String = "US") {
        ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE).edit()
            .putBoolean("manual_loc_enabled", true)
            .putLong("manual_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("manual_lon", java.lang.Double.doubleToRawLongBits(lon))
            .putString("manual_city", city)
            .putString("manual_region", region)
            .putString("manual_country", country)
            .apply()

        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                isLocked = true,
                latitude = lat,
                longitude = lon,
                city = city,
                state = region,
                country = country,
                provider = "Manual Override",
                // A typed-in place measures no kinematics — clear any carry-over from an earlier fix.
                hasSpeed = false, hasAltitude = false, hasBearing = false,
                lastFixTime = SystemClock.elapsedRealtime(),
                fuzzyLocation = buildFuzzyString("", city, region)
            )
        )
        refreshWeather(ctx)
    }

    fun clearManualLocation(ctx: Context) {
        ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE).edit()
            .putBoolean("manual_loc_enabled", false)
            .apply()
        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                provider = "Auto GPS",
                isLocked = false
            )
        )
        startLocationTracking(ctx, force = true)
    }

    fun searchCity(query: String, onResult: (List<CitySearchResult>) -> Unit) {
        scope.launch {
            if (query.trim().length < 2) {
                withContext(Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=en&format=json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val json = JSONObject(reader.readText())
                    reader.close()
                    val resultsArr = json.optJSONArray("results")
                    val list = mutableListOf<CitySearchResult>()
                    if (resultsArr != null) {
                        for (i in 0 until resultsArr.length()) {
                            val item = resultsArr.getJSONObject(i)
                            val name = item.optString("name", "")
                            val admin1 = item.optString("admin1", "")
                            val country = item.optString("country", "")
                            val lat = item.optDouble("latitude", 0.0)
                            val lon = item.optDouble("longitude", 0.0)
                            if (name.isNotEmpty() && lat != 0.0) {
                                list.add(CitySearchResult(name, admin1, country, lat, lon))
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onResult(list) }
                    return@launch
                }
            } catch (t: Throwable) {
                Log.w(TAG, "City search failed: ${t.message}")
            }
            withContext(Dispatchers.Main) { onResult(emptyList()) }
        }
    }

    /** Persist the last GOOD fix so a reboot/offline start never regresses to default coords. */
    private fun saveLastGoodFix(ctx: Context, lat: Double, lon: Double, city: String, region: String) {
        runCatching {
            ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE).edit()
                .putLong("last_lat", java.lang.Double.doubleToRawLongBits(lat))
                .putLong("last_lon", java.lang.Double.doubleToRawLongBits(lon))
                .putString("last_city", city)
                .putString("last_region", region)
                .apply()
        }
    }

    /**
     * Schema of the persisted snapshot. Bumped when the saved field set changes; a snapshot written
     * by an older schema is IGNORED on restore rather than partially applied (see below).
     */
    private const val WX_SNAPSHOT_SCHEMA = 2

    /**
     * Persist the last real condition so a cold start isn't blank.
     *
     * This used to persist only temp/summary/icon/severe — but [restoreLastWeather] republished the
     * result WITH its original lastUpdatedTime, which every surface in the app treats as "this is a
     * real reading". So after a restart the observatory confidently printed "▲ High 0°F ▼ Low 0°F",
     * "0 mph", "0 % humidity", "Dew Point 0°F", "0.00 inHg" and "UV 0" — the model's neutral
     * defaults, wearing a valid timestamp. The whole scalar snapshot is saved now.
     */
    private fun saveLastWeather(ctx: Context, c: WeatherCondition) {
        runCatching {
            ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE).edit()
                .putInt("wx_schema", WX_SNAPSHOT_SCHEMA)
                .putFloat("wx_tempf", c.tempF)
                .putString("wx_summary", c.summary)
                .putString("wx_icon", c.icon)
                .putString("wx_severe", c.severeWarning)
                .putLong("wx_updated", c.lastUpdatedTime)
                .putInt("wx_code", c.code)
                .putFloat("wx_feels", c.feelsLikeF)
                .putInt("wx_humidity", c.humidityPct)
                .putFloat("wx_wind", c.windSpeedMph)
                .putFloat("wx_gust", c.windGustMph)
                .putInt("wx_winddir", c.windDirectionDeg)
                .putString("wx_windcompass", c.windDirectionCompass)
                .putFloat("wx_precip_in", c.precipitationIn)
                .putInt("wx_precip_prob", c.precipitationProbPct)
                .putFloat("wx_high", c.highTempF)
                .putFloat("wx_low", c.lowTempF)
                .putFloat("wx_dew", c.dewPointF)
                .putFloat("wx_pressure", c.pressureInHg)
                .putInt("wx_cloud", c.cloudCoverPct)
                .putFloat("wx_vis", c.visibilityMiles)
                .putFloat("wx_uv", c.uvIndex)
                .putInt("wx_aqi", c.aqi)
                .putString("wx_aqicat", c.aqiCategory)
                .putString("wx_sunrise", c.sunrise)
                .putString("wx_sunset", c.sunset)
                .putString("wx_sources", c.sourcesUsed)
                .putString("wx_station", c.nwsStationId)
                .apply()
        }
    }

    /** Seed _state with the last persisted forecast so surfaces have real data before the first
     *  fetch of the session completes. No-op when nothing complete was saved yet. */
    fun restoreLastWeather(ctx: Context) {
        runCatching {
            val p = ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
            val updated = p.getLong("wx_updated", 0L)
            if (updated <= 0L) return
            // A pre-schema-2 snapshot only holds four fields. Restoring it would stamp a valid
            // lastUpdatedTime onto ~30 zeroed fields, which the UI would read as measurements —
            // so skip it entirely and wait for the first real fetch instead.
            if (p.getInt("wx_schema", 0) < WX_SNAPSHOT_SCHEMA) return
            // Don't clobber a fresher live condition already fetched this session.
            if (_state.value.weather.lastUpdatedTime >= updated) return
            val sunrise = p.getString("wx_sunrise", "") ?: ""
            val sunset = p.getString("wx_sunset", "") ?: ""
            // Re-derive the solar phase for RIGHT NOW from the saved astro times; null = the
            // snapshot had none, and the arc renders as unknown.
            val solar = calculateSolarProgress(sunrise, sunset)
            _state.value = _state.value.copy(
                weather = _state.value.weather.copy(
                    code = p.getInt("wx_code", 0),
                    tempF = p.getFloat("wx_tempf", 0f),
                    summary = p.getString("wx_summary", "") ?: "",
                    icon = p.getString("wx_icon", "") ?: "",
                    feelsLikeF = p.getFloat("wx_feels", 0f),
                    humidityPct = p.getInt("wx_humidity", 0),
                    windSpeedMph = p.getFloat("wx_wind", 0f),
                    windGustMph = p.getFloat("wx_gust", 0f),
                    windDirectionDeg = p.getInt("wx_winddir", 0),
                    windDirectionCompass = p.getString("wx_windcompass", "") ?: "",
                    precipitationIn = p.getFloat("wx_precip_in", 0f),
                    precipitationProbPct = p.getInt("wx_precip_prob", 0),
                    highTempF = p.getFloat("wx_high", 0f),
                    lowTempF = p.getFloat("wx_low", 0f),
                    dewPointF = p.getFloat("wx_dew", 0f),
                    pressureInHg = p.getFloat("wx_pressure", 0f),
                    cloudCoverPct = p.getInt("wx_cloud", 0),
                    visibilityMiles = p.getFloat("wx_vis", 0f),
                    uvIndex = p.getFloat("wx_uv", 0f),
                    aqi = p.getInt("wx_aqi", -1),
                    aqiCategory = p.getString("wx_aqicat", "") ?: "",
                    sunrise = sunrise,
                    sunset = sunset,
                    solarFraction = solar?.first ?: 0f,
                    isDay = solar?.second ?: true,
                    sourcesUsed = p.getString("wx_sources", "") ?: "",
                    nwsStationId = p.getString("wx_station", "") ?: "",
                    severeWarning = p.getString("wx_severe", null),
                    lastUpdatedTime = updated
                )
            )
        }
    }

    /** Restore the persisted fix; returns false when none saved yet. */
    fun restoreLastGoodFix(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
        if (p.getBoolean("manual_loc_enabled", false)) {
            val lat = java.lang.Double.longBitsToDouble(p.getLong("manual_lat", 0L))
            val lon = java.lang.Double.longBitsToDouble(p.getLong("manual_lon", 0L))
            val city = p.getString("manual_city", "") ?: ""
            val region = p.getString("manual_region", "") ?: ""
            val country = p.getString("manual_country", "US") ?: "US"
            if (lat != 0.0 || lon != 0.0) {
                _state.value = _state.value.copy(
                    gps = _state.value.gps.copy(
                        isLocked = true,
                        latitude = lat,
                        longitude = lon,
                        city = city,
                        state = region,
                        country = country,
                        provider = "Manual",
                        hasSpeed = false, hasAltitude = false, hasBearing = false,
                        lastFixTime = SystemClock.elapsedRealtime(),
                        fuzzyLocation = buildFuzzyString("", city, region)
                    )
                )
                return true
            }
        }
        if (!p.contains("last_lat")) return false
        val lat = java.lang.Double.longBitsToDouble(p.getLong("last_lat", 0L))
        val lon = java.lang.Double.longBitsToDouble(p.getLong("last_lon", 0L))
        if (lat == 0.0 && lon == 0.0) return false
        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                isLocked = true,
                latitude = lat,
                longitude = lon,
                city = p.getString("last_city", "") ?: "",
                state = p.getString("last_region", "") ?: "",
                provider = "Last Known",
                hasSpeed = false, hasAltitude = false, hasBearing = false,
                lastFixTime = SystemClock.elapsedRealtime(),
                fuzzyLocation = buildFuzzyString("", p.getString("last_city", "") ?: "", p.getString("last_region", "") ?: "")
            )
        )
        return true
    }

    private fun fetchIpGeolocation(ctx: Context) {
        // Fallback CHAIN — if manual location is set, do not query IP-geo
        if (isManualLocationEnabled(ctx)) return

        data class GeoApi(val url: String, val latKey: String, val lonKey: String, val cityKey: String, val regionKey: String, val countryKey: String)
        val providers = listOf(
            GeoApi("http://ip-api.com/json/", "lat", "lon", "city", "regionName", "country"),
            GeoApi("https://ipapi.co/json/", "latitude", "longitude", "city", "region", "country_name"),
            GeoApi("https://ipwho.is/", "latitude", "longitude", "city", "region", "country")
        )
        for (api in providers) {
            try {
                val conn = (URL(api.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                }
                if (conn.responseCode != 200) continue
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = JSONObject(reader.readText())
                reader.close()

                val lat = json.optDouble(api.latKey, 0.0)
                val lon = json.optDouble(api.lonKey, 0.0)
                if (lat == 0.0 && lon == 0.0) continue
                val city = json.optString(api.cityKey, "")
                val region = json.optString(api.regionKey, "")
                val country = json.optString(api.countryKey, "")

                val fuzzy = buildFuzzyString("", city, region)

                _state.value = _state.value.copy(
                    gps = _state.value.gps.copy(
                        isLocked = true,
                        latitude = lat,
                        longitude = lon,
                        city = city,
                        state = region,
                        country = country,
                        provider = "IP Geolocation",
                        hasSpeed = false, hasAltitude = false, hasBearing = false,
                        lastFixTime = SystemClock.elapsedRealtime(),
                        fuzzyLocation = fuzzy
                    )
                )
                saveLastGoodFix(ctx, lat, lon, city, region)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "IP geolocation via ${api.url} failed: ${t.message}")
            }
        }
        Log.w(TAG, "All IP geolocation providers failed")
    }

    private fun reverseGeocode(ctx: Context, lat: Double, lon: Double) {
        scope.launch {
            var resolvedCity = ""
            var resolvedCounty = ""
            var resolvedState = ""
            var resolvedCountry = ""

            // 1. Android Geocoder
            try {
                val geocoder = android.location.Geocoder(ctx, Locale.US)
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    resolvedCity = addr.locality ?: addr.subAdminArea ?: ""
                    resolvedCounty = addr.subAdminArea ?: ""
                    resolvedState = addr.adminArea ?: ""
                    resolvedCountry = addr.countryName ?: ""
                }
            } catch (_: Throwable) {}

            // 2. Open-Meteo / Nominatim reverse geocoding fallback
            if (resolvedCity.isEmpty()) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=10")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                    }
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val json = JSONObject(reader.readText())
                        reader.close()
                        val address = json.optJSONObject("address")
                        if (address != null) {
                            resolvedCity = address.optString("city", address.optString("town", address.optString("village", "")))
                            resolvedCounty = address.optString("county", "")
                            resolvedState = address.optString("state", "")
                            resolvedCountry = address.optString("country", "")
                        }
                    }
                } catch (_: Throwable) {}
            }

            val currentGps = _state.value.gps
            val finalCity = if (resolvedCity.isNotEmpty()) resolvedCity else currentGps.city
            val finalCounty = if (resolvedCounty.isNotEmpty()) resolvedCounty else currentGps.county
            val finalState = if (resolvedState.isNotEmpty()) resolvedState else currentGps.state
            val fuzzy = buildFuzzyString(finalCounty, finalCity, finalState)

            _state.value = _state.value.copy(
                gps = _state.value.gps.copy(
                    city = finalCity,
                    county = finalCounty,
                    state = finalState,
                    country = if (resolvedCountry.isNotEmpty()) resolvedCountry else currentGps.country,
                    fuzzyLocation = fuzzy
                )
            )
        }
    }

    private fun buildFuzzyString(county: String, city: String, state: String): String {
        val parts = mutableListOf<String>()
        if (county.isNotEmpty()) {
            val cleanCounty = if (county.contains("County", ignoreCase = true)) county else "$county Co."
            parts.add(cleanCounty)
        }
        if (city.isNotEmpty() && !city.equals(county, ignoreCase = true)) {
            parts.add(city)
        }
        if (state.isNotEmpty()) {
            parts.add(state)
        }
        return if (parts.isNotEmpty()) parts.joinToString(", ") else "Local Station"
    }

    /**
     * Unified OnThe8s Dual Meteorological Engine:
     * Combines National Weather Service (NWS) direct station observations with Open-Meteo hourly meteogram models.
     */
    fun refreshWeather(ctx: Context) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            var lat = _state.value.gps.latitude
            var lon = _state.value.gps.longitude

            // Resolve coordinates: live fix → persisted last-good fix → IP geolocation chain.
            // NEVER silently fall back to a hardcoded city — that's how the widget ended up
            // confidently showing Albuquerque weather. With nothing resolvable we skip this
            // cycle and show "locating…" instead of someone else's forecast.
            if (lat == 0.0 && lon == 0.0) {
                if (restoreLastGoodFix(ctx)) {
                    lat = _state.value.gps.latitude
                    lon = _state.value.gps.longitude
                }
            }
            if (lat == 0.0 && lon == 0.0) {
                fetchIpGeolocation(ctx)
                lat = _state.value.gps.latitude
                lon = _state.value.gps.longitude
                if (lat == 0.0 && lon == 0.0) {
                    Log.w(TAG, "No location from any source — skipping weather cycle")
                    _state.value = _state.value.copy(isLoading = false)
                    return@launch
                }
            }

            try {
                // Step 1: Query Open-Meteo for high-res hourly meteogram + solar astro
                val omUrlStr = "https://api.open-meteo.com/v1/forecast?" +
                        "latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,cloud_cover" +
                        "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset" +
                        "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto"

                val omConn = (URL(omUrlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (omConn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(omConn.inputStream))
                    val json = JSONObject(reader.readText())
                    reader.close()

                    val current = json.optJSONObject("current")
                    val daily = json.optJSONObject("daily")
                    val hourly = json.optJSONObject("hourly")

                    // A missing temperature means we have no observation at all: publish nothing
                    // rather than the old `70.0` default, which reached the UI as a real reading
                    // (with a fresh lastUpdatedTime, so every freshness gate believed it).
                    val tempRaw = current?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
                    if (tempRaw.isNaN()) {
                        Log.w(TAG, "Open-Meteo current block has no temperature — skipping cycle")
                        _state.value = _state.value.copy(isLoading = false, error = "No current observation")
                        return@launch
                    }
                    var tempF = tempRaw.toFloat()
                    var feelsLike = current?.optDouble("apparent_temperature", tempF.toDouble())?.toFloat() ?: tempF
                    // 0 is this model's documented "never reported" value. These used to default to
                    // 45 % and 1013.25 hPa (= 29.92 inHg, the textbook standard atmosphere), which
                    // are plausible-looking numbers the station never sent.
                    var humidity = current?.optInt("relative_humidity_2m", 0) ?: 0
                    var wCode = current?.optInt("weather_code", 0) ?: 0
                    var windSpeed = current?.optDouble("wind_speed_10m", 0.0)?.toFloat() ?: 0f
                    var windDir = current?.optInt("wind_direction_10m", 0) ?: 0
                    val precipIn = current?.optDouble("precipitation", 0.0)?.toFloat() ?: 0f
                    val pressureHpa = current?.optDouble("surface_pressure", Double.NaN)?.toFloat() ?: Float.NaN
                    val pressureInHg = if (pressureHpa.isNaN()) 0f else pressureHpa * 0.02953f
                    val cloudCover = current?.optInt("cloud_cover", 0) ?: 0
                    var isDay = (current?.optInt("is_day", 1) ?: 1) == 1

                    // Sunrise and Sunset parsing from onthe8s. Blank = the daily block carried no
                    // astro; they used to be pre-seeded with a fictional 06:00/20:00 day, which made
                    // the meteogram's `sunrise.isNotBlank()` gate impossible to trip and drew a solar
                    // arc, a RISE/SET pair and a sun marker out of nothing.
                    var sunriseStr = ""
                    var sunsetStr = ""
                    val srRaw = daily?.optJSONArray("sunrise")?.optString(0) ?: ""
                    val ssRaw = daily?.optJSONArray("sunset")?.optString(0) ?: ""
                    if (srRaw.isNotEmpty() && ssRaw.isNotEmpty()) {
                        val astroParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                        val astroFmt = SimpleDateFormat("h:mm a", Locale.US)
                        try {
                            sunriseStr = astroFmt.format(astroParser.parse(srRaw) ?: Date())
                            sunsetStr = astroFmt.format(astroParser.parse(ssRaw) ?: Date())
                        } catch (_: Throwable) {}
                    }

                    // Null = astro unknown. isDay then stays the radio-honest Open-Meteo `is_day`
                    // instead of the old hardcoded "0.5 of the day, and it's daytime".
                    val solarResult = calculateSolarProgress(sunriseStr, sunsetStr)
                    val solarFraction = solarResult?.first ?: 0f
                    isDay = solarResult?.second ?: isDay

                    val maxTemps = daily?.optJSONArray("temperature_2m_max")
                    val minTemps = daily?.optJSONArray("temperature_2m_min")
                    // 0 = no daily block. These used to fall back to the CURRENT temperature, which
                    // printed "▲72° ▼72°" as a forecast range nobody forecast.
                    val highRaw = maxTemps?.optDouble(0, Double.NaN) ?: Double.NaN
                    val lowRaw = minTemps?.optDouble(0, Double.NaN) ?: Double.NaN
                    val highTemp = if (highRaw.isNaN()) 0f else highRaw.toFloat()
                    val lowTemp = if (lowRaw.isNaN()) 0f else lowRaw.toFloat()

                    val (summary, icon) = mapWeatherCode(wCode, isDay)
                    val compass = degreesToCompass(windDir)

                    // Step 2: Try NWS Station lookup & Live Observation (Direct from onthe8s WeatherDataCache)
                    var nwsStation = cachedStationId ?: ""
                    var nwsCondition = ""
                    val sourcesList = mutableListOf("Open-Meteo")

                    try {
                        if (cachedStationId == null || cachedForecastUrl == null) {
                            val nwsPts = httpGetJson("$NWS_BASE/points/${String.format(Locale.US, "%.4f", lat)},${String.format(Locale.US, "%.4f", lon)}")
                            val props = nwsPts?.optJSONObject("properties")
                            cachedForecastUrl = props?.optString("forecast", "")?.ifEmpty { null }
                            cachedHourlyForecastUrl = props?.optString("forecastHourly", "")?.ifEmpty { null }
                            val obsStationsUrl = props?.optString("observationStations", "")?.ifEmpty { null }
                            if (obsStationsUrl != null) {
                                val stJson = httpGetJson(obsStationsUrl)
                                val features = stJson?.optJSONArray("features")
                                if (features != null && features.length() > 0) {
                                    val first = features.getJSONObject(0).optJSONObject("properties")
                                    cachedStationId = first?.optString("stationIdentifier", "")
                                    nwsStation = cachedStationId ?: ""
                                }
                            }
                        }

                        if (nwsStation.isNotEmpty()) {
                            val obsJson = httpGetJson("$NWS_BASE/stations/$nwsStation/observations/latest")
                            val obsProps = obsJson?.optJSONObject("properties")
                            if (obsProps != null) {
                                val nwsTempC = obsProps.optJSONObject("temperature")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsTempC.isNaN()) {
                                    tempF = (nwsTempC * 9.0 / 5.0 + 32.0).toFloat()
                                    sourcesList.add(0, "NWS ($nwsStation)")
                                }
                                val nwsRh = obsProps.optJSONObject("relativeHumidity")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsRh.isNaN()) {
                                    humidity = nwsRh.roundToInt()
                                }
                                val nwsWs = obsProps.optJSONObject("windSpeed")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsWs.isNaN()) {
                                    windSpeed = (nwsWs * 0.621371).toFloat()
                                }
                                val nwsWd = obsProps.optJSONObject("windDirection")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsWd.isNaN()) {
                                    windDir = nwsWd.roundToInt()
                                }
                                nwsCondition = obsProps.optString("textDescription", "")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "NWS observation fetch skipped: ${e.message}")
                    }

                    val finalSummary = if (nwsCondition.isNotEmpty()) nwsCondition else summary

                    // Step 3: Hourly forecast look-ahead & Meteogram indexing starting from NOW
                    val hourlyTimes = hourly?.optJSONArray("time")
                    val hourlyPrecip = hourly?.optJSONArray("precipitation")
                    val hourlyPrecipProbs = hourly?.optJSONArray("precipitation_probability")
                    val hourlyCodes = hourly?.optJSONArray("weather_code")
                    val hourlyTemps = hourly?.optJSONArray("temperature_2m")
                    val hourlyFeels = hourly?.optJSONArray("apparent_temperature")
                    val hourlyDewPoints = hourly?.optJSONArray("dew_point_2m")
                    val hourlyWindSpeed = hourly?.optJSONArray("wind_speed_10m")
                    val hourlyWindGusts = hourly?.optJSONArray("wind_gusts_10m")
                    val hourlyWindDir = hourly?.optJSONArray("wind_direction_10m")
                    val hourlyVis = hourly?.optJSONArray("visibility")
                    val hourlyUv = hourly?.optJSONArray("uv_index")
                    val hourlyIsDay = hourly?.optJSONArray("is_day")

                    val precipList6h = mutableListOf<HourlyPrecipPoint>()
                    val meteogramList = mutableListOf<HourlyMeteogramPoint>()
                    val sparklineTemps = mutableListOf<Int>()
                    val totalHourlyPoints = hourlyTimes?.length() ?: 0

                    val inSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                    val dayHeaderSdf = SimpleDateFormat("EEEE d", Locale.US)
                    val hourSdf = SimpleDateFormat("HH:mm", Locale.US)

                    val nowMs = System.currentTimeMillis()
                    var startIdx = 0

                    // Locate index of current hour so we don't start from midnight in the past
                    for (i in 0 until totalHourlyPoints) {
                        val rawTime = hourlyTimes?.optString(i, "") ?: ""
                        try {
                            val parsedDate = inSdf.parse(rawTime)
                            if (parsedDate != null && parsedDate.time >= nowMs - 3600000L) {
                                startIdx = i
                                break
                            }
                        } catch (_: Throwable) {}
                    }

                    // Look-ahead precipitation classifier from onthe8s
                    var nextPrecip = ""
                    // 0 = the hourly block never reported it (this model's neutral "no data").
                    // These used to be seeded with tempF-15 (an invented dew point), a flat 10 mi
                    // visibility and a flat UV 4, all of which reached the observatory as readings.
                    var dewPointVal = 0f
                    var visibilityVal = 0f
                    var uvVal = 0f
                    // Real measured gust. windGustMph used to be windSpeed * 1.35f — a multiplier,
                    // printed as "Gusts to N mph". Open-Meteo's wind_gusts_10m was already fetched.
                    var gustVal = 0f

                    for (i in startIdx until minOf(totalHourlyPoints, startIdx + 24)) {
                        val rawTime = hourlyTimes?.optString(i, "") ?: ""
                        var hourDisplay = "+${i - startIdx}h"
                        var dayHeaderDisplay = "Today"
                        var hourTimestamp = nowMs + (i - startIdx) * 3600000L
                        try {
                            val parsedDate = inSdf.parse(rawTime)
                            if (parsedDate != null) {
                                hourDisplay = hourSdf.format(parsedDate)
                                dayHeaderDisplay = dayHeaderSdf.format(parsedDate)
                                hourTimestamp = parsedDate.time
                            }
                        } catch (_: Throwable) {
                            if (rawTime.contains("T")) {
                                hourDisplay = rawTime.substringAfter("T").take(5)
                            }
                        }

                        val pIn = hourlyPrecip?.optDouble(i, 0.0)?.toFloat() ?: 0f
                        val prob = hourlyPrecipProbs?.optInt(i, 0) ?: 0
                        val code = hourlyCodes?.optInt(i, 0) ?: 0
                        // An hour with no temperature is skipped, not drawn at a hardcoded 70 °F.
                        val tRaw = hourlyTemps?.optDouble(i, Double.NaN) ?: Double.NaN
                        if (tRaw.isNaN()) continue
                        val tF = tRaw.toFloat()
                        val appTF = hourlyFeels?.optDouble(i, tF.toDouble())?.toFloat() ?: tF
                        val wSpd = hourlyWindSpeed?.optDouble(i, 0.0)?.toFloat() ?: 0f
                        // Was: gust defaulted to the wind SPEED, i.e. a gust reading invented from
                        // the mean wind. 0 = not reported.
                        val wGst = hourlyWindGusts?.optDouble(i, Double.NaN)?.toFloat()?.takeIf { !it.isNaN() } ?: 0f
                        val wDegree = hourlyWindDir?.optInt(i, 0) ?: 0
                        val ptIsDay = (hourlyIsDay?.optInt(i, 1) ?: 1) == 1
                        val (s, ic) = mapWeatherCode(code, ptIsDay)

                        if (i == startIdx) {
                            // Every one of these is now either the reported value or 0 ("not
                            // reported"). The old fallbacks were tempF-15 / 16000 m / UV 4, and the
                            // visibility was additionally clamped to 1..15 mi, which silently
                            // rewrote genuinely-reported values outside that band.
                            dewPointVal = hourlyDewPoints?.optDouble(i, Double.NaN)?.toFloat()?.takeIf { !it.isNaN() } ?: 0f
                            val vM = hourlyVis?.optDouble(i, Double.NaN) ?: Double.NaN
                            visibilityVal = if (vM.isNaN()) 0f else (vM / 1609.34).toFloat()
                            uvVal = hourlyUv?.optDouble(i, Double.NaN)?.toFloat()?.takeIf { !it.isNaN() } ?: 0f
                            gustVal = wGst
                        }

                        // Collect 8-point temperature sparkline
                        if (sparklineTemps.size < 8) {
                            sparklineTemps.add(tF.roundToInt())
                        }

                        // Look-ahead precipitation classifier logic
                        if (nextPrecip.isEmpty() && (prob >= 40 || pIn > 0.02f)) {
                            val hoursAway = ((hourTimestamp - nowMs) / 3600000L).toInt().coerceAtLeast(0)
                            val type = classifyPrecip(s)
                            nextPrecip = when (hoursAway) {
                                0 -> "$type NOW"
                                1 -> "$type IN 1 HR"
                                else -> "$type IN $hoursAway HRS"
                            }
                        }

                        if (precipList6h.size < 6) {
                            precipList6h.add(
                                HourlyPrecipPoint(
                                    timeLabel = if (precipList6h.isEmpty()) "NOW" else hourDisplay,
                                    precipInches = pIn,
                                    precipProbPct = prob,
                                    weatherCode = code,
                                    tempF = tF,
                                    summary = s,
                                    icon = ic
                                )
                            )
                        }

                        meteogramList.add(
                            HourlyMeteogramPoint(
                                timeLabel = hourDisplay,
                                dayLabel = dayHeaderDisplay,
                                tempF = tF,
                                feelsLikeF = appTF,
                                precipInches = pIn,
                                precipProbPct = prob,
                                windSpeedMph = wSpd,
                                windGustsMph = wGst,
                                windDirectionDeg = wDegree,
                                windDirectionCompass = degreesToCompass(wDegree),
                                weatherCode = code,
                                summary = s,
                                icon = ic,
                                isDay = ptIsDay
                            )
                        )
                    }

                    // Step 4: Daily Multi-Day Forecast parsing
                    val dailyList = mutableListOf<DailyForecastPoint>()
                    val dailyTimes = daily?.optJSONArray("time")
                    val dailyCodes = daily?.optJSONArray("weather_code")
                    val dailyMaxTemps = daily?.optJSONArray("temperature_2m_max")
                    val dailyMinTemps = daily?.optJSONArray("temperature_2m_min")
                    val dailyPrecipSums = daily?.optJSONArray("precipitation_sum")
                    val dailyPrecipProbMax = daily?.optJSONArray("precipitation_probability_max")
                    val dailyMaxWind = daily?.optJSONArray("wind_speed_10m_max")
                    val totalDailyDays = dailyTimes?.length() ?: 0

                    val dateParseSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val dayNameSdf = SimpleDateFormat("EEEE", Locale.US)
                    val shortDateSdf = SimpleDateFormat("MM/dd", Locale.US)

                    var tomorrowDName = "Tomorrow"
                    // 0 / blank = no day-1 row in the response. These used to fall back to TODAY's
                    // high/low and a flat "Clear", presented as tomorrow's forecast.
                    var tomorrowHiVal = 0f
                    var tomorrowLoVal = 0f
                    var tomorrowSummary = ""

                    for (d in 0 until totalDailyDays) {
                        val dStr = dailyTimes?.optString(d, "") ?: ""
                        var dName = "Day $d"
                        // Blank, not the hardcoded calendar date "08/18" this used to print when
                        // the daily timestamp failed to parse.
                        var dFormatted = ""
                        try {
                            val parsedDate = dateParseSdf.parse(dStr)
                            if (parsedDate != null) {
                                dName = if (d == 0) "Today" else dayNameSdf.format(parsedDate)
                                dFormatted = shortDateSdf.format(parsedDate)
                            }
                        } catch (_: Throwable) {}

                        val dCode = dailyCodes?.optInt(d, 0) ?: 0
                        // A day with no high/low is DROPPED rather than charted at a hardcoded
                        // 75/55 °F.
                        val dMaxRaw = dailyMaxTemps?.optDouble(d, Double.NaN) ?: Double.NaN
                        val dMinRaw = dailyMinTemps?.optDouble(d, Double.NaN) ?: Double.NaN
                        if (dMaxRaw.isNaN() || dMinRaw.isNaN()) continue
                        val dMax = dMaxRaw.toFloat()
                        val dMin = dMinRaw.toFloat()
                        val dPrecipSum = dailyPrecipSums?.optDouble(d, 0.0)?.toFloat() ?: 0f
                        val dProb = dailyPrecipProbMax?.optInt(d, 0) ?: 0
                        val dWind = dailyMaxWind?.optDouble(d, 0.0)?.toFloat() ?: 0f
                        val (s, ic) = mapWeatherCode(dCode, true)

                        if (d == 1) {
                            tomorrowDName = dName
                            tomorrowHiVal = dMax
                            tomorrowLoVal = dMin
                            tomorrowSummary = s
                        }

                        dailyList.add(
                            DailyForecastPoint(
                                dayName = dName,
                                dateFormatted = dFormatted,
                                maxTempF = dMax,
                                minTempF = dMin,
                                precipSumIn = dPrecipSum,
                                precipProbMax = dProb,
                                maxWindSpeedMph = dWind,
                                weatherCode = dCode,
                                summary = s,
                                icon = ic
                            )
                        )
                    }

                    // Severe Weather Alerts Check
                    val severeAlert = checkSevereAlerts(wCode, windSpeed, precipIn)

                    val condition = WeatherCondition(
                        code = wCode,
                        summary = finalSummary,
                        icon = icon,
                        tempF = tempF,
                        feelsLikeF = feelsLike,
                        humidityPct = humidity,
                        windSpeedMph = windSpeed,
                        windGustMph = gustVal,
                        windDirectionDeg = windDir,
                        windDirectionCompass = compass,
                        precipitationIn = precipIn,
                        precipitationProbPct = if (precipList6h.isNotEmpty()) precipList6h[0].precipProbPct else 0,
                        highTempF = highTemp,
                        lowTempF = lowTemp,
                        dewPointF = dewPointVal,
                        pressureInHg = pressureInHg,
                        cloudCoverPct = cloudCover,
                        visibilityMiles = visibilityVal,
                        uvIndex = uvVal,
                        aqi = -1,
                        aqiCategory = "",
                        sunrise = sunriseStr,
                        sunset = sunsetStr,
                        solarFraction = solarFraction,
                        isDay = isDay,
                        nextPrecipLabel = nextPrecip,
                        todayCond = summary,
                        tomorrowDay = tomorrowDName,
                        tomorrowHi = tomorrowHiVal,
                        tomorrowLo = tomorrowLoVal,
                        tomorrowCond = tomorrowSummary,
                        sourcesUsed = sourcesList.joinToString(" + "),
                        hourlySparklineTemps = sparklineTemps,
                        nwsStationId = nwsStation,
                        severeWarning = severeAlert,
                        lastUpdatedTime = System.currentTimeMillis(),
                        hourlyPrecip6h = precipList6h,
                        hourlyMeteogram = meteogramList,
                        dailyForecast = dailyList
                    )

                    _state.value = _state.value.copy(
                        weather = condition,
                        isLoading = false,
                        error = null
                    )
                    // Persist the essentials so a cold start shows the last real forecast instantly
                    // (widget + compact UI) instead of an "awaiting data" placeholder. Only the few
                    // fields those surfaces render — not the whole heavy condition object.
                    saveLastWeather(ctx, condition)
                    // Refresh the placed weather widgets with the new condition; no-op with none
                    // placed, and never let a widget failure poison the fetch cycle.
                    try { com.miku.launcher.widget.MikuWeatherWidget.pushUpdate(ctx) } catch (_: Throwable) {}
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = "HTTP ${omConn.responseCode}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Weather fetch error: ${t.message}")
                _state.value = _state.value.copy(isLoading = false, error = t.message)
            }
        }
    }

    private fun httpGetJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                setRequestProperty("Accept", "application/geo+json, application/json")
            }
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                JSONObject(body)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Solar progress, or null when the astro times are missing/unparseable.
     * Every failure path used to return `0.5f to true` — "it is daytime and we are exactly halfway
     * through the day" — which drove a 50 %-filled solar arc, a "SOLAR TRANSIT · 50%" readout and a
     * sun glyph at 2 AM. A caller that gets null must render the arc as unknown.
     */
    private fun calculateSolarProgress(sunriseStr: String, sunsetStr: String): Pair<Float, Boolean>? {
        if (sunriseStr.isBlank() || sunsetStr.isBlank()) return null
        try {
            val sdf = SimpleDateFormat("h:mm a", Locale.US)
            val sr = sdf.parse(sunriseStr) ?: return null
            val ss = sdf.parse(sunsetStr) ?: return null
            val cal = Calendar.getInstance()
            val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val calSr = Calendar.getInstance().apply { time = sr }
            val srMin = calSr.get(Calendar.HOUR_OF_DAY) * 60 + calSr.get(Calendar.MINUTE)

            val calSs = Calendar.getInstance().apply { time = ss }
            val ssMin = calSs.get(Calendar.HOUR_OF_DAY) * 60 + calSs.get(Calendar.MINUTE)

            val isDay = currentMin in srMin..ssMin
            val totalDayMin = (ssMin - srMin).coerceAtLeast(60)
            val frac = if (isDay) {
                ((currentMin - srMin).toFloat() / totalDayMin).coerceIn(0f, 1f)
            } else {
                val nightDuration = 1440 - totalDayMin
                val nightElapsed = if (currentMin > ssMin) currentMin - ssMin else (1440 - ssMin + currentMin)
                (nightElapsed.toFloat() / nightDuration).coerceIn(0f, 1f)
            }
            return frac to isDay
        } catch (_: Throwable) {
            return null
        }
    }

    private fun classifyPrecip(forecast: String): String {
        val f = forecast.lowercase(Locale.US)
        return when {
            f.contains("thunder") -> "TSTORM"
            f.contains("wintry mix") -> "WINTRY MIX"
            f.contains("snow") && f.contains("rain") -> "WINTRY MIX"
            f.contains("freezing rain") -> "FREEZE RAIN"
            f.contains("freezing") -> "FREEZE RAIN"
            f.contains("sleet") -> "SLEET"
            f.contains("snow") -> "SNOW"
            f.contains("hail") -> "HAIL"
            f.contains("shower") -> "SHOWERS"
            f.contains("drizzle") -> "DRIZZLE"
            f.contains("rain") -> "RAIN"
            else -> "PRECIP"
        }
    }

    fun mapWeatherCode(code: Int, isDay: Boolean, timestamp: Long = System.currentTimeMillis()): Pair<String, String> {
        val moonIcon = calculateMoonPhase(timestamp).phaseIcon
        return when (code) {
            0 -> "Clear Sky" to if (isDay) "☀️" else moonIcon
            1 -> "Mainly Clear" to if (isDay) "🌤️" else moonIcon
            2 -> "Partly Cloudy" to if (isDay) "⛅" else "☁️"
            3 -> "Overcast" to "☁️"
            45, 48 -> "Fog & Haze" to "🌫️"
            51, 53, 55 -> "Drizzle" to "🌦️"
            56, 57 -> "Freezing Drizzle" to "🌧️"
            61, 63, 65 -> "Rain Showers" to "🌧️"
            66, 67 -> "Freezing Rain" to "🌨️"
            71, 73, 75 -> "Snowfall" to "❄️"
            77 -> "Snow Grains" to "❄️"
            80, 81, 82 -> "Heavy Showers" to "⛈️"
            85, 86 -> "Heavy Snow Showers" to "🌨️"
            95 -> "Thunderstorm" to "⚡"
            96, 99 -> "Severe Thunderstorm & Hail" to "⛈️⚡"
            else -> "Fair" to if (isDay) "☀️" else moonIcon
        }
    }

    private fun degreesToCompass(deg: Int): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = (((deg % 360) / 22.5) + 0.5).toInt() % 16
        return directions[idx]
    }

    private fun checkSevereAlerts(code: Int, windSpeed: Float, precipIn: Float): String? {
        return when {
            code in listOf(95, 96, 99) -> "⚠️ SEVERE THUNDERSTORM & LIGHTNING DETECTED"
            windSpeed >= 40f -> "⚠️ HIGH WIND WARNING (>40 MPH GUSTS)"
            precipIn >= 0.75f -> "⚠️ FLASH FLOOD RISK - EXCESSIVE RAINFALL"
            code in listOf(85, 86) -> "⚠️ HEAVY WINTER BLIZZARD WARNING"
            else -> null
        }
    }
}
