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

    // Live Telemetry Poll. Initial values are EMPTY/0 (rendered "—"), never presumed clocks or RAM.
    // The placeholder row is sized to the device's REAL core count (was a hardcoded 8, which drew
    // eight permanently-"—" bars on any other SoC). Values stay null until a real sysfs read lands.
    val cpuFreqs by produceState<List<Int?>>(
        initialValue = List(Runtime.getRuntime().availableProcessors().coerceIn(1, 16)) { null }
    ) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveCpuFreqs()
            }
            delay(1200)
        }
    }

    val memoryState by produceState(initialValue = Pair(0L, 0L)) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveMemory(ctx)
            }
            delay(2000)
        }
    }

    // Real, cheap one-shot reads for the identity/capability pills.
    val facts = remember { readAnatomyFacts(ctx) }
    val netState by com.miku.launcher.network.MikuNetworkService.state.collectAsState()

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
                        Triple("EYES", "👁️ Optic Vision", com.miku.launcher.ui.MikuIdentity.Gold),
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
                                // No ANR sentinel exists; the card shows threads/heap/RAM/uptime.
                                subtitle = "Threads, JVM heap, system RAM & uptime",
                                accentColor = Color(0xFF00FF7F)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    // Real process facts (was "SENTINEL ONLINE" / "ANR IMMUNITY 100%": asserted, unmeasured).
                                    MetricPill("THREADS", "${Thread.activeCount()}", Color(0xFF00FF7F))
                                    val rt = Runtime.getRuntime()
                                    MetricPill("JVM HEAP", "${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}M / ${rt.maxMemory() / (1024 * 1024)}M", MikuCyan)
                                    MetricPill("SYSTEM RAM", if (memoryState.second > 0) "${memoryState.first}M / ${memoryState.second}M" else "—", com.miku.launcher.ui.MikuIdentity.Gold)
                                    val hrs = uptimeSec / 3600
                                    val mins = (uptimeSec % 3600) / 60
                                    MetricPill("UPTIME", "${hrs}h ${mins}m", Color(0xFFB388FF))
                                }
                            }
                        }
                    }

                    // AUDIO EARS (DAC settings as actually readable from sysfs / vendor globals)
                    if (activeOrgan in listOf("ALL", "EARS")) {
                        item {
                            AnatomicalOrganCard(
                                title = "ACOUSTIC EARS // DAC",
                                organIcon = "🎧",
                                // CirrusLogicManager falls back to its own prefs mirror when the DAC
                                // sysfs node is unreadable, so this is not always a hardware readback.
                                subtitle = "Digital filter, analog gain & DRE — DAC node if readable, else the last value this app wrote",
                                accentColor = Color(0xFFB388FF)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        // Chip identity is a spec label, not a per-chip liveness probe — say so.
                                        MetricPill("DAC (SPEC)", "CS43198 ×2", Color(0xFFB388FF))
                                        MetricPill("OUTPUT ROUTE", facts.audioOutput, Color(0xFFB388FF))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        MetricPill("OUTPUT GAIN", facts.dacGain, Color(0xFF00FF7F))
                                        MetricPill("DIGITAL FILTER", facts.dacFilter, MikuCyan)
                                        MetricPill("DRE", facts.dacDre, Color(0xFFFF4081))
                                    }
                                }
                            }
                        }
                    }

                    // OPTIC EYES (Light & Display Matrix — from SensorManager / Display, honest "none")
                    if (activeOrgan in listOf("ALL", "EYES")) {
                        item {
                            AnatomicalOrganCard(
                                title = "OPTIC EYES // VISION MATRIX",
                                organIcon = "👁️",
                                subtitle = "Ambient light sensor, panel refresh & colour gamut",
                                accentColor = com.miku.launcher.ui.MikuIdentity.Gold
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("LIGHT SENSOR", facts.lightSensor, com.miku.launcher.ui.MikuIdentity.Gold)
                                    MetricPill("REFRESH", facts.refreshHz, MikuCyan)
                                    MetricPill("GAMUT", facts.gamut, Color(0xFF00FF7F))
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
                                // SoC name from Build.SOC_*, not a hardcoded "Qualcomm Snapdragon
                                // Kryo Octa-Core" assertion (the core count was hardcoded too).
                                subtitle = realSocLabel()?.let { "$it · per-core cpufreq" }
                                    ?: "SoC not reported by the build · per-core cpufreq",
                                accentColor = MikuNeonPink
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "CPU CLUSTER SPECTRUM · ${cpuFreqs.size} CORES (MHz)",
                                        color = MikuTextSecondary,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        cpuFreqs.forEachIndexed { i, freqOrNull ->
                                            Column(
                                                Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                // null = cpufreq node unreadable for this core → flat bar + "—".
                                                val freq = freqOrNull ?: 0
                                                val barHeight = if (freqOrNull == null) 4f else (freq / 2400f * 24f).coerceIn(4f, 24f)
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
                                                    if (freqOrNull == null) "—" else "${freq / 1000f}G",
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
                                // Only the three pills below are actually probed; "ALSA Direct PCM"
                                // and "MSEB Engine" were never queried from anything.
                                subtitle = "Native mixer rate, FM node visibility & published now-playing format",
                                accentColor = MikuCyan
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    // Native mixer rate/buffer from AudioManager; FM node visibility probed, not asserted.
                                    MetricPill("MIXER", facts.mixerFormat, MikuCyan)
                                    MetricPill("FM /dev/radio0", facts.fmNode, Color(0xFF00FF7F))
                                    MetricPill("NOW PLAYING FMT", facts.nowPlayingFormat, Color(0xFFB388FF))
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
                                    // Live link from MikuNetworkService; LED/SD presence probed from the system.
                                    val link = when {
                                        netState.wifi.isConnected -> "Wi-Fi ${netState.wifi.standard.ifBlank { netState.wifi.bandLabel }}".trim()
                                        netState.cellular.dataConnected -> "Cellular ${netState.cellular.networkType.ifBlank { "" }}".trim()
                                        netState.cellular.hasSignal -> "Cell signal · no data"
                                        netState.lastUpdated == 0L -> "—"
                                        else -> "Offline"
                                    }
                                    MetricPill("RADIO LINK", link, Color(0xFF00E5FF))
                                    MetricPill("LED DRIVER", facts.ledDriver, com.miku.launcher.ui.MikuIdentity.Gold)
                                    MetricPill("SD CARD", facts.sdCard, Color(0xFF00FF7F))
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

/**
 * Per-core MHz from cpufreq sysfs; null for a core whose node cannot be read (no presumed clock).
 * The core COUNT is now the device's real one (was a hardcoded 0..7, which drew eight bars on any
 * SoC and presented the surplus as permanently "—" cores).
 */
private fun readLiveCpuFreqs(): List<Int?> {
    val cores = try {
        java.io.File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
            ?.size
            ?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
    } catch (_: Throwable) { Runtime.getRuntime().availableProcessors() }
    return (0 until cores.coerceIn(1, 16)).map { i ->
        try {
            val f = java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (f.exists() && f.canRead()) f.readText().trim().toIntOrNull()?.let { it / 1000 } else null
        } catch (_: Throwable) { null }
    }
}

/** Real SoC name from the build, never a hardcoded "Snapdragon Kryo". Blank/UNKNOWN → null. */
private fun realSocLabel(): String? = try {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val model = android.os.Build.SOC_MODEL
        val manu = android.os.Build.SOC_MANUFACTURER
        val combined = listOf(manu, model)
            .filter { it.isNotBlank() && !it.equals("unknown", true) }
            .joinToString(" ")
        combined.takeIf { it.isNotBlank() }
    } else null
} catch (_: Throwable) { null }

/** (used MB, total MB) from ActivityManager; (0, 0) when unavailable → rendered "—". */
private fun readLiveMemory(ctx: Context): Pair<Long, Long> {
    return try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        Pair(totalMb - availMb, totalMb)
    } catch (_: Throwable) {
        Pair(0L, 0L)
    }
}

/**
 * One-shot, real reads for the identity/capability pills. Every field is "—" or an explicit
 * "none"/"not visible" when the platform does not expose it — nothing is asserted.
 */
private data class AnatomyFacts(
    val dacGain: String,
    val dacFilter: String,
    val dacDre: String,
    val audioOutput: String,
    val lightSensor: String,
    val refreshHz: String,
    val gamut: String,
    val mixerFormat: String,
    val fmNode: String,
    val nowPlayingFormat: String,
    val ledDriver: String,
    val sdCard: String
)

private fun readAnatomyFacts(ctx: Context): AnatomyFacts {
    val dacGain = runCatching { com.miku.launcher.CirrusLogicManager.getGainModeOrNull(ctx)?.label }.getOrNull() ?: "—"
    val dacFilter = runCatching { com.miku.launcher.CirrusLogicManager.getDigitalFilterOrNull(ctx)?.label }.getOrNull() ?: "—"
    val dacDre = runCatching { com.miku.launcher.CirrusLogicManager.isDreEnabledOrNull(ctx) }.getOrNull()
        ?.let { if (it) "ENABLED" else "OFF" } ?: "—"

    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val audioOutput = runCatching {
        val devs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        val pick = devs.firstOrNull { it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY }
            ?: devs.firstOrNull()
        when (pick?.type) {
            null -> "—"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired jack"
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Type ${pick.type}"
        }
    }.getOrDefault("—")
    val mixerFormat = runCatching {
        val sr = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val fpb = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        if (sr.isNullOrBlank()) "—" else "$sr Hz · ${fpb ?: "—"} fr/buf"
    }.getOrDefault("—")

    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    val lightSensor = runCatching {
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)?.name?.let { "$it" } ?: "None on this device"
    }.getOrDefault("—")

    val display = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
    }.getOrNull()
    val refreshHz = display?.let { "${"%.0f".format(it.refreshRate)} Hz" } ?: "—"
    val gamut = display?.let { if (it.isWideColorGamut) "Wide gamut" else "sRGB" } ?: "—"

    val fmNode = runCatching {
        val f = java.io.File("/dev/radio0")
        if (f.exists()) (if (f.canRead()) "present · readable" else "present · no access") else "not visible"
    }.getOrDefault("not visible")
    val nowPlayingFormat = runCatching {
        android.provider.Settings.Global.getString(ctx.contentResolver, "miku_now_playing_format")?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "idle"

    val ledDriver = runCatching {
        val nodes = listOf("/sys/class/leds/sgm31324-leds", "/sys/class/leds/red", "/sys/class/leds/blue")
        if (nodes.any { java.io.File(it).exists() }) "sysfs present" else "not visible"
    }.getOrDefault("not visible")
    val sdCard = runCatching {
        val smgr = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val removable = smgr?.storageVolumes?.firstOrNull { it.isRemovable }
        when {
            removable == null -> "No card"
            removable.state == android.os.Environment.MEDIA_MOUNTED -> "Mounted"
            else -> removable.state
        }
    }.getOrDefault("—")

    return AnatomyFacts(
        dacGain = dacGain, dacFilter = dacFilter, dacDre = dacDre, audioOutput = audioOutput,
        lightSensor = lightSensor, refreshHz = refreshHz, gamut = gamut,
        mixerFormat = mixerFormat, fmNode = fmNode, nowPlayingFormat = nowPlayingFormat,
        ledDriver = ledDriver, sdCard = sdCard
    )
}
