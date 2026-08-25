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
                                                text = "▲ High ${weather.highTempF.roundToInt()}°F  ▼ Low ${weather.lowTempF.roundToInt()}°F",
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
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🌅 SUNRISE ${weather.sunrise}",
                                                color = com.miku.launcher.ui.MikuIdentity.Gold,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = if (weather.isDay) "☀️ DAYLIGHT PHASE" else "🌙 LUNAR NIGHT PHASE",
                                                color = if (weather.isDay) com.miku.launcher.ui.MikuIdentity.Gold else Color(0xFF80DEEA),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "🌇 SUNSET ${weather.sunset}",
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
                                                    .fillMaxWidth(weather.solarFraction)
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
                                        value = "${weather.windSpeedMph.roundToInt()} mph ${weather.windDirectionCompass}",
                                        sub = "Gusts to ${weather.windGustMph.roundToInt()} mph",
                                        icon = Icons.Default.Air,
                                        color = MikuCyan
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "HUMIDITY / DEW",
                                        value = "${weather.humidityPct}%",
                                        sub = "Dew Point ${weather.dewPointF.roundToInt()}°F",
                                        icon = Icons.Default.WaterDrop,
                                        color = Color(0xFF2979FF)
                                    )
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "BAROMETER / CLOUD",
                                        value = "${String.format(Locale.US, "%.2f", weather.pressureInHg)} inHg",
                                        sub = "${weather.cloudCoverPct}% Cloud Cover",
                                        icon = Icons.Default.Speed,
                                        color = com.miku.launcher.ui.MikuIdentity.Gold
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "SOLAR / UV & AIR",
                                        value = "UV ${weather.uvIndex.roundToInt()} · AQI ${weather.aqi}",
                                        sub = "Air Quality: ${weather.aqiCategory}",
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
 * Fetches real timestamped Doppler radar tiles over dark basemap with playback scrubber and dBZ scale.
 */
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

    // Load Live RainViewer Radar Frames & CartoDB Dark Basemap
    LaunchedEffect(gps.latitude, gps.longitude) {
        isLoading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val lat = if (gps.latitude != 0.0) gps.latitude else 35.0844
                val lon = if (gps.longitude != 0.0) gps.longitude else -106.6504
                val zoom = 6
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
                    text = "RANGE: 80NM · BASE REFLECTIVITY (0.5°) · dBZ SCALE",
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

            // 3. Cyber Range Rings & Tactical Scanner Grid
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = kotlin.math.min(size.width, size.height) / 2f - 8f

                // Range Rings: 20NM, 40NM, 60NM, 80NM
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

                // Station Center Point Marker
                drawCircle(com.miku.launcher.ui.MikuIdentity.Coral, radius = 4.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 2.dp.toPx(), center = center)

                // Rotating 360-degree radar beam sweep
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

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MikuCyan, modifier = Modifier.size(24.dp))
                }
            }

            // Radar Range Labels
            Text(
                "80NM",
                color = MikuCyan,
                fontSize = 7.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
            Text(
                "40NM",
                color = MikuCyan.copy(alpha = 0.7f),
                fontSize = 6.5.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-32).dp)
            )
            Text(
                "TARGET: ${if (gps.city.isNotEmpty()) gps.city else "STATION"}",
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
                Text("REFLECTIVITY (dBZ)", color = MikuCyan, fontSize = 7.sp, fontFamily = AudiowideFont)
                Text("15    30    45    55    65+", color = Color.White.copy(alpha = 0.7f), fontSize = 7.sp, fontFamily = AudiowideFont)
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
