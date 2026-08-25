package com.miku.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.miku.player.metrics.MikuMetricDatabase
import com.miku.player.weather.MikuWeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hatsune Miku Per-Listen Context & Atmospheric Meteorological Logger.
 * Records precise listening events correlated with real-time Open-Meteo weather
 * metrics (temp, humidity, wind, condition code), GPS coordinates, and DAC hardware state
 * directly into MikuMetricDatabase for deep music telemetry intelligence.
 */
object LocationLogger {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun logForTrack(
        ctx: Context,
        trackId: Long,
        nowMs: Long = System.currentTimeMillis(),
        title: String = "",
        artist: String = "",
        album: String = ""
    ) {
        if (!MikuPowerGovernor.allowLocation) return   // power governor: no location work while screen-off / idle
        scope.launch {
            try {
                val weatherState = MikuWeatherService.state.value
                val weather = weatherState.weather
                val gps = weatherState.gps

                var finalLat = gps.latitude
                var finalLon = gps.longitude
                var finalCity = if (gps.city.isNotEmpty()) gps.city else "Local Station"

                // Optional single-shot coarse location fix if GPS wasn't already locked
                if (!gps.isLocked && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    if (lm != null) {
                        val provider = when {
                            Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                            else -> null
                        }
                        if (provider != null) {
                            @Suppress("DEPRECATION")
                            val last = lm.getLastKnownLocation(provider)
                            if (last != null) {
                                finalLat = last.latitude
                                finalLon = last.longitude
                            }
                        }
                    }
                }

                // Record full correlated song playback + atmospheric weather entry in DB
                MikuMetricDatabase.getInstance(ctx).insertSongPlayWeatherTelemetry(
                    MikuMetricDatabase.SongPlayWeatherRecord(
                        timestamp = nowMs,
                        trackId = trackId,
                        title = title,
                        artist = artist,
                        album = album,
                        tempF = weather.tempF,
                        feelsLikeF = weather.feelsLikeF,
                        humidityPct = weather.humidityPct,
                        windSpeedMph = weather.windSpeedMph,
                        weatherSummary = weather.summary,
                        weatherCode = weather.code,
                        isDay = weather.isDay,
                        latitude = finalLat,
                        longitude = finalLon,
                        locationCity = finalCity,
                        dacSampleRate = 192000,
                        dacGain = "HIGH",
                        playbackDurationMs = 0L
                    )
                )
            } catch (_: Throwable) {}
        }
    }
}
