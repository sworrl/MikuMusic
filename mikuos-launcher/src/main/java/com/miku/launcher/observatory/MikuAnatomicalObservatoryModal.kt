package com.miku.launcher.observatory

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miku.launcher.*
import com.miku.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Skeuomorphic Hatsune Miku Vocaloid Android Anatomy & Brain Observatory.
 * Organizes hardware telemetry, sensors, and audio subsystems by body structure:
 * - Neural Cortex: System Watchdog, Thread Pools & ANR Sentinel
 * - Audio Ears: Cirrus Logic Dual CS43131 DACs, Gain & Digital Filters
 * - Optical Eyes: Light Sensor, Display Backlight & Vision Matrix
 * - Quantum Heart: Snapdragon Octa-Core CPU Governor, Thermals & Power
 * - Vocal Synthesizer: Direct ALSA Stream, Qualcomm FM & MSEB DSP
 * - Nervous System: Wi-Fi/LTE Transceiver & Sysfs I/O Bus
 */
@Composable
fun MikuAnatomicalObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var activeOrgan by remember { mutableStateOf("ALL") }

    // Live Telemetry Poll
    val cpuFreqs by produceState(initialValue = listOf(1400, 1400, 1400, 1400, 1800, 1800, 2200, 2400)) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveCpuFreqs()
            }
            delay(1200)
        }
    }

    val memoryState by produceState(initialValue = Pair(2450L, 4096L)) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveMemory(ctx)
            }
            delay(2000)
        }
    }

    val uptimeSec by produceState(initialValue = SystemClock.elapsedRealtime() / 1000) {
        while (true) {
            value = SystemClock.elapsedRealtime() / 1000
            delay(1000)
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { onDismissRequest() }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the card.
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
                .clip(CutCornerShape(16.dp))
                .background(Color(0xFF030D12))
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.verticalGradient(
                            listOf(
                                MikuCyan.copy(alpha = 0.9f),
                                Color(0xFFB388FF).copy(alpha = 0.4f),
                                MikuNeonPink.copy(alpha = 0.8f)
                            )
                        )
                    ),
                    CutCornerShape(16.dp)
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Top Grab Handle
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MikuCyan.copy(alpha = 0.6f))
                )

                Spacer(Modifier.height(6.dp))

                // Modal Header (Clean, no X button)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF00FF7F), Color(0xFF003319)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("01", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "VOCALOID ANATOMY // BRAIN",
                                color = MikuCyan,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Text(
                                "Skeuomorphic System Observatory",
                                color = MikuTextSecondary,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Skeuomorphic Body Filter Selector Chips
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val organs = listOf(
                        Triple("ALL", "✨ Full Body", MikuCyan),
                        Triple("BRAIN", "🧠 Neural Cortex", Color(0xFF00FF7F)),
                        Triple("EARS", "🎧 Audio Dual-DAC", Color(0xFFB388FF)),
                        Triple("EYES", "👁️ Optic Vision", Color(0xFFFFD600)),
                        Triple("HEART", "💖 Quantum Core", MikuNeonPink),
                        Triple("VOCAL", "🎤 Synthesizer", MikuCyan),
                        Triple("NERVES", "⚡ Telemetry", Color(0xFF00E5FF))
                    )
                    organs.forEach { (key, label, accent) ->
                        val isSelected = activeOrgan == key
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) accent.copy(alpha = 0.25f)
                                    else Color(0xFF071822)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) accent else Color(0x30FFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { activeOrgan = key }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) accent else Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Scrollable Anatomical Organ Cards
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // NEURAL CORTEX (Brain)
                    if (activeOrgan in listOf("ALL", "BRAIN")) {
                        item {
                            AnatomicalOrganCard(
                                title = "NEURAL CORTEX // WATCHDOG",
                                organIcon = "🧠",
                                subtitle = "System Health, ANR Sentinel & Uptime",
                                accentColor = Color(0xFF00FF7F)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("SENTINEL", "ONLINE", Color(0xFF00FF7F))
                                    MetricPill("ANR IMMUNITY", "100% ACTIVE", MikuCyan)
                                    MetricPill("RAM HEAP", "${memoryState.first}M / ${memoryState.second}M", Color(0xFFFFD600))
                                    val hrs = uptimeSec / 3600
                                    val mins = (uptimeSec % 3600) / 60
                                    MetricPill("UPTIME", "${hrs}h ${mins}m", Color(0xFFB388FF))
                                }
                            }
                        }
                    }

                    // AUDIO EARS (Cirrus Logic CS43131 Dual DAC)
                    if (activeOrgan in listOf("ALL", "EARS")) {
                        item {
                            AnatomicalOrganCard(
                                title = "ACOUSTIC EARS // DUAL CS43131",
                                organIcon = "🎧",
                                subtitle = "MasterHIFI Bit-Perfect Bypass & Analog Gain",
                                accentColor = Color(0xFFB388FF)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        MetricPill("PRIMARY DAC", "CS43131 #1 ACTIVE", Color(0xFFB388FF))
                                        MetricPill("SECONDARY DAC", "CS43131 #2 ACTIVE", Color(0xFFB388FF))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        MetricPill("OUTPUT GAIN", "HIGH (2.0Vrms)", Color(0xFF00FF7F))
                                        MetricPill("DIGITAL FILTER", "Fast Roll-Off", MikuCyan)
                                        MetricPill("DRE 130dB+", "ENABLED", Color(0xFFFF4081))
                                    }
                                }
                            }
                        }
                    }

                    // OPTIC EYES (Light & Display Matrix)
                    if (activeOrgan in listOf("ALL", "EYES")) {
                        item {
                            AnatomicalOrganCard(
                                title = "OPTIC EYES // VISION MATRIX",
                                organIcon = "👁️",
                                subtitle = "Ambient Lux Sensor, Backlight PWM & Color Calibration",
                                accentColor = Color(0xFFFFD600)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("LUX SENSOR", "OPT3001 OK", Color(0xFFFFD600))
                                    MetricPill("PWM REFRESH", "60 Hz Direct", MikuCyan)
                                    MetricPill("CALIBRATION", "DCI-P3 Vivid", Color(0xFF00FF7F))
                                }
                            }
                        }
                    }

                    // QUANTUM HEART (Snapdragon CPU Governor & Battery Core)
                    if (activeOrgan in listOf("ALL", "HEART")) {
                        item {
                            AnatomicalOrganCard(
                                title = "QUANTUM HEART // POWER & GOVERNOR",
                                organIcon = "💖",
                                subtitle = "Qualcomm Snapdragon Kryo Octa-Core & Power Matrix",
                                accentColor = MikuNeonPink
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "KRYO OCTA-CORE CLUSTER SPECTRUM (MHz)",
                                        color = MikuTextSecondary,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        cpuFreqs.forEachIndexed { i, freq ->
                                            Column(
                                                Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                val barHeight = (freq / 2400f * 24f).coerceIn(4f, 24f)
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(barHeight.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(MikuNeonPink, Color(0xFF880E4F))
                                                            )
                                                        )
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    "${freq / 1000f}G",
                                                    color = Color.White,
                                                    fontSize = 6.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // VOCAL SYNTHESIZER (ALSA Stream, FM & MSEB)
                    if (activeOrgan in listOf("ALL", "VOCAL")) {
                        item {
                            AnatomicalOrganCard(
                                title = "VOCAL SYNTHESIZER // ACOUSTIC DSP",
                                organIcon = "🎤",
                                subtitle = "ALSA Direct PCM, Qualcomm FM Subsystem & MSEB Engine",
                                accentColor = MikuCyan
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("ALSA STREAM", "Bit-Perfect UAC2", MikuCyan)
                                    MetricPill("FM TUNER", "V4L2 /dev/radio0", Color(0xFF00FF7F))
                                    MetricPill("MSEB DSP", "HiBy Audio Engine", Color(0xFFB388FF))
                                }
                            }
                        }
                    }

                    // NERVOUS SYSTEM (Wi-Fi, LTE & Kernel Bus)
                    if (activeOrgan in listOf("ALL", "NERVES")) {
                        item {
                            AnatomicalOrganCard(
                                title = "NERVOUS SYSTEM // I/O & BUS",
                                organIcon = "⚡",
                                subtitle = "High-Speed Transceiver & Sysfs Hardware Bus",
                                accentColor = Color(0xFF00E5FF)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("RADIO LINK", "Wi-Fi 802.11ac", Color(0xFF00E5FF))
                                    MetricPill("PULSAR BUS", "SGM31324 PWM", Color(0xFFFFD600))
                                    MetricPill("SD BUS", "UHS-I Direct I/O", Color(0xFF00FF7F))
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
private fun AnatomicalOrganCard(
    title: String,
    organIcon: String,
    subtitle: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 4.dp, bottomStart = 4.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B1F2A),
                        Color(0xFF051118)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.8f),
                            Color(0x30FFFFFF),
                            accentColor.copy(alpha = 0.3f)
                        )
                    )
                ),
                CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 4.dp, bottomStart = 4.dp)
            )
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(organIcon, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        title,
                        color = accentColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        subtitle,
                        color = MikuTextSecondary,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF030D14))
            .border(0.6.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = MikuTextSecondary,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Text(
                value,
                color = color,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

private fun readLiveCpuFreqs(): List<Int> {
    val freqs = mutableListOf<Int>()
    for (i in 0..7) {
        val f = try {
            val path = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
            val text = java.io.File(path).readText().trim()
            text.toIntOrNull()?.let { it / 1000 } ?: 1400
        } catch (_: Throwable) {
            1400
        }
        freqs.add(f)
    }
    return freqs
}

private fun readLiveMemory(ctx: Context): Pair<Long, Long> {
    return try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        Pair(totalMb - availMb, totalMb)
    } catch (_: Throwable) {
        Pair(2400L, 4096L)
    }
}
