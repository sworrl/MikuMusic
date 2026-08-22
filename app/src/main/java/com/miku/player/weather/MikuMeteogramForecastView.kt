package com.miku.player.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.AudiowideFont
import com.miku.player.CyberGlassBorder
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import com.miku.player.theme.MikuDiurnalTheme
import kotlin.math.roundToInt

/**
 * Windy-Style High-Precision Multi-Day Meteogram Forecast Strip.
 * Directly integrates the OnThe8s astronomical solar arc and 24-hour meteorological table:
 * 1. Astronomical Solar Arc (Sunrise/Sunset progress track, day/night transit).
 * 2. Look-ahead precipitation arrival ("RAIN IN 2 HRS", "TSTORM NOW", "CLEAR NEXT 24H").
 * 3. Future-indexed 24-Hour time headers starting from NOW.
 * 4. Meteorological condition icons (Day Sun vs Night Moon).
 * 5. Temperature values, rain gauges, and directional wind vectors.
 */
@Composable
fun MikuMeteogramForecastView(
    weather: MikuWeatherService.WeatherCondition,
    gps: MikuWeatherService.GpsTelemetry,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val palette by MikuDiurnalTheme.rememberDiurnalPalette()
    val scrollState = rememberScrollState()

    // Hourly points (sample starting from NOW)
    val points = remember(weather.hourlyMeteogram) {
        if (weather.hourlyMeteogram.isNotEmpty()) {
            if (isCompact) weather.hourlyMeteogram.take(12)
            else weather.hourlyMeteogram.take(24)
        } else {
            (0..11).map { i ->
                val hour = (12 + i * 2) % 24
                val isDay = hour in 6..19
                val (sum, ic) = MikuWeatherService.mapWeatherCode(weather.code, isDay)
                MikuWeatherService.HourlyMeteogramPoint(
                    timeLabel = String.format("%02d:00", hour),
                    dayLabel = if (hour < 6) "Tomorrow" else "Today",
                    tempF = (weather.tempF + (if (hour in 10..18) 6f else -6f) - (i * 0.4f)),
                    feelsLikeF = weather.feelsLikeF,
                    precipInches = (weather.precipitationIn * (1f - i * 0.08f)).coerceAtLeast(0f),
                    precipProbPct = (weather.precipitationProbPct - i * 4).coerceIn(0, 100),
                    windSpeedMph = (weather.windSpeedMph + (i % 3) * 1.5f),
                    windGustsMph = (weather.windSpeedMph + (i % 3) * 2f + 3f),
                    windDirectionDeg = (weather.windDirectionDeg + i * 15) % 360,
                    windDirectionCompass = weather.windDirectionCompass,
                    weatherCode = weather.code,
                    summary = sum,
                    icon = ic,
                    isDay = isDay
                )
            }
        }
    }

    val itemWidth = if (isCompact) 44.dp else 52.dp

    Box(
        modifier = modifier
            .clip(CutCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xF0061822),
                        Color(0xFA030D14)
                    )
                )
            )
            .border(1.dp, palette.primary.copy(alpha = 0.75f), CutCornerShape(10.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp)
    ) {
        Column {
            // Top Meteogram Title & Location Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📊", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "METEOGRAM FORECAST",
                        color = palette.primary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    if (weather.nextPrecipLabel.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(CutCornerShape(3.dp))
                                .background(Color(0xFF00E5FF).copy(alpha = 0.25f))
                                .border(0.6.dp, Color(0xFF00E5FF), CutCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = weather.nextPrecipLabel,
                                color = Color(0xFF00E5FF),
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }

                Text(
                    text = "${weather.tempF.roundToInt()}°F · ${if (gps.city.isNotEmpty()) gps.city else "Local"}",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(4.dp))

            // Horizontally Scrollable Meteogram Strip Table
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 4.dp)
                ) {
                    // Day column grouping headers
                    val dayGroups = remember(points) {
                        points.groupBy { it.dayLabel }
                    }

                    dayGroups.forEach { (dayName, dayPoints) ->
                        Column(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x4404141E))
                                .border(0.6.dp, CyberGlassBorder.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        ) {
                            // Day Name Header
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(palette.primary.copy(alpha = 0.15f))
                                    .padding(vertical = 2.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayName.uppercase(),
                                    color = palette.primary,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }

                            // Hourly Data Columns Row
                            Row {
                                dayPoints.forEach { pt ->
                                    Column(
                                        Modifier
                                            .width(itemWidth)
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        // 1. Time Label (24-Hour Military Time)
                                        Text(
                                            text = pt.timeLabel,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )

                                        Spacer(Modifier.height(2.dp))

                                        // 2. Weather Condition Icon (Sun vs Moon based on day state)
                                        Text(
                                            text = pt.icon,
                                            fontSize = 12.sp
                                        )

                                        Spacer(Modifier.height(2.dp))

                                        // 3. Temperature Value
                                        Text(
                                            text = "${pt.tempF.roundToInt()}°",
                                            color = when {
                                                pt.tempF >= 85f -> Color(0xFFFF5252)
                                                pt.tempF >= 70f -> Color(0xFFFFD600)
                                                pt.tempF >= 55f -> Color(0xFF00E676)
                                                else -> Color(0xFF00E5FF)
                                            },
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )

                                        Spacer(Modifier.height(3.dp))

                                        // 4. Rain Precipitation Percentage & Drop
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text("💧", fontSize = 7.sp)
                                            Spacer(Modifier.width(1.dp))
                                            Text(
                                                text = "${pt.precipProbPct}%",
                                                color = if (pt.precipProbPct > 20) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }

                                        Spacer(Modifier.height(3.dp))

                                        // 5. Wind Speed Cell (Color Coded Speed)
                                        val windSpd = pt.windSpeedMph.roundToInt()
                                        val windCellColor = when {
                                            windSpd >= 25 -> Color(0xFFFF1744) // Severe gale
                                            windSpd >= 15 -> Color(0xFFFF9100) // Moderate
                                            windSpd >= 8  -> Color(0xFF76FF03) // Light breeze
                                            else          -> Color(0xFF00E5FF) // Gentle
                                        }
                                        Box(
                                            Modifier
                                                .width(itemWidth - 6.dp)
                                                .clip(CutCornerShape(3.dp))
                                                .background(windCellColor.copy(alpha = 0.35f))
                                                .padding(vertical = 1.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$windSpd",
                                                color = Color.White,
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }

                                        Spacer(Modifier.height(2.dp))

                                        // 6. Wind Direction Arrow
                                        Text(
                                            text = "↑",
                                            color = palette.primary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.rotate(pt.windDirectionDeg.toFloat())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Integrated Solar Arc Bar from OnThe8s
            MikuSolarArcTrack(
                sunrise = weather.sunrise,
                sunset = weather.sunset,
                solarFraction = weather.solarFraction,
                isDay = weather.isDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Astronomical Solar Arc Progress Bar (Direct Lift from OnThe8s SolarArcView).
 * Shows sunrise to sunset trajectory, glowing solar traveler, and day/night progress.
 */
@Composable
fun MikuSolarArcTrack(
    sunrise: String,
    sunset: String,
    solarFraction: Float,
    isDay: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x33020A0F))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "☀️ RISE: $sunrise",
                    color = Color(0xFFFFB300),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = if (isDay) "SOLAR TRANSIT · ${(solarFraction * 100).roundToInt()}%" else "LUNAR TRANSIT",
                    color = if (isDay) MikuCyan else Color(0xFFB388FF),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "🌙 SET: $sunset",
                    color = Color(0xFFFF80AB),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(3.dp))

            // Graphical Solar Arc Bar
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                val w = size.width
                val h = size.height
                val trackY = h / 2f

                // Base Track Line
                drawLine(
                    color = Color(0x3300E5FF),
                    start = Offset(0f, trackY),
                    end = Offset(w, trackY),
                    strokeWidth = h,
                    cap = StrokeCap.Round
                )

                // Day Gradient Track
                val gradientBrush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFFFF6D00),
                        Color(0xFFFFD600),
                        Color(0xFF00E5FF),
                        Color(0xFFFF80AB)
                    )
                )

                drawLine(
                    brush = gradientBrush,
                    start = Offset(0f, trackY),
                    end = Offset(w * solarFraction.coerceIn(0f, 1f), trackY),
                    strokeWidth = h,
                    cap = StrokeCap.Round
                )

                // Sun / Moon Indicator Dot with Glow
                val dotX = (w * solarFraction.coerceIn(0f, 1f)).coerceIn(h, w - h)
                val dotColor = if (isDay) Color(0xFFFFD600) else Color(0xFFB388FF)

                drawCircle(
                    color = dotColor.copy(alpha = 0.35f),
                    radius = h * 1.8f,
                    center = Offset(dotX, trackY)
                )
                drawCircle(
                    color = dotColor,
                    radius = h * 0.9f,
                    center = Offset(dotX, trackY)
                )
            }
        }
    }
}
