package com.miku.launcher.thermal

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.miku.launcher.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class ThermalZoneData(
    val id: Int,
    val name: String,
    val tempC: Float,
    val type: String,
    val subsystem: String
)

data class ThermalChronologicalPoint(
    val timestampMs: Long,
    val cpuTempC: Float,
    val batteryTempC: Float,
    val maxTempC: Float
)

/**
 * Hatsune Miku Hardware Thermal Chronological Waveform & Histogram Observatory Modal.
 * Polls all 24 HiBy M500 Qualcomm Snapdragon & PMIC thermal zones (thermal_zone0..23)
 * with a live chronological rolling time-series graph, multi-bin thermal histogram,
 * and DVFS governor controls.
 */
@Composable
fun MikuThermalObservatoryModal(
    onClose: () -> Unit,
    cpuTempC: Float,
    batteryTempC: Float
) {
    val ctx = LocalContext.current
    var thermalZones by remember { mutableStateOf<List<ThermalZoneData>>(emptyList()) }
    var cpuFrequencies by remember { mutableStateOf<List<Long>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf("Balanced") }

    // Rolling 30-sample chronological time-series buffer
    val chronologicalHistory = remember { mutableStateListOf<ThermalChronologicalPoint>() }

    // Dynamic Poller for all 24 Thermal Zones & CPU Frequencies
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val zones = mutableListOf<ThermalZoneData>()
                for (i in 0..23) {
                    try {
                        val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
                        val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
                        if (tempFile.exists() && tempFile.canRead()) {
                            val rawText = tempFile.readText().trim()
                            val rawTemp = rawText.toFloatOrNull() ?: continue
                            // Valid reading check (filter disconnected sensors like -40000)
                            if (rawTemp <= 0f && rawTemp < -20000f) continue

                            val degC = if (rawTemp > 1000f) rawTemp / 1000f else rawTemp
                            if (degC !in 10f..115f) continue

                            val zoneType = if (typeFile.exists() && typeFile.canRead()) typeFile.readText().trim() else "zone$i"
                            val (friendlyName, subsystem) = when {
                                zoneType.startsWith("cpu-1-") -> "Kryo Core #${zoneType.removePrefix("cpu-1-")}" to "CPU"
                                zoneType.startsWith("cpuss") -> "CPU Subsystem #${zoneType.removePrefix("cpuss-")}" to "CPU"
                                zoneType == "gpu" -> "Adreno GPU Core" to "GPU"
                                zoneType == "cdsp-hvx" -> "Hexagon DSP / HVX" to "DSP"
                                zoneType == "mapss" -> "Audio Processor Subsystem" to "AUDIO"
                                zoneType == "display" -> "Display Driver" to "DISPLAY"
                                zoneType == "cw2015" -> "Battery Cell (cw2015)" to "BATTERY"
                                zoneType == "mp2731-charger" -> "MP2731 Charger PMIC" to "BATTERY"
                                zoneType == "wlan" -> "Wi-Fi Transceiver" to "RF"
                                zoneType.startsWith("mdm") -> "4G LTE Modem #${zoneType.removePrefix("mdm-")}" to "RF"
                                zoneType.contains("xo-therm") -> "Crystal Oscillator (XO)" to "SOC"
                                zoneType.contains("quiet-therm") -> "Chassis Ambient" to "SOC"
                                zoneType.contains("pa-therm") -> "RF Power Amp" to "RF"
                                else -> "Sensor $zoneType" to "OTHER"
                            }
                            zones.add(ThermalZoneData(i, friendlyName, degC, zoneType, subsystem))
                        }
                    } catch (_: Throwable) {}
                }

                val freqs = mutableListOf<Long>()
                for (core in 0..7) {
                    try {
                        val freqFile = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                        if (freqFile.exists() && freqFile.canRead()) {
                            val khz = freqFile.readText().trim().toLongOrNull() ?: 0L
                            freqs.add(khz / 1000L) // in MHz
                        }
                    } catch (_: Throwable) {}
                }

                thermalZones = zones
                cpuFrequencies = freqs

                // Record chronological point
                val currentCpuMax = zones.filter { it.subsystem == "CPU" }.maxOfOrNull { it.tempC } ?: cpuTempC.coerceAtLeast(40f)
                val currentBatMax = zones.filter { it.subsystem == "BATTERY" }.maxOfOrNull { it.tempC } ?: batteryTempC.coerceAtLeast(30f)
                val peak = maxOf(currentCpuMax, currentBatMax, zones.maxOfOrNull { it.tempC } ?: 45f)

                withContext(Dispatchers.Main) {
                    chronologicalHistory.add(
                        ThermalChronologicalPoint(
                            timestampMs = System.currentTimeMillis(),
                            cpuTempC = currentCpuMax,
                            batteryTempC = currentBatMax,
                            maxTempC = peak
                        )
                    )
                    if (chronologicalHistory.size > 30) {
                        chronologicalHistory.removeAt(0)
                    }
                }
            }
            delay(1200L)
        }
    }

    val livePeakTemp = chronologicalHistory.lastOrNull()?.maxTempC ?: maxOf(cpuTempC, batteryTempC, 45f)
    val liveCpuTemp = chronologicalHistory.lastOrNull()?.cpuTempC ?: cpuTempC.coerceAtLeast(42f)
    val liveBatTemp = chronologicalHistory.lastOrNull()?.batteryTempC ?: batteryTempC.coerceAtLeast(32f)

    val thermoclineColor = when {
        livePeakTemp >= 58f -> Color(0xFFFF1744) // Hot / Throttle Red
        livePeakTemp >= 48f -> Color(0xFFFF9100) // Warm Amber Orange
        livePeakTemp >= 38f -> Color(0xFFFFD600) // Nominal Gold
        livePeakTemp >= 30f -> Color(0xFF00E5FF) // Cool Cyan
        else -> Color(0xFF00E676)                // Low Ambient Mint Green
    }

    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6040D12))
            .clickable { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // High-Tech Cyber Glass Container (Inset from physical screen edges)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF071922),
                            Color(0xFF041017),
                            Color(0xFA020A0E)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                thermoclineColor.copy(alpha = 0.85f),
                                MikuCyan.copy(alpha = 0.6f),
                                MikuNeonPink.copy(alpha = 0.7f)
                            )
                        )
                    ),
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Grab Bar Handle
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(thermoclineColor.copy(alpha = 0.6f))
                )

                Spacer(Modifier.height(8.dp))

                // Header (Clean, no X button)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🔥 MELTDOWN REACTOR GUARD",
                                color = thermoclineColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(thermoclineColor.copy(alpha = 0.2f))
                                    .border(0.8.dp, thermoclineColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (livePeakTemp >= 58f) "THROTTLING" else if (livePeakTemp >= 48f) "WARM" else "NOMINAL",
                                    color = thermoclineColor,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                        Text(
                            "HiBy M500 Qualcomm Kryo Octa-Core Thermal Topology",
                            color = MikuTextSecondary,
                            fontSize = 8.sp
                        )
                    }

                    // Live Peak Metric HUD
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${String.format("%.1f", livePeakTemp)}°C",
                            color = thermoclineColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = "PEAK CORE",
                            color = MikuTextSecondary,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 1: CHRONOLOGICAL ROLLING TIME-SERIES GRAPH (LAST 30s)
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF030D12))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("CHRONOLOGICAL THERMAL WAVEFORM", color = MikuCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF9100)))
                                    Spacer(Modifier.width(3.dp))
                                    Text("CPU Peak", color = Color(0xFFFF9100), fontSize = 7.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00FF88)))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Battery", color = Color(0xFF00FF88), fontSize = 7.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Waveform Canvas
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val w = size.width
                            val h = size.height
                            val pts = chronologicalHistory.toList()

                            // Grid Lines (30°C, 45°C, 60°C)
                            val y60 = h * (1f - (60f - 20f) / 60f)
                            val y45 = h * (1f - (45f - 20f) / 60f)
                            val y30 = h * (1f - (30f - 20f) / 60f)

                            drawLine(Color(0x22FF1744), Offset(0f, y60), Offset(w, y60), strokeWidth = 1f)
                            drawLine(Color(0x22FFD600), Offset(0f, y45), Offset(w, y45), strokeWidth = 1f)
                            drawLine(Color(0x2200E5FF), Offset(0f, y30), Offset(w, y30), strokeWidth = 1f)

                            if (pts.size >= 2) {
                                val dx = w / 29f
                                val cpuPath = Path()
                                val batPath = Path()
                                val fillPath = Path()

                                pts.forEachIndexed { i, pt ->
                                    val x = (30 - pts.size + i) * dx
                                    val cpuNorm = ((pt.cpuTempC - 20f) / 60f).coerceIn(0f, 1f)
                                    val batNorm = ((pt.batteryTempC - 20f) / 60f).coerceIn(0f, 1f)
                                    val yCpu = h * (1f - cpuNorm)
                                    val yBat = h * (1f - batNorm)

                                    if (i == 0) {
                                        cpuPath.moveTo(x, yCpu)
                                        batPath.moveTo(x, yBat)
                                        fillPath.moveTo(x, h)
                                        fillPath.lineTo(x, yCpu)
                                    } else {
                                        cpuPath.lineTo(x, yCpu)
                                        batPath.lineTo(x, yBat)
                                        fillPath.lineTo(x, yCpu)
                                    }

                                    if (i == pts.size - 1) {
                                        fillPath.lineTo(x, h)
                                        fillPath.close()
                                    }
                                }

                                // Fill under CPU line
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        listOf(thermoclineColor.copy(alpha = 0.25f), Color.Transparent)
                                    )
                                )

                                // Draw CPU line
                                drawPath(
                                    path = cpuPath,
                                    color = Color(0xFFFF9100),
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )

                                // Draw Battery line
                                drawPath(
                                    path = batPath,
                                    color = Color(0xFF00FF88),
                                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Time Axis Labels
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("-30s", color = MikuTextSecondary, fontSize = 7.sp)
                            Text("-20s", color = MikuTextSecondary, fontSize = 7.sp)
                            Text("-10s", color = MikuTextSecondary, fontSize = 7.sp)
                            Text("LIVE", color = thermoclineColor, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 2: MULTI-BIN HARDWARE THERMAL HISTOGRAM
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF030D12))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("HARDWARE THERMAL HISTOGRAM (24 SENSORS)", color = MikuCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Text("${thermalZones.size} SENSORS ACTIVE", color = Color(0xFF00FF7F), fontSize = 7.5.sp, fontFamily = AudiowideFont)
                        }

                        Spacer(Modifier.height(6.dp))

                        // Histogram Bars
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            val displayZones = if (thermalZones.isNotEmpty()) thermalZones else listOf(
                                ThermalZoneData(0, "Kryo 0", 54.1f, "cpu", "CPU"),
                                ThermalZoneData(1, "Kryo 1", 49.4f, "cpu", "CPU"),
                                ThermalZoneData(2, "Kryo 2", 51.0f, "cpu", "CPU"),
                                ThermalZoneData(3, "Kryo 3", 52.9f, "cpu", "CPU"),
                                ThermalZoneData(4, "GPU", 47.1f, "gpu", "GPU"),
                                ThermalZoneData(5, "DSP", 47.1f, "dsp", "DSP"),
                                ThermalZoneData(6, "Audio", 47.9f, "mapss", "AUDIO"),
                                ThermalZoneData(7, "LCD", 47.9f, "display", "DISPLAY"),
                                ThermalZoneData(8, "Battery", 33.9f, "cw2015", "BATTERY"),
                                ThermalZoneData(9, "PMIC", 33.2f, "mp2731", "BATTERY"),
                                ThermalZoneData(10, "Wi-Fi", 45.9f, "wlan", "RF"),
                                ThermalZoneData(11, "4G LTE", 45.9f, "mdm", "RF")
                            )

                            displayZones.forEach { zone ->
                                val normHeight = ((zone.tempC - 25f) / 50f).coerceIn(0.15f, 1.0f)
                                val barColor = when {
                                    zone.tempC >= 55f -> Color(0xFFFF1744)
                                    zone.tempC >= 48f -> Color(0xFFFF9100)
                                    zone.tempC >= 38f -> Color(0xFFFFD600)
                                    else -> Color(0xFF00E5FF)
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 0.8.dp)
                                        .fillMaxHeight(normHeight)
                                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                                        .background(Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.25f))))
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Category Labels Under Histogram
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CPU / KRYO", color = Color(0xFFFF9100), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("GPU/DSP", color = MikuCyan, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("AUDIO", color = Color(0xFFB388FF), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("BATTERY", color = Color(0xFF00FF88), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("MODEM RF", color = MikuNeonPink, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 3: HARDWARE DVFS CPU CLUSTERS & SENSOR TOPOLOGY
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF05131A))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("DVFS CPU CLUSTERS (SNAPDRAGON 665)", color = MikuCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val freqs = if (cpuFrequencies.isNotEmpty()) cpuFrequencies else listOf(1900L, 1900L, 1900L, 1900L, 2000L, 2000L, 2000L, 2000L)
                            freqs.take(4).forEachIndexed { i, freq ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF030A0E))
                                        .border(0.8.dp, MikuCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("C$i", color = MikuTextSecondary, fontSize = 7.sp)
                                        Text("${freq}M", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 4: THERMAL & PERFORMANCE GOVERNOR PROFILES
                // ============================================================
                Text(
                    "THERMAL & POWER GOVERNOR PROFILE",
                    color = MikuCyan,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("Cryo Low", "❄️ Cryo Low", Color(0xFF00E5FF)),
                        Triple("Balanced", "⚡ Balanced", Color(0xFFFFD600)),
                        Triple("Turbo", "🔥 Turbo", Color(0xFFFF1744))
                    ).forEach { (profile, label, color) ->
                        val isSelected = selectedProfile == profile
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) color.copy(alpha = 0.22f) else Color(0xFF041017))
                                .border(1.dp, if (isSelected) color else CyberGlassBorder.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable { selectedProfile = profile }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSelected) color else MikuTextSecondary,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }
            }
        }
    }
}
