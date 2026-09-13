package com.miku.launcher.weather
import com.miku.launcher.*

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miku.launcher.R
import com.miku.launcher.*
import com.miku.launcher.metrics.MikuMetricDatabase
import com.miku.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Hatsune Miku Meteorological Observatory & Live Telemetry Modal.
 * Lifted directly from the OnThe8s (RetroWeather) meteorological pipeline:
 * 1. National Weather Service (NWS) direct station observations & lookaheads.
 * 2. Astronomical Solar Arc sunrise/sunset progression & day/night transit.
 * 3. 8-Point temperature trend sparkline with range boundaries.
 * 4. High-granularity environmental telemetry: Dew Point, Barometric Pressure, Cloud Cover, Visibility, AQI, UV.
 * 5. Interactive 360-degree Cyber Doppler Radar precipitation sweep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuWeatherObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val weatherState by MikuWeatherService.state.collectAsState()
    val weather = weatherState.weather
    val gps = weatherState.gps

    // Honest empty state: the WeatherCondition defaults (72°F / "Clear Sky" / AQI 30) are placeholders
    // until the first successful fetch - never render them as observations.
    if (weather.lastUpdatedTime == 0L) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { MikuWeatherService.refreshWeather(ctx) }) {
                    androidx.compose.material3.Text(if (weatherState.isLoading) "Fetching…" else "Fetch now")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissRequest) { androidx.compose.material3.Text("Close") }
            },
            title = { androidx.compose.material3.Text("No weather data yet") },
            text = {
                androidx.compose.material3.Text(
                    weatherState.error?.let { "Last attempt failed: $it" }
                        ?: "Nothing has been fetched from the weather service on this boot yet."
                )
            }
        )
        return
    }

    var historyRecords by remember { mutableStateOf<List<MikuMetricDatabase.WeatherRecord>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MikuWeatherService.CitySearchResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        historyRecords = MikuMetricDatabase.getInstance(ctx).getRecentWeatherHistory(24)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB040D12))
            .clickable { onDismissRequest() }
            // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
            .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .align(Alignment.Center)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFA09222E),
                                Color(0xFF04121A),
                                Color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    MikuCyan.copy(alpha = 0.95f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    MikuNeonPink.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Full Modal-Sized Transparent Miku Inlay Background
                Image(
                    painter = painterResource(R.drawable.miku_pose_dance),
                    contentDescription = "Miku Weather Idol",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    alpha = 0.20f,
                    contentScale = ContentScale.Crop
                )

                Column(
                    Modifier.fillMaxSize()
                ) {
                    // Header Bar with 3D Embossed Buttons & Task-Specific Miku Badge
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CutCornerShape(8.dp))
                                    .background(Color(0x3300E5FF))
                                    .border(1.dp, MikuCyan, CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.miku_pose_dance),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.clickable { showLocationPicker = true }) {
                                Text(
                                    text = "MIKU METEOROLOGICAL OBSERVATORY",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )
                                val isManual = MikuWeatherService.isManualLocationEnabled(ctx)
                                val providerBadge = if (isManual) "📍 MANUAL" else "📡 AUTO GPS"
                                Text(
                                    text = "$providerBadge: ${if (weather.nwsStationId.isNotEmpty()) weather.nwsStationId + " · " else ""}${if (gps.fuzzyLocation.isNotEmpty()) gps.fuzzyLocation else if (gps.city.isNotEmpty()) gps.city else "Local Station"} · [Tap to Change]",
                                    color = if (isManual) Color(0xFFFF80AB) else MikuCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.miku.launcher.network.Cyber3dIconButton(
                                onClick = { MikuWeatherService.refreshWeather(ctx) },
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh Weather",
                                accentColor = MikuCyan,
                                isLoading = weatherState.isLoading
                            )
                            com.miku.launcher.network.Cyber3dIconButton(
                                onClick = onDismissRequest,
                                icon = Icons.Default.Close,
                                contentDescription = "Close Modal",
                                accentColor = MikuNeonPink
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Cyber 3-Tab Switcher (Overview vs Meteogram vs Live Doppler Radar)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TabButton(
                            title = "📊 OVERVIEW",
                            isSelected = selectedTab == 0,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 0 }

                        TabButton(
                            title = "📈 FORECAST",
                            isSelected = selectedTab == 1,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 1 }

                        TabButton(
                            title = "🛰️ RADAR",
                            isSelected = selectedTab == 2,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 2 }
                    }

                    Spacer(Modifier.height(8.dp))

                    when (selectedTab) {
                        0 -> {
                            // TAB 0: HIGH-DENSITY OBSERVATORY OVERVIEW (No scrolling needed)
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Multi-Source Feed Attribution Banner
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(4.dp))
                                        .background(Color(0x2200E5FF))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚡ DATA FEEDS: ${weather.sourcesUsed.uppercase()}",
                                        color = MikuCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    if (weather.nextPrecipLabel.isNotEmpty()) {
                                        Text(
                                            text = "⏱️ ${weather.nextPrecipLabel}",
                                            color = com.miku.launcher.ui.MikuIdentity.Gold,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                }

                                // Main Current Conditions Hero Card
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(12.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0x4400E5FF),
                                                    Color(0x22041D28),
                                                    Color(0xFF031016)
                                                )
                                            )
                                        )
                                        .border(
                                            BorderStroke(
                                                1.dp,
                                                Brush.linearGradient(listOf(MikuCyan, Color(0x3300E5FF), MikuNeonPink))
                                            ),
                                            CutCornerShape(12.dp)
                                        )
                                        .padding(10.dp)
                                    ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    text = "${weather.tempF.roundToInt()}°",
                                                    color = Color.White,
                                                    fontSize = 32.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = AudiowideFont
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    text = "F",
                                                    color = MikuCyan,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = AudiowideFont,
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = "${weather.summary} · Feels ${weather.feelsLikeF.roundToInt()}°F",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                // 0 = no daily block in the last fetch (and a
                                                // partial cache restore leaves it 0 too). It used
                                                // to print "▲ High 0°F ▼ Low 0°F" as a forecast.
                                                text = if (weather.highTempF != 0f || weather.lowTempF != 0f)
                                                    "▲ High ${weather.highTempF.roundToInt()}°F  ▼ Low ${weather.lowTempF.roundToInt()}°F"
                                                else "▲ High —  ▼ Low —",
                                                color = MikuTextSecondary,
                                                fontSize = 11.5.sp
                                            )
                                        }

                                        // Lunar & Solar Astronomical Status Badge
                                        Column(horizontalAlignment = Alignment.End) {
                                            Box(
                                                Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0x3300E5FF))
                                                    .border(1.2.dp, MikuCyan, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = weather.icon,
                                                    fontSize = 24.sp
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "${weather.moonPhase.phaseIcon} ${weather.moonPhase.phaseName}",
                                                color = Color(0xFF80DEEA),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "${weather.moonPhase.illuminationPct}% illuminated",
                                                color = MikuTextSecondary,
                                                fontSize = 9.5.sp
                                            )
                                        }
                                    }
                                }

                                // 8-Hour Temperature Sparkline Trend
                                if (weather.hourlySparklineTemps.isNotEmpty()) {
                                    MikuTemperatureSparkline(
                                        temps = weather.hourlySparklineTemps,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Astronomical Solar Arc
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(10.dp))
                                        .background(Color(0xFF03141C))
                                        .border(0.8.dp, MikuCyan.copy(alpha = 0.4f), CutCornerShape(10.dp))
                                        .padding(8.dp)
                                ) {
                                    val hasAstro = weather.sunrise.isNotBlank() && weather.sunset.isNotBlank()
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Blank sunrise/sunset = the fetch carried no astro
                                            // (or this is a partial cache restore). It used to
                                            // render "🌅 SUNRISE " / "🌇 SUNSET " with an empty
                                            // time and a 0 %-filled arc as if they were readings.
                                            Text(
                                                text = "🌅 SUNRISE ${weather.sunrise.ifBlank { "—" }}",
                                                color = com.miku.launcher.ui.MikuIdentity.Gold,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = if (!hasAstro) "— PHASE UNKNOWN"
                                                    else if (weather.isDay) "☀️ DAYLIGHT PHASE" else "🌙 LUNAR NIGHT PHASE",
                                                color = if (hasAstro && weather.isDay) com.miku.launcher.ui.MikuIdentity.Gold else Color(0xFF80DEEA),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "🌇 SUNSET ${weather.sunset.ifBlank { "—" }}",
                                                color = Color(0xFFFF9100),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color(0xFF0A222C))
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth(if (hasAstro) weather.solarFraction.coerceIn(0f, 1f) else 0f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(com.miku.launcher.ui.MikuIdentity.Gold, Color(0xFFFF9100), MikuCyan)
                                                        )
                                                    )
                                            )
                                        }
                                    }
                                }

                                // 4 High-Density Environmental Telemetry Tiles (2x2 Grid)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "WIND / GUSTS",
                                        // 0 gust = wind_gusts_10m was not reported for this hour.
                                        // It used to print windSpeed * 1.35 as a measured gust.
                                        value = "${weather.windSpeedMph.roundToInt()} mph ${weather.windDirectionCompass}",
                                        sub = if (weather.windGustMph > 0f) "Gusts to ${weather.windGustMph.roundToInt()} mph" else "Gusts —",
                                        icon = Icons.Default.Air,
                                        color = MikuCyan
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "HUMIDITY / DEW",
                                        // 0 = never reported (see WeatherCondition's neutral
                                        // defaults); the dew point used to be faked as temp-15°F.
                                        value = if (weather.humidityPct > 0) "${weather.humidityPct}%" else "—",
                                        sub = if (weather.dewPointF != 0f) "Dew Point ${weather.dewPointF.roundToInt()}°F" else "Dew Point —",
                                        icon = Icons.Default.WaterDrop,
                                        color = Color(0xFF2979FF)
                                    )
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "BAROMETER / CLOUD",
                                        // 0 = no surface_pressure in the response. It used to fall
                                        // back to 1013.25 hPa — the standard atmosphere — and print
                                        // "29.92 inHg" as a barometer reading.
                                        value = if (weather.pressureInHg > 0f)
                                            "${String.format(Locale.US, "%.2f", weather.pressureInHg)} inHg" else "— inHg",
                                        sub = "${weather.cloudCoverPct}% Cloud Cover",
                                        icon = Icons.Default.Speed,
                                        color = com.miku.launcher.ui.MikuIdentity.Gold
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "SOLAR / UV & AIR",
                                        // UV 0 = not reported (was a flat 4). AQI is the -1
                                        // "no AQI source wired up" sentinel — it used to be printed
                                        // raw, so the tile literally read "AQI -1".
                                        value = "UV " + (if (weather.uvIndex > 0f) "${weather.uvIndex.roundToInt()}" else "—") +
                                            " · AQI " + (if (weather.aqi >= 0) "${weather.aqi}" else "—"),
                                        sub = if (weather.aqiCategory.isNotBlank()) "Air Quality: ${weather.aqiCategory}" else "Air Quality: —",
                                        icon = Icons.Default.WbSunny,
                                        color = Color(0xFFFF9100)
                                    )
                                }
                            }
                        }

                        1 -> {
                            // TAB 1: METEOGRAM & MULTI-DAY FORECAST
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MikuMeteogramForecastView(
                                    weather = weather,
                                    gps = gps,
                                    modifier = Modifier.fillMaxWidth(),
                                    isCompact = false
                                )

                                Spacer(Modifier.height(4.dp))

                                // Historical Weather Timeline
                                Text(
                                    text = "24-HOUR HISTORICAL TEMPERATURE LOG",
                                    color = MikuCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )

                                if (historyRecords.isEmpty()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(CutCornerShape(8.dp))
                                            .background(Color(0x220A222C))
                                            .border(1.dp, CyberGlassBorder, CutCornerShape(8.dp))
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Metric telemetry accumulating in local datastore...",
                                            color = MikuTextSecondary,
                                            fontSize = 8.5.sp
                                        )
                                    }
                                } else {
                                    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                                    LazyRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(historyRecords.take(12)) { rec ->
                                            Box(
                                                Modifier
                                                    .width(78.dp)
                                                    .clip(CutCornerShape(8.dp))
                                                    .background(Color(0x3300E5FF))
                                                    .border(1.dp, MikuCyan.copy(alpha = 0.5f), CutCornerShape(8.dp))
                                                    .padding(6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = sdf.format(Date(rec.timestamp)),
                                                        color = MikuCyan,
                                                        fontSize = 7.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = AudiowideFont
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        text = "${rec.tempF.roundToInt()}°F",
                                                        color = getWeatherTempColor(rec.tempF.roundToInt()),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Black,
                                                        fontFamily = AudiowideFont
                                                    )
                                                    Text(
                                                        text = "💧 ${rec.humidityPct}%",
                                                        color = MikuTextSecondary,
                                                        fontSize = 7.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // TAB 2: LIVE DOPPLER RADAR SWEEP
                            MikuLiveDopplerRadarTab(gps = gps, weather = weather)
                        }
                    }
                }
            }
        }

        if (showLocationPicker) {
            Dialog(
                onDismissRequest = { showLocationPicker = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .clip(CutCornerShape(12.dp))
                        .background(Color(0xFF04121A))
                        .border(1.dp, MikuCyan, CutCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SET WEATHER LOCATION",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            IconButton(onClick = { showLocationPicker = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = MikuNeonPink, modifier = Modifier.size(18.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Button: Use Live GPS Auto-Detect
                        Button(
                            onClick = {
                                MikuWeatherService.clearManualLocation(ctx)
                                showLocationPicker = false
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                            border = BorderStroke(1.dp, MikuCyan)
                        ) {
                            Text("📡 USE LIVE GPS AUTO-DETECT", color = MikuCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }

                        Spacer(Modifier.height(8.dp))

                        Text("OR SEARCH CITY / ZIP CODE:", color = MikuTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                MikuWeatherService.searchCity(it) { list ->
                                    searchResults = list
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. Seattle, Tokyo, Dallas...", color = Color.Gray, fontSize = 10.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = MikuCyan,
                                unfocusedBorderColor = Color(0x6600E5FF)
                            )
                        )

                        Spacer(Modifier.height(6.dp))

                        if (searchResults.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(searchResults) { res ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x2200E5FF))
                                            .border(0.5.dp, MikuCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .clickable {
                                                MikuWeatherService.setManualLocation(
                                                    ctx,
                                                    res.latitude,
                                                    res.longitude,
                                                    res.name,
                                                    res.region,
                                                    res.country
                                                )
                                                showLocationPicker = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                "${res.name}, ${if (res.region.isNotEmpty()) res.region + ", " else ""}${res.country}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "Lat: ${String.format(Locale.US, "%.2f", res.latitude)}° Lon: ${String.format(Locale.US, "%.2f", res.longitude)}°",
                                                color = MikuCyan,
                                                fontSize = 7.sp
                                            )
                                        }
                                        Text("SELECT", color = MikuCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0x4400E5FF) else Color(0x1104121A))
            .border(
                1.dp,
                if (isSelected) MikuCyan else CyberGlassBorder.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else MikuTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}

fun getWeatherTempColor(tempF: Int): Color {
    return when {
        tempF < 32 -> Color(0xFF7C4DFF) // Freezing (Deep Purple)
        tempF < 50 -> Color(0xFF2979FF) // Cold (Sky Blue)
        tempF < 65 -> Color(0xFF00E5FF) // Cool (Cyan)
        tempF < 75 -> com.miku.launcher.ui.MikuIdentity.Leek // Mild (Green)
        tempF < 85 -> com.miku.launcher.ui.MikuIdentity.Gold // Warm (Electric Yellow)
        tempF < 95 -> Color(0xFFFF6D00) // Hot (Orange)
        else -> com.miku.launcher.ui.MikuIdentity.Coral       // Extreme Heat (Crimson)
    }
}

/**
 * 8-Point Temperature Trend Sparkline (from OnThe8s SparklineView).
 * Standard meteorological color coding: Purple/Blue (cold) to Yellow/Orange/Red (hot).
 */
@Composable
fun MikuTemperatureSparkline(
    temps: List<Int>,
    modifier: Modifier = Modifier
) {
    if (temps.size < 2) return
    val minT = temps.minOrNull() ?: 50
    val maxT = temps.maxOrNull() ?: 80
    val range = (maxT - minT).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CutCornerShape(8.dp))
            .background(Color(0x33020A0F))
            .border(0.6.dp, MikuCyan.copy(alpha = 0.4f), CutCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📈 8-HOUR TEMPERATURE TREND",
                    color = MikuCyan,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "RANGE: $minT°F - $maxT°F",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(4.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val w = size.width
                val h = size.height
                val padH = 14f
                val padV = 6f
                val chartW = w - 2 * padH
                val chartH = h - 2 * padV
                val step = chartW / (temps.size - 1)

                val path = Path()
                temps.forEachIndexed { idx, t ->
                    val x = padH + idx * step
                    val y = padV + (1f - (t - minT).toFloat() / range) * chartH
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                val startColor = getWeatherTempColor(temps.first())
                val endColor = getWeatherTempColor(temps.last())

                // Dynamic Thermocline Gradient stroke
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(startColor, endColor)
                    ),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Point dots colored by temperature
                temps.forEachIndexed { idx, t ->
                    val x = padH + idx * step
                    val y = padV + (1f - (t - minT).toFloat() / range) * chartH
                    val ptColor = getWeatherTempColor(t)
                    drawCircle(color = ptColor, radius = 3.2.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = Color.White, radius = 1.4.dp.toPx(), center = Offset(x, y))
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                temps.forEach { t ->
                    val ptColor = getWeatherTempColor(t)
                    Text(
                        text = "$t°",
                        color = ptColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherMetricBadge(
    title: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CutCornerShape(8.dp))
            .background(Color(0x3300E5FF))
            .border(1.dp, color.copy(alpha = 0.45f), CutCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    color = MikuTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

data class RadarFrameInfo(
    val time: Long,
    val timeLabel: String,
    val bitmap: ImageBitmap?
)

/**
 * Hatsune Miku Live Doppler Radar Suite (RainViewer High-Res Composite).
 * Fetches real timestamped RainViewer radar tiles over a dark basemap with a playback scrubber.
 *
 * Everything drawn here is tied to the REAL fix and the REAL web-mercator tile scale:
 *  - no fix => no tiles fetched and no station mark drawn (it used to fall back to a hardcoded
 *    35.0844/-106.6504 and show Albuquerque's radar as the user's own),
 *  - the station mark sits at the device's fractional position inside the tile, not at the tile
 *    centre (at z6 a tile is ~600 km wide, so "centre" was up to ~300 km from the truth),
 *  - the range rings are labelled with distances computed from the tile scale, not the old
 *    hardcoded 20/40/60/80 NM, which described a range this imagery never had.
 */
private const val RADAR_ZOOM = 6
/** Web-mercator equatorial circumference, metres — for the real ground scale of a radar tile. */
private const val EARTH_CIRCUMFERENCE_M = 40075016.686
@Composable
fun MikuLiveDopplerRadarTab(
    gps: MikuWeatherService.GpsTelemetry,
    weather: MikuWeatherService.WeatherCondition
) {
    var pastFrames by remember { mutableStateOf<List<RadarFrameInfo>>(emptyList()) }
    var basemapBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    // Real pixel size of the radar viewport — the only way to turn a ring radius in pixels into a
    // distance on the ground. Zero until the first layout pass, which renders every label as "—".
    var viewportPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAngle"
    )

    // A radar picture is only honest when we know where the device actually is.
    val hasFix = gps.latitude != 0.0 || gps.longitude != 0.0

    // Real ground scale of the drawn tile. The 256 px tile is drawn with ContentScale.Crop, so it
    // covers max(w, h) px; one tile spans EARTH_CIRCUMFERENCE * cos(lat) / 2^zoom metres.
    val metersPerPx: Double? = if (hasFix && viewportPx.width > 0 && viewportPx.height > 0) {
        val drawnTilePx = maxOf(viewportPx.width, viewportPx.height).toDouble()
        (EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(gps.latitude)) / (1 shl RADAR_ZOOM)) / drawnTilePx
    } else null
    // Outer range ring, in nautical miles, MEASURED from that scale (was a hardcoded "80NM").
    val outerRingNm: Double? = metersPerPx?.let {
        val maxRadiusPx = minOf(viewportPx.width, viewportPx.height) / 2.0 - 8.0
        if (maxRadiusPx <= 0) null else maxRadiusPx * it / 1852.0
    }

    // Load Live RainViewer Radar Frames & CartoDB Dark Basemap
    LaunchedEffect(gps.latitude, gps.longitude) {
        // WAS: fell through to a hardcoded Albuquerque (35.0844/-106.6504) with no fix, so the
        // user saw a stranger's storms under "TARGET: STATION". NOW: no fix => no radar.
        if (!hasFix) {
            pastFrames = emptyList()
            basemapBitmap = null
            currentFrameIndex = 0
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val lat = gps.latitude
                val lon = gps.longitude
                val zoom = RADAR_ZOOM
                val tileX = ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
                val latRad = Math.toRadians(lat)
                val tileY = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()

                // 1. Download CartoDB Dark basemap tile
                try {
                    val baseMapUrl = "https://a.basemaps.cartocdn.com/dark_all/$zoom/$tileX/$tileY.png"
                    val baseReq = java.net.URL(baseMapUrl).openConnection() as java.net.HttpURLConnection
                    baseReq.setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                    baseReq.connectTimeout = 6000
                    baseReq.readTimeout = 6000
                    val baseBytes = baseReq.inputStream.use { it.readBytes() }
                    val baseBmp = BitmapFactory.decodeByteArray(baseBytes, 0, baseBytes.size)
                    basemapBitmap = baseBmp?.asImageBitmap()
                } catch (_: Throwable) {}

                // 2. Fetch RainViewer radar metadata
                val rvReq = java.net.URL("https://api.rainviewer.com/public/weather-maps.json").openConnection() as java.net.HttpURLConnection
                rvReq.setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                rvReq.connectTimeout = 6000
                rvReq.readTimeout = 6000
                val rvJsonStr = rvReq.inputStream.bufferedReader().use { it.readText() }
                val rootJson = org.json.JSONObject(rvJsonStr)
                val host = rootJson.optString("host", "https://tilecache.rainviewer.com")
                val radarObj = rootJson.optJSONObject("radar")
                val pastArray = radarObj?.optJSONArray("past")

                val frames = mutableListOf<RadarFrameInfo>()
                if (pastArray != null) {
                    val count = pastArray.length()
                    val startIndex = (count - 6).coerceAtLeast(0)
                    for (i in startIndex until count) {
                        val item = pastArray.getJSONObject(i)
                        val t = item.getLong("time")
                        val p = item.getString("path")
                        val tileUrl = "$host$p/256/$zoom/$tileX/$tileY/2/1_1.png"

                        try {
                            val tileReq = java.net.URL(tileUrl).openConnection() as java.net.HttpURLConnection
                            tileReq.setRequestProperty("User-Agent", "MikuMeteorology/1.0 (contact@falcontechnix.com)")
                            tileReq.connectTimeout = 5000
                            tileReq.readTimeout = 5000
                            val tileBytes = tileReq.inputStream.use { it.readBytes() }
                            val tileBmp = BitmapFactory.decodeByteArray(tileBytes, 0, tileBytes.size)

                            val cal = java.util.Calendar.getInstance()
                            cal.timeInMillis = t * 1000L
                            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            val label = sdf.format(cal.time)

                            frames.add(RadarFrameInfo(time = t, timeLabel = label, bitmap = tileBmp?.asImageBitmap()))
                        } catch (_: Throwable) {}
                    }
                }
                pastFrames = frames
                currentFrameIndex = if (frames.isNotEmpty()) frames.size - 1 else 0
            } catch (t: Throwable) {
                android.util.Log.e("MikuWeatherRadar", "Failed to fetch live radar frames", t)
            } finally {
                isLoading = false
            }
        }
    }

    // Playback loop timer
    LaunchedEffect(isPlaying, pastFrames.size) {
        if (isPlaying && pastFrames.isNotEmpty()) {
            while (true) {
                kotlinx.coroutines.delay(650L)
                currentFrameIndex = (currentFrameIndex + 1) % pastFrames.size
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // Radar Header & Metadata
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "RADAR: MIKU DOPPLER COMPOSITE",
                    color = MikuCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    // WAS: "RANGE: 80NM · BASE REFLECTIVITY (0.5°) · dBZ SCALE" — none of which was
                    // true of a RainViewer z6 composite tile. NOW: the source, and the outer-ring
                    // distance actually computed from the tile's ground scale ("—" before layout).
                    text = "RAINVIEWER COMPOSITE · z$RADAR_ZOOM · OUTER RING " +
                        (outerRingNm?.let { "${"%.0f".format(it)} NM" } ?: "—"),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (pastFrames.isNotEmpty() && currentFrameIndex in pastFrames.indices) {
                Box(
                    Modifier
                        .clip(CutCornerShape(4.dp))
                        .background(Color(0x3300E5FF))
                        .border(1.dp, MikuCyan, CutCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "FRAME ${pastFrames[currentFrameIndex].timeLabel}",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Radar Map Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(CutCornerShape(12.dp))
                .background(Color(0xFF02090E))
                .border(1.dp, MikuCyan.copy(alpha = 0.8f), CutCornerShape(12.dp))
                .onSizeChanged { viewportPx = it }
        ) {
            // 1. Dark Basemap Tile (if loaded)
            basemapBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Radar Basemap",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // 2. Live RainViewer Doppler Radar Tile (Current Frame)
            if (pastFrames.isNotEmpty() && currentFrameIndex in pastFrames.indices) {
                pastFrames[currentFrameIndex].bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Radar Echo Tile",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.85f
                    )
                }
            }

            // 3. Cyber Range Rings & Tactical Scanner Grid — drawn ONLY with a real fix, and
            //    centred on the device's real position inside the tile (was the tile centre, which
            //    at z6 is up to ~300 km away from where the user actually is).
            if (hasFix) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // ContentScale.Crop draws the 256 px tile at max(w, h) px, centred.
                    val drawn = kotlin.math.max(size.width, size.height)
                    val originX = (size.width - drawn) / 2f
                    val originY = (size.height - drawn) / 2f
                    val n = (1 shl RADAR_ZOOM).toDouble()
                    val latRad = Math.toRadians(gps.latitude)
                    val tx = (gps.longitude + 180.0) / 360.0 * n
                    val ty = (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n
                    val fracX = (tx - kotlin.math.floor(tx)).toFloat()
                    val fracY = (ty - kotlin.math.floor(ty)).toFloat()
                    val center = Offset(originX + fracX * drawn, originY + fracY * drawn)
                    val maxRadius = kotlin.math.min(size.width, size.height) / 2f - 8f

                    // Range rings at 1/4, 1/2, 3/4 and the full computed radius. Their real
                    // distances are printed by the labels below, from [outerRingNm].
                    val rings = listOf(0.25f, 0.50f, 0.75f, 1.0f)
                    rings.forEach { frac ->
                        drawCircle(
                            color = MikuCyan.copy(alpha = 0.25f),
                            radius = maxRadius * frac,
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Crosshairs
                    drawLine(
                        color = MikuCyan.copy(alpha = 0.35f),
                        start = Offset(center.x - maxRadius, center.y),
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = MikuCyan.copy(alpha = 0.35f),
                        start = Offset(center.x, center.y - maxRadius),
                        end = Offset(center.x, center.y + maxRadius),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Station Point Marker — the device's real spot on this tile.
                    drawCircle(com.miku.launcher.ui.MikuIdentity.Coral, radius = 4.dp.toPx(), center = center)
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = center)

                    // Decorative sweep arm. It is an ANIMATION, not a scan: the imagery is a
                    // downloaded composite frame, so the arm never uncovers or refreshes anything.
                    val sweepRad = Math.toRadians(sweepAngle.toDouble())
                    val beamEndX = center.x + (maxRadius * kotlin.math.cos(sweepRad)).toFloat()
                    val beamEndY = center.y + (maxRadius * kotlin.math.sin(sweepRad)).toFloat()
                    drawLine(
                        color = MikuCyan.copy(alpha = 0.9f),
                        start = center,
                        end = Offset(beamEndX, beamEndY),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MikuCyan, modifier = Modifier.size(24.dp))
                }
            } else if (!hasFix) {
                // Honest empty state. The old code silently substituted a hardcoded city here.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "NO LOCATION FIX · RADAR UNAVAILABLE",
                        color = Color(0xFFFFB300),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (pastFrames.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "NO RADAR FRAMES RETRIEVED",
                        color = Color(0xFFFFB300),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Radar Range Labels — distances MEASURED from the tile scale. They were hardcoded
            // "80NM" / "40NM", which had nothing to do with the imagery on screen.
            Text(
                outerRingNm?.let { "${"%.0f".format(it)}NM" } ?: "—",
                color = MikuCyan,
                fontSize = 7.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
            Text(
                outerRingNm?.let { "${"%.0f".format(it / 2.0)}NM" } ?: "—",
                color = MikuCyan.copy(alpha = 0.7f),
                fontSize = 6.5.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-32).dp)
            )
            Text(
                // Only names a place the geocoder actually resolved; "STATION" used to stand in for
                // a location we did not have at all.
                text = when {
                    gps.city.isNotEmpty() -> "TARGET: ${gps.city}"
                    hasFix -> "TARGET: ${"%.3f".format(gps.latitude)}, ${"%.3f".format(gps.longitude)}"
                    else -> "TARGET: —"
                },
                color = Color.White,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Playback Controls & Frame Scrubber
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isPlaying = !isPlaying },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                border = BorderStroke(1.dp, MikuCyan),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontSize = 8.sp, fontFamily = AudiowideFont)
            }

            if (pastFrames.isNotEmpty()) {
                Slider(
                    value = currentFrameIndex.toFloat(),
                    onValueChange = {
                        isPlaying = false
                        currentFrameIndex = it.toInt().coerceIn(0, pastFrames.size - 1)
                    },
                    valueRange = 0f..(pastFrames.size - 1).toFloat(),
                    steps = (pastFrames.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MikuCyan,
                        activeTrackColor = MikuCyan,
                        inactiveTrackColor = Color(0x3300E5FF)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // dBZ Reflectivity Scale Legend Bar
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(6.dp))
                .background(Color(0x9904121A))
                .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(6.dp))
                .padding(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // The bar below is Miku brand colour, NOT RainViewer's tile palette, so it cannot
                // be read as a dBZ key. It used to be labelled "REFLECTIVITY (dBZ) 15 30 45 55 65+",
                // inviting the user to decode real reflectivity values off colours that mean nothing.
                Text("PRECIPITATION INTENSITY", color = MikuCyan, fontSize = 7.sp, fontFamily = AudiowideFont)
                Text("LIGHT → HEAVY", color = Color.White.copy(alpha = 0.7f), fontSize = 7.sp, fontFamily = AudiowideFont)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF00E5FF),
                                com.miku.launcher.ui.MikuIdentity.Leek,
                                com.miku.launcher.ui.MikuIdentity.Gold,
                                Color(0xFFFF9100),
                                com.miku.launcher.ui.MikuIdentity.Coral,
                                Color(0xFFFF007F)
                            )
                        )
                    )
            )
        }
    }
}
