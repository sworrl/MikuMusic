package com.miku.launcher.bpm

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.audio.MikuSeasonalAudioEngine
import com.miku.launcher.haptics.MikuTactileHaptics
import com.miku.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// =========================================================================
// 🌸 ULTRA KAWAII SWEET PASTEL PALETTE & JUICY ARCADE THEME
// =========================================================================
private val KawaiiSakuraPink = Color(0xFFFF85B3)
private val KawaiiHotPink = Color(0xFFFF3385)
private val KawaiiMikuMint = Color(0xFF39C5BB)
private val KawaiiSoftTeal = Color(0xFF66E0D8)
private val KawaiiLavender = Color(0xFFDFB8FF)
private val KawaiiGoldenHoney = Color(0xFFFFD166)
private val KawaiiPeach = Color(0xFFFF9E80)
private val KawaiiCardBg = Color(0xEE140A18)
private val KawaiiSurface = Color(0xF2200E26)
private val KawaiiTextMuted = Color(0xFFD8B4E2)

/**
 * Hatsune Miku Ultra-Kawaii BPM Observatory & Project DIVA Rhythm Arcade.
 * Packed with juicy tactile visual feedback, rainbow fever gauges, SSS+ performance grades,
 * particle explosions, and high-readability typography for the HiBy M500 DAP screen.
 */
@Composable
fun MikuBpmObservatoryModal(
    onClose: () -> Unit,
    bpmState: MikuBpmEngine.BpmState
) {
    val ctx = LocalContext.current
    val bpmDb = remember { MikuBpmDatabase.getInstance(ctx) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        MikuBeatClickerEngine.init(ctx)
        MikuBpmSeasonsEngine.init(ctx)
    }

    // Modal Mode: 0 = Kawaii Beat Match, 1 = Sweet Leek Clicker, 2 = Producer Skills, 3 = Skins & Textures, 4 = Quests & DB, 5 = Seasons
    var selectedMode by remember { mutableIntStateOf(0) }

    val liveBpm = if (bpmState.bpm.isFinite() && bpmState.bpm in 20f..999f) bpmState.bpm else 120f
    val isPlaying = bpmState.isPlaying
    val beatIntervalMs = bpmState.beatIntervalMs.coerceIn(60L, 3000L)

    val cr = ctx.contentResolver
    val trackTitle = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_title") ?: "World is Mine" } catch (_: Throwable) { "World is Mine" }
    }
    val trackArtist = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_artist") ?: "supercell feat. Hatsune Miku" } catch (_: Throwable) { "supercell feat. Hatsune Miku" }
    }

    var calculatedTapBpm by remember { mutableStateOf<Float?>(null) }
    var dbStats by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }

    // Auto-resolve canonical BPM from SQLite / Preseeded dictionary on track change
    LaunchedEffect(trackArtist, trackTitle) {
        dbStats = bpmDb.getCalibrationStats()
        val rec = bpmDb.resolveCanonicalBpm(trackArtist, trackTitle)
        if (rec != null && rec.canonicalBpm > 0f) {
            calculatedTapBpm = rec.canonicalBpm
            try {
                android.provider.Settings.Global.putFloat(ctx.contentResolver, "miku_live_bpm", rec.canonicalBpm)
                android.provider.Settings.Global.putInt(ctx.contentResolver, "miku_beat_interval_ms", (60000f / rec.canonicalBpm).toInt())
                val intent = android.content.Intent("com.miku.action.BPM_UPDATE").apply {
                    putExtra("bpm", rec.canonicalBpm)
                    putExtra("beat_interval_ms", (60000f / rec.canonicalBpm).toLong())
                    putExtra("is_playing", isPlaying)
                }
                ctx.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    // High-Precision Metronome & Approach Clocks
    var beatTick by remember { mutableLongStateOf(0L) }
    var lastBeatEpochMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying, beatIntervalMs, bpmState.lastPulseEpochMs) {
        if (!isPlaying) {
            beatTick = 0L
            return@LaunchedEffect
        }
        while (true) {
            lastBeatEpochMs = SystemClock.elapsedRealtime()
            beatTick += 1
            delay(beatIntervalMs)
        }
    }

    // Incremental Game Loop Tick (200ms interval for 0 lag)
    LaunchedEffect(liveBpm, isPlaying) {
        while (true) {
            delay(200L)
            MikuBeatClickerEngine.tick(0.2, if (isPlaying) liveBpm else 120f)
        }
    }

    // Approach Ring Phase: 1.6f -> 0.0f
    val beatApproachAnim = remember { Animatable(1f) }
    val beatPulseScale = remember { Animatable(1f) }
    LaunchedEffect(isPlaying, beatTick) {
        if (!isPlaying || beatTick == 0L) {
            beatApproachAnim.snapTo(1f)
            beatPulseScale.snapTo(1f)
            return@LaunchedEffect
        }
        launch {
            beatApproachAnim.snapTo(1.6f)
            beatApproachAnim.animateTo(0f, tween(beatIntervalMs.toInt().coerceAtLeast(60), easing = LinearEasing))
        }
        launch {
            beatPulseScale.snapTo(1.18f)
            beatPulseScale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    // Game Economy & Customization State
    val leekCount by MikuBeatClickerEngine.leeks.collectAsState()
    val clickerBuildings by MikuBeatClickerEngine.buildings.collectAsState()
    val clickerSkills by MikuBeatClickerEngine.skills.collectAsState()
    val currentSkin by MikuBeatClickerEngine.currentSkin.collectAsState()
    val achievements by MikuBeatClickerEngine.achievements.collectAsState()
    val clickerCombo by MikuBeatClickerEngine.combo.collectAsState()
    val feverEnergy by MikuBeatClickerEngine.feverEnergy.collectAsState()
    val feverSeconds by MikuBeatClickerEngine.feverSeconds.collectAsState()
    val goldenLeekVisible by MikuBeatClickerEngine.goldenLeekVisible.collectAsState()
    val floatingTexts by MikuBeatClickerEngine.floatingTexts.collectAsState()
    val activeParticles by MikuBeatClickerEngine.particles.collectAsState()

    val activeSkinPrimary = Color(currentSkin.primaryColor)
    val activeSkinAccent = Color(currentSkin.accentColor)

    val currentSeason by MikuBpmSeasonsEngine.currentSeason.collectAsState()
    val lifetimeStats by MikuBpmSeasonsEngine.lifetime.collectAsState()

    // Real-Time Hit Timing State
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    var tapCounter by remember { mutableIntStateOf(0) }
    var timingOffsetMs by remember { mutableIntStateOf(0) }
    var judgmentTitle by remember { mutableStateOf("") }
    var judgmentColor by remember { mutableStateOf(KawaiiSakuraPink) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var perfectShockwaveTrigger by remember { mutableIntStateOf(0) }

    BackHandler(enabled = true) {
        MikuBeatClickerEngine.saveState()
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD90E0514))
            .clickable {
                MikuBeatClickerEngine.saveState()
                onClose()
            }
            .swipeUpFromBottomToDismiss(onDismiss = {
                MikuBeatClickerEngine.saveState()
                onClose()
            }),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Kawaii Pastel Card Sheet with Polka-Dot Texture
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(KawaiiSurface, KawaiiCardBg, Color(0xFF0C0310))
                    )
                )
                .border(
                    BorderStroke(
                        2.5.dp,
                        Brush.linearGradient(
                            listOf(
                                if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink,
                                KawaiiMikuMint,
                                KawaiiLavender
                            )
                        )
                    ),
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Floating Kawaii Heart & Star Particles Canvas
            KawaiiDreamyHeartCanvas(
                modifier = Modifier.fillMaxSize(),
                isFever = feverSeconds > 0
            )

            // Dynamic Hit Particles System
            KawaiiJuicyParticleOverlay(
                particles = activeParticles,
                modifier = Modifier.fillMaxSize()
            )

            // Layout Column (Single Page, 0 Scroll)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. TOP HEADER & CUTE MODE PILLS + FEVER GAUGE
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Cute Cloud Pill Handle
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(5.5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(KawaiiSakuraPink.copy(alpha = 0.7f))
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 6 Gamified Mode Tabs (Horizontally Scrollable)
                        LazyRow(
                            Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x40000000))
                                .border(1.2.dp, activeSkinPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item {
                                KawaiiModeTabPill("🌸 RHYTHM", selectedMode == 0, activeSkinPrimary) { selectedMode = 0 }
                            }
                            item {
                                KawaiiModeTabPill("🥬 HARVEST", selectedMode == 1, KawaiiGoldenHoney) { selectedMode = 1 }
                            }
                            item {
                                KawaiiModeTabPill("🧲 SKILLS", selectedMode == 2, KawaiiSoftTeal) { selectedMode = 2 }
                            }
                            item {
                                KawaiiModeTabPill("🎨 SKINS", selectedMode == 3, KawaiiLavender) { selectedMode = 3 }
                            }
                            item {
                                KawaiiModeTabPill("🏆 QUESTS", selectedMode == 4, Color(0xFFFFD700)) { selectedMode = 4 }
                            }
                            item {
                                KawaiiModeTabPill("${currentSeason.rank.badge} SEASONS", selectedMode == 5, Color(currentSeason.rank.colorHex)) { selectedMode = 5 }
                            }
                        }

                        // Close button
                        IconButton(
                            onClick = {
                                MikuBeatClickerEngine.saveState()
                                onClose()
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FF85B3))
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = activeSkinPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Rainbow Sugar Fever Gauge
                    KawaiiSugarFeverGauge(
                        feverEnergy = feverEnergy,
                        feverSeconds = feverSeconds,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 2. HERO SECTION: TIMING & TRACK CARDS
                if (selectedMode == 0) {
                    // MODE 0: KAWAII BEAT MATCH HERO (LIVE BPM + PRECISION OFFSET GAUGE + SSS+ GRADE)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Kawaii BPM Orb Badge
                        Box(
                            Modifier
                                .size(124.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.radialGradient(
                                        listOf(activeSkinPrimary.copy(alpha = 0.35f), Color(0x22140A18))
                                    )
                                )
                                .border(1.5.dp, activeSkinPrimary.copy(alpha = 0.7f), RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isPlaying) "💖" else "⏸️", fontSize = 16.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (isPlaying) String.format(Locale.US, "%.0f", liveBpm) else "--",
                                        color = Color.White,
                                        fontSize = 35.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }
                                Text(
                                    text = if (isPlaying) "BEAT TEMPO" else "PAUSED",
                                    color = if (isPlaying) activeSkinPrimary else KawaiiTextMuted,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                                Text(
                                    text = if (isPlaying) "${liveBpm.toInt()} BPM · ${beatIntervalMs}ms" else "ALSA Standby",
                                    color = KawaiiSoftTeal,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Right: Live Track Card + Rhythm Timing Deviation Gauge + Arcade Grade
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0x33000000))
                                .border(1.5.dp, KawaiiMikuMint.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = if (isPlaying) "♪ $trackTitle" else "🌸 MikuOS Player",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isPlaying) trackArtist else "Bit-Perfect DTA Direct",
                                        color = KawaiiMikuMint,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                // Arcade Performance Grade Badge
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x44FF3385))
                                        .border(1.dp, KawaiiHotPink, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = MikuBeatClickerEngine.sessionGrade,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }

                            // Visual Rhythm Timing Calibration Gauge
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("EARLY (-ms)", color = KawaiiPeach, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (lastTapTimeMs > 0) {
                                            when {
                                                abs(timingOffsetMs) <= 35 -> "💖 EXACT (0ms)"
                                                timingOffsetMs < 0 -> "⚠️ ${timingOffsetMs}ms EARLY"
                                                else -> "⚠️ +${timingOffsetMs}ms LATE"
                                            }
                                        } else "TAP ON BEAT",
                                        color = judgmentColor,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Text("LATE (+ms)", color = KawaiiSoftTeal, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(3.dp))
                                // Horizontal Target Bar with Deviation Pointer
                                KawaiiTimingDeviationBar(offsetMs = timingOffsetMs, maxWindowMs = (beatIntervalMs / 3).coerceIn(80L, 250L))
                            }
                        }
                    }
                } else if (selectedMode == 1) {
                    // MODE 1: HARVEST CLICKER HERO
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x33000000))
                            .border(1.5.dp, if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink, RoundedCornerShape(22.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("SWEET LEEK HARVEST 🥬", color = KawaiiMikuMint, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                Text(
                                    MikuBeatClickerEngine.formatNumber(leekCount),
                                    color = if (feverSeconds > 0) KawaiiGoldenHoney else Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val bps = MikuBeatClickerEngine.getEffectiveBps(liveBpm)
                                Text(
                                    "+${MikuBeatClickerEngine.formatNumber(bps)}/s",
                                    color = KawaiiSoftTeal,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                                Text(
                                    if (feverSeconds > 0) "🔥 77x FEVER (${feverSeconds}s)" else "⚡ BPM Boost Active",
                                    color = if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (clickerCombo > 0) {
                                val comboTier = when {
                                    clickerCombo >= 50 -> "🌟 MAX OVERDRIVE"
                                    clickerCombo >= 25 -> "✨ FEVER FRENZY"
                                    clickerCombo >= 10 -> "💖 SWEET RHYTHM"
                                    else -> "★ COMBO"
                                }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(KawaiiHotPink.copy(alpha = 0.35f))
                                        .border(1.dp, KawaiiSakuraPink, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text("$comboTier x$clickerCombo", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Text(
                                "Total Earned: ${MikuBeatClickerEngine.formatNumber(MikuBeatClickerEngine.totalEarned.collectAsState().value)} 🥬",
                                color = KawaiiTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else if (selectedMode == 2) {
                    // MODE 2: PRODUCER SKILLS TREE HERO
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x33000000))
                            .border(1.5.dp, KawaiiSoftTeal, RoundedCornerShape(22.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🧲 PRODUCER SKILL TREE", color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                Text("Rhythm Master Perks", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            }
                            Text("${clickerSkills.sumOf { it.level }} Upgrades Active", color = KawaiiGoldenHoney, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Upgrade your timing window, magnetize Golden Leeks, overclock Fever duration, and summon Chibi Miku autopilot!",
                            color = KawaiiTextMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (selectedMode == 3) {
                    // MODE 3: THEME SKINS & TEXTURES HERO
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x33000000))
                            .border(1.5.dp, activeSkinPrimary, RoundedCornerShape(22.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🎨 AESTHETIC SKINS & TEXTURES", color = activeSkinPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(currentSkin.icon, fontSize = 20.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(currentSkin.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(activeSkinPrimary.copy(alpha = 0.3f))
                                    .border(1.dp, activeSkinPrimary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text("ACTIVE SKIN", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("Custom palette, ambient floating particles, and tactile shaders.", color = KawaiiTextMuted, fontSize = 11.sp)
                    }
                } else if (selectedMode == 4) {
                    // MODE 4: QUESTS & CALIBRATION DATABASE HERO
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x33000000))
                            .border(1.5.dp, Color(0xFFFFD700), RoundedCornerShape(22.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🏆 QUESTS & CALIBRATION DB", color = Color(0xFFFFD700), fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                Text("Calibration Stats", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            }
                            Text("${achievements.count { it.isUnlocked }} of ${achievements.size} Done", color = KawaiiMikuMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val totalCal = dbStats["totalTracks"] ?: 0
                            val userCal = dbStats["userCalibrated"] ?: 0
                            val totalTaps = dbStats["totalTaps"] ?: 0
                            val accPct = dbStats["perfectAccuracyPct"] ?: 100
                            KawaiiStatBadge("DB TRACKS", "$totalCal", KawaiiSoftTeal)
                            KawaiiStatBadge("USER CAL", "$userCal", KawaiiSakuraPink)
                            KawaiiStatBadge("LOGGED TAPS", "$totalTaps", KawaiiGoldenHoney)
                            KawaiiStatBadge("ACCURACY", "$accPct%", KawaiiMikuMint)
                        }
                    }
                } else {
                    // MODE 5: SEASONS & RANKS HERO
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0x33000000))
                            .border(1.5.dp, Color(currentSeason.rank.colorHex), RoundedCornerShape(22.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(currentSeason.seasonName, color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(currentSeason.rank.badge, fontSize = 26.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        currentSeason.rank.title,
                                        color = Color(currentSeason.rank.colorHex),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("SEASON SCORE", color = KawaiiTextMuted, fontSize = 10.sp)
                                Text(
                                    MikuBeatClickerEngine.formatNumber(currentSeason.seasonScore.toDouble()),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            KawaiiStatBadge("TOTAL TAPS", "${lifetimeStats.totalTaps}", KawaiiMikuMint)
                            KawaiiStatBadge("MAX STREAK", "x${lifetimeStats.maxCombo}", KawaiiGoldenHoney)
                            KawaiiStatBadge("PERFECTS", "${lifetimeStats.perfectHits}", KawaiiSakuraPink)
                            KawaiiStatBadge("HIGH BPM", "${lifetimeStats.highestBpmLocked.toInt()}", KawaiiLavender)
                        }
                    }
                }

                // 3. CENTER: PROJECT DIVA APPROACH RING & ULTRA KAWAII BEAT NODE
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Golden Leek lucky target
                    if (goldenLeekVisible) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 12.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(KawaiiGoldenHoney, Color(0xFFFFA500))))
                                .border(2.dp, Color.White, CircleShape)
                                .clickable {
                                    MikuBeatClickerEngine.tapGoldenLeek()
                                    com.miku.launcher.haptics.MikuHaptics.like(ctx)
                                }
                                .padding(8.dp)
                        ) {
                            Text("✨🥬✨", fontSize = 20.sp)
                        }
                    }

                    // Interactive Kawaii Project DIVA Beat Node (with Approach Rings, Ripple & Shockwaves)
                    KawaiiProjectDivaBeatNode(
                        approachRadius = beatApproachAnim.value,
                        pulseScale = beatPulseScale.value,
                        isPlaying = isPlaying,
                        feverActive = feverSeconds > 0,
                        perfectShockwaveTrigger = perfectShockwaveTrigger,
                        onTap = {
                            val now = SystemClock.elapsedRealtime()
                            lastTapTimeMs = now

                            // Tap tempo tracking
                            if (tapTimestamps.isNotEmpty() && now - tapTimestamps.last() > 2000L) {
                                tapTimestamps.clear()
                                tapCounter = 0
                            }
                            tapTimestamps.add(now)
                            tapCounter++
                            if (tapTimestamps.size > 8) tapTimestamps.removeAt(0)
                            if (tapTimestamps.size >= 2) {
                                val intervals = (1 until tapTimestamps.size).map { (tapTimestamps[it] - tapTimestamps[it - 1]).toDouble() }
                                val avg = intervals.average()
                                if (avg > 0) calculatedTapBpm = (60_000.0 / avg).toFloat().coerceIn(20f, 999f)
                            }

                            // Calculate Exact Millisecond Timing Offset (Early vs Late)
                            val accuracy: HitAccuracy
                            if (isPlaying && lastBeatEpochMs > 0L) {
                                val cycle = (now - lastBeatEpochMs).mod(beatIntervalMs)
                                val signedOffset = if (cycle > beatIntervalMs / 2) {
                                    (cycle - beatIntervalMs).toInt()
                                } else {
                                    cycle.toInt()
                                }
                                timingOffsetMs = signedOffset

                                val absOffset = abs(signedOffset)
                                val perfectWin = maxOf(45, (beatIntervalMs / 8).toInt())
                                val goodWin = maxOf(100, (beatIntervalMs / 4).toInt())

                                accuracy = when {
                                    absOffset <= perfectWin -> HitAccuracy.PERFECT
                                    absOffset <= goodWin -> HitAccuracy.GOOD
                                    else -> HitAccuracy.MISS
                                }
                            } else {
                                timingOffsetMs = 0
                                accuracy = HitAccuracy.GOOD
                            }

                            // Haptic FIRST — one short sharp pulse the instant the judgment is known,
                            // before any scoring/DB work, so it lands on the finger, not after it.
                            com.miku.launcher.haptics.MikuHaptics.beat(
                                ctx,
                                when (accuracy) { HitAccuracy.PERFECT -> 2; HitAccuracy.GOOD -> 1; HitAccuracy.MISS -> 0 }
                            )

                            val yield = MikuBeatClickerEngine.tap(accuracy, liveBpm)
                            val pts = when (accuracy) {
                                HitAccuracy.PERFECT -> 100L + clickerCombo * 15L
                                HitAccuracy.GOOD -> 50L + clickerCombo * 5L
                                HitAccuracy.MISS -> 0L
                            }
                            MikuBpmSeasonsEngine.recordTap(accuracy, clickerCombo, pts, liveBpm)

                            // Telemetry Logging to SQLite Database
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                bpmDb.logTapTelemetry(
                                    MikuBpmDatabase.TapTelemetryRecord(
                                        artist = trackArtist,
                                        title = trackTitle,
                                        tapEpochMs = System.currentTimeMillis(),
                                        targetBeatMs = beatIntervalMs,
                                        deviationMs = timingOffsetMs,
                                        accuracy = accuracy.name,
                                        instantaneousBpm = liveBpm,
                                        comboAtTap = clickerCombo,
                                        isFever = feverSeconds > 0
                                    )
                                )
                                MikuBeatClickerEngine.checkAchievements()
                                dbStats = bpmDb.getCalibrationStats()
                            }

                            when (accuracy) {
                                HitAccuracy.PERFECT -> {
                                    judgmentTitle = "💖 PERFECT!! (0ms)"
                                    judgmentColor = KawaiiHotPink
                                    perfectShockwaveTrigger++
                                }
                                HitAccuracy.GOOD -> {
                                    val prefix = if (timingOffsetMs < 0) "EARLY" else "LATE"
                                    judgmentTitle = "✨ GOOD ($prefix ${abs(timingOffsetMs)}ms)"
                                    judgmentColor = KawaiiSoftTeal
                                }
                                HitAccuracy.MISS -> {
                                    judgmentTitle = "MISS (${abs(timingOffsetMs)}ms)"
                                    judgmentColor = Color(0xFFFF6B8B)
                                }
                            }
                        }
                    )

                    // Floating Numbers / Floating Texts
                    floatingTexts.forEach { ft ->
                        key(ft.id) {
                            val animY = remember { Animatable(0f) }
                            val animAlpha = remember { Animatable(1f) }
                            LaunchedEffect(ft.id) {
                                launch { animY.animateTo(-40f, tween(600, easing = LinearOutSlowInEasing)) }
                                launch {
                                    delay(150L)
                                    animAlpha.animateTo(0f, tween(450, easing = LinearEasing))
                                }
                            }
                            Text(
                                text = ft.text,
                                color = if (ft.isCrit) KawaiiGoldenHoney else (if (ft.isFever) KawaiiHotPink else KawaiiSoftTeal),
                                fontSize = if (ft.isCrit) 16.sp else 13.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont,
                                modifier = Modifier
                                    .offset(x = ft.x.dp, y = (ft.y + animY.value).dp)
                                    .graphicsLayer { alpha = animAlpha.value }
                            )
                        }
                    }

                    // Floating Judgment Title
                    if (judgmentTitle.isNotEmpty()) {
                        Text(
                            text = judgmentTitle,
                            color = judgmentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-14).dp)
                        )
                    }
                }

                // 4. BOTTOM DOCK (Modes 0..5)
                if (selectedMode == 1) {
                    // MODE 1: BUILDINGS SHOP CARDS
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(clickerBuildings) { b ->
                            val canAfford = leekCount >= b.currentCost
                            Box(
                                Modifier
                                    .width(145.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x33000000))
                                    .border(1.dp, if (canAfford) KawaiiMikuMint else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                    .clickable(enabled = canAfford) {
                                        MikuBeatClickerEngine.buyBuilding(b.id)
                                        com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                                    }
                                    .padding(6.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${b.icon} ${b.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text("x${b.owned}", color = KawaiiSakuraPink, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                        Text("+${MikuBeatClickerEngine.formatNumber(b.baseBps)}/s", color = KawaiiSoftTeal, fontSize = 11.sp)
                                        Text("${MikuBeatClickerEngine.formatNumber(b.currentCost)} 🥬", color = if (canAfford) KawaiiGoldenHoney else KawaiiTextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedMode == 2) {
                    // MODE 2: PRODUCER SKILLS UPGRADE CARDS
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(clickerSkills) { s ->
                            val canAfford = leekCount >= s.currentCost && s.level < s.maxLevel
                            Box(
                                Modifier
                                    .width(155.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x33000000))
                                    .border(1.dp, if (canAfford) KawaiiSoftTeal else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                    .clickable(enabled = canAfford) {
                                        MikuBeatClickerEngine.buySkill(s.id)
                                        com.miku.launcher.haptics.MikuHaptics.like(ctx)
                                    }
                                    .padding(6.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${s.icon} ${s.name}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text("Lv.${s.level}", color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                        Text(if (s.level >= s.maxLevel) "MAX" else "${MikuBeatClickerEngine.formatNumber(s.currentCost)} 🥬", color = if (canAfford) KawaiiGoldenHoney else KawaiiTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(if (canAfford) "UPGRADE" else (if (s.level >= s.maxLevel) "MASTERED" else "LOCKED"), color = if (canAfford) KawaiiSoftTeal else KawaiiTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedMode == 3) {
                    // MODE 3: THEME SKINS SELECTOR
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(MikuBeatClickerEngine.BpmSkin.values()) { skin ->
                            val isCurrent = currentSkin == skin
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x33000000))
                                    .border(1.5.dp, if (isCurrent) Color(skin.primaryColor) else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                    .clickable {
                                        MikuBeatClickerEngine.setSkin(skin)
                                        com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                                    }
                                    .padding(8.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(skin.icon, fontSize = 18.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(skin.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(if (isCurrent) "✨ ACTIVE" else "TAP TO APPLY", color = if (isCurrent) Color(skin.primaryColor) else KawaiiTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                } else if (selectedMode == 4) {
                    // MODE 4: QUESTS & ACHIEVEMENTS LIST
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(achievements) { a ->
                            Box(
                                Modifier
                                    .width(155.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x33000000))
                                    .border(1.dp, if (a.isUnlocked) Color(0xFFFFD700) else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                    .padding(6.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${a.icon} ${a.title}", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(if (a.isUnlocked) "✅" else "🔒", fontSize = 12.sp)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                        Text(a.desc, color = KawaiiTextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(4.dp))
                                        Text("+${MikuBeatClickerEngine.formatNumber(a.rewardLeeks)} 🥬", color = Color(0xFFFFD700), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // MODE 0 / 5: CALIBRATION ACTION FOOTER & DB CONTROLS
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            KawaiiStatBadge("LEEK HARVEST", MikuBeatClickerEngine.formatNumber(leekCount), KawaiiGoldenHoney)
                            KawaiiStatBadge("COMBO STREAK", "x$clickerCombo", if (clickerCombo >= 10) KawaiiGoldenHoney else KawaiiSakuraPink)
                            KawaiiStatBadge("SEASON RANK", currentSeason.rank.badge, Color(currentSeason.rank.colorHex))
                            KawaiiStatBadge("TAP TEMPO", if (calculatedTapBpm != null) "${calculatedTapBpm!!.toInt()}" else "--", KawaiiMikuMint)
                        }

                        if (calculatedTapBpm != null) {
                            val tapVal = calculatedTapBpm!!
                            val halfVal = (tapVal / 2f).coerceIn(20f, 999f)
                            val doubleVal = (tapVal * 2f).coerceIn(20f, 999f)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x55FF3385))
                                        .border(1.dp, KawaiiHotPink, RoundedCornerShape(10.dp))
                                        .clickable {
                                            com.miku.launcher.haptics.MikuHaptics.like(ctx)
                                            try {
                                                android.provider.Settings.Global.putFloat(ctx.contentResolver, "miku_live_bpm", tapVal)
                                                android.provider.Settings.Global.putInt(ctx.contentResolver, "miku_beat_interval_ms", (60000f / tapVal).toInt())
                                                val intent = android.content.Intent("com.miku.action.BPM_UPDATE").apply {
                                                    putExtra("bpm", tapVal)
                                                    putExtra("beat_interval_ms", (60000f / tapVal).toLong())
                                                    putExtra("is_playing", isPlaying)
                                                }
                                                ctx.sendBroadcast(intent)
                                            } catch (_: Throwable) {}

                                            // Save to SQLite Database
                                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                bpmDb.saveTrackBpm(
                                                    MikuBpmDatabase.TrackBpmRecord(
                                                        artist = trackArtist,
                                                        title = trackTitle,
                                                        canonicalBpm = tapVal,
                                                        rawDetectedBpm = liveBpm,
                                                        userTappedBpm = tapVal,
                                                        tempoMultiplier = if (tapVal > liveBpm * 1.5f) 2.0f else (if (tapVal < liveBpm * 0.75f) 0.5f else 1.0f),
                                                        confidence = 1.0f,
                                                        source = "USER_CALIBRATED",
                                                        tapCount = tapCounter
                                                    )
                                                )
                                                dbStats = bpmDb.getCalibrationStats()
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("🔒 LOCK BPM: ${tapVal.toInt()}", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                                Spacer(Modifier.width(5.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x3339C5BB))
                                        .border(1.dp, KawaiiMikuMint, RoundedCornerShape(10.dp))
                                        .clickable {
                                            calculatedTapBpm = halfVal
                                            com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text("➗ /2 (${halfVal.toInt()})", color = KawaiiMikuMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x33DFB8FF))
                                        .border(1.dp, KawaiiLavender, RoundedCornerShape(10.dp))
                                        .clickable {
                                            calculatedTapBpm = doubleVal
                                            com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text("✖️ x2 (${doubleVal.toInt()})", color = KawaiiLavender, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x3300E5FF))
                                        .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(10.dp))
                                        .clickable {
                                            com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                                            coroutineScope.launch {
                                                val online = bpmDb.resolveCanonicalBpm(trackArtist, trackTitle)
                                                if (online != null && online.canonicalBpm > 0f) {
                                                    calculatedTapBpm = online.canonicalBpm
                                                    android.widget.Toast.makeText(ctx, "🌐 DB Resolved: ${online.canonicalBpm.toInt()} BPM (${online.source})", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(ctx, "🌐 Queried DB (DSP fallback active)", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text("🌐 DB", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                            }
                        }

                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (isPlaying) "🌸 Tap node when the glowing ring closes on center!" else "⏸️ Playback Paused · Tap node to measure tempo",
                            color = if (isPlaying) KawaiiMikuMint else KawaiiTextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rainbow Sugar Fever Energy Progress Bar with Shimmering Pulse.
 */
@Composable
private fun KawaiiSugarFeverGauge(
    feverEnergy: Float,
    feverSeconds: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "FeverShimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer"
    )

    Box(
        modifier = modifier
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x40000000))
            .border(1.dp, if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
    ) {
        val frac = if (feverSeconds > 0) 1f else (feverEnergy / 100f).coerceIn(0f, 1f)
        val animatedFrac by animateFloatAsState(targetValue = frac, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "feverFrac")

        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFrac)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (feverSeconds > 0) {
                        Brush.horizontalGradient(
                            listOf(KawaiiGoldenHoney, KawaiiHotPink, KawaiiMikuMint, KawaiiLavender)
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(KawaiiSakuraPink, KawaiiHotPink, KawaiiMikuMint)
                        )
                    }
                )
        )
    }
}

/**
 * Visual Rhythm Timing Calibration Gauge.
 */
@Composable
private fun KawaiiTimingDeviationBar(
    offsetMs: Int,
    maxWindowMs: Long,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
    ) {
        val w = size.width
        val h = size.height
        val midX = w / 2f

        drawLine(
            color = Color(0x44FFFFFF),
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        val perfectZoneWidth = (w * 0.2f)
        drawRect(
            color = KawaiiSakuraPink.copy(alpha = 0.35f),
            topLeft = Offset(midX - perfectZoneWidth / 2f, 0f),
            size = Size(perfectZoneWidth, h)
        )

        drawLine(
            color = Color.White,
            start = Offset(midX, 0f),
            end = Offset(midX, h),
            strokeWidth = 2f
        )

        val clampedOffset = offsetMs.toFloat().coerceIn(-maxWindowMs.toFloat(), maxWindowMs.toFloat())
        val fraction = (clampedOffset / maxWindowMs.toFloat()).coerceIn(-1f, 1f)
        val pointerX = midX + (fraction * (w / 2f - 6f))

        val pointerColor = when {
            abs(offsetMs) <= 35 -> KawaiiHotPink
            offsetMs < 0 -> KawaiiPeach
            else -> KawaiiSoftTeal
        }

        drawCircle(color = pointerColor, radius = 6f, center = Offset(pointerX, h / 2f))
        drawCircle(color = Color.White, radius = 3f, center = Offset(pointerX, h / 2f))
    }
}

/**
 * Project DIVA Approach Ring & Kawaii Beat Node with Squash & Stretch + Perfect Shockwaves.
 */
@Composable
private fun KawaiiProjectDivaBeatNode(
    approachRadius: Float,
    pulseScale: Float,
    isPlaying: Boolean,
    feverActive: Boolean,
    perfectShockwaveTrigger: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val touchScale = remember { Animatable(1f) }
    val rippleAnim = remember { Animatable(0f) }
    val shockwaveAnim = remember { Animatable(0f) }

    LaunchedEffect(perfectShockwaveTrigger) {
        if (perfectShockwaveTrigger > 0) {
            shockwaveAnim.snapTo(0f)
            shockwaveAnim.animateTo(1f, tween(400, easing = LinearOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .size(140.dp)
            .scale(touchScale.value * pulseScale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        coroutineScope.launch {
                            touchScale.snapTo(0.85f)
                            touchScale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                        coroutineScope.launch {
                            rippleAnim.snapTo(0f)
                            rippleAnim.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
                        }
                        onTap()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val baseR = (size.minDimension / 2f) * 0.65f

            // Approach Ring
            if (isPlaying && approachRadius > 0.01f) {
                val currentApproachR = baseR + (approachRadius * 26f)
                drawCircle(
                    color = if (feverActive) KawaiiGoldenHoney.copy(alpha = 0.8f) else KawaiiSakuraPink.copy(alpha = 0.85f),
                    radius = currentApproachR,
                    center = c,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = currentApproachR,
                    center = c,
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            // Tap Ripple
            if (rippleAnim.value > 0.01f && rippleAnim.value < 0.99f) {
                val r = baseR + (rippleAnim.value * 35f)
                val alpha = (1f - rippleAnim.value) * 0.9f
                drawCircle(
                    color = KawaiiHotPink.copy(alpha = alpha),
                    radius = r,
                    center = c,
                    style = Stroke(width = 4.dp.toPx() * (1f - rippleAnim.value))
                )
            }

            // Perfect Shockwave
            if (shockwaveAnim.value > 0.01f && shockwaveAnim.value < 0.99f) {
                val swR = baseR + (shockwaveAnim.value * 70f)
                val swAlpha = (1f - shockwaveAnim.value) * 0.8f
                drawCircle(
                    color = KawaiiGoldenHoney.copy(alpha = swAlpha),
                    radius = swR,
                    center = c,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            if (feverActive) KawaiiGoldenHoney else KawaiiSakuraPink,
                            Color(0xFF5A1A3A)
                        ),
                        center = Offset(34f, 34f),
                        radius = 105f
                    )
                )
                .border(
                    BorderStroke(
                        2.5.dp,
                        Brush.linearGradient(listOf(Color.White, KawaiiHotPink))
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (feverActive) "✨🥬✨" else "🌸💖🌸", fontSize = 20.sp)
                Text(
                    text = "TAP BEAT",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * Dynamic Tap Juice Particle Explosions.
 */
@Composable
private fun KawaiiJuicyParticleOverlay(
    particles: List<MikuBeatClickerEngine.JuiceParticle>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        particles.forEach { p ->
            key(p.id) {
                val animProgress = remember { Animatable(0f) }
                LaunchedEffect(p.id) {
                    animProgress.animateTo(1f, tween(550, easing = LinearOutSlowInEasing))
                }
                val currentX = p.x + p.vx * animProgress.value
                val currentY = p.y + p.vy * animProgress.value
                val currentAlpha = (1f - animProgress.value).coerceIn(0f, 1f)

                Text(
                    text = p.emoji,
                    fontSize = p.sizeDp.sp,
                    modifier = Modifier
                        .offset(x = currentX.dp, y = currentY.dp)
                        .graphicsLayer { alpha = currentAlpha }
                )
            }
        }
    }
}

/**
 * Dreamy Floating Kawaii Hearts and Sakura Canvas.
 */
@Composable
private fun KawaiiDreamyHeartCanvas(
    modifier: Modifier = Modifier,
    isFever: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "DreamyCanvas")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val count = 16

        for (i in 0 until count) {
            val seed = i * 142.3f
            val baseX = (seed % w)
            val baseY = ((seed * 2.1f) % h)
            val driftY = (baseY - (phase * h * 0.6f) + (i * 18f)).mod(h)
            val wobbleX = baseX + sin((phase * 6.28f) + i) * 14f
            val scale = (0.5f + (i % 4) * 0.15f)
            val alpha = (0.2f + 0.5f * sin(phase * 3.14f + i)).coerceIn(0.1f, 0.75f)

            val heartColor = when {
                isFever -> if (i % 2 == 0) KawaiiGoldenHoney else KawaiiHotPink
                i % 3 == 0 -> KawaiiSakuraPink
                i % 3 == 1 -> KawaiiMikuMint
                else -> KawaiiLavender
            }

            drawKawaiiHeart(center = Offset(wobbleX, driftY), size = 14f * scale, color = heartColor.copy(alpha = alpha))
        }
    }
}

private fun DrawScope.drawKawaiiHeart(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + size * 0.5f)
        cubicTo(
            center.x - size, center.y - size * 0.3f,
            center.x - size * 0.5f, center.y - size,
            center.x, center.y - size * 0.4f
        )
        cubicTo(
            center.x + size * 0.5f, center.y - size,
            center.x + size, center.y - size * 0.3f,
            center.x, center.y + size * 0.5f
        )
        close()
    }
    drawPath(path, color)
}

@Composable
private fun KawaiiModeTabPill(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) activeColor.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else KawaiiTextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont
        )
    }
}

@Composable
private fun KawaiiStatBadge(label: String, value: String, accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33000000))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = KawaiiTextMuted, fontSize = 9.5.sp, fontFamily = AudiowideFont)
            Text(value, color = accent, fontSize = 14.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        }
    }
}
