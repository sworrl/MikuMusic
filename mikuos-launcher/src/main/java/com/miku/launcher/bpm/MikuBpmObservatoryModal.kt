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
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.graphics.Shape
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
        // Loads the saved timing offset and probes what the platform will tell us about output
        // latency. Idempotent.
        MikuRhythmCalibration.init(ctx)
    }

    // Modal Mode: 0 = Kawaii Beat Match, 1 = Sweet Leek Clicker, 2 = Producer Skills, 3 = Skins & Textures, 4 = Quests & DB, 5 = Seasons
    val selectedModeState = remember { mutableIntStateOf(0) }
    val selectedMode by selectedModeState

    // hasLiveTempo gates every READOUT; the 120 fallback below only feeds the rhythm-game engine's
    // default tempo and is never printed as a measurement.
    val hasLiveTempo = bpmState.bpm.isFinite() && bpmState.bpm in 20f..999f
    val liveBpm = if (hasLiveTempo) bpmState.bpm else 120f
    val isPlaying = bpmState.isPlaying
    val beatIntervalMs = bpmState.beatIntervalMs.coerceIn(60L, 3000L)

    val cr = ctx.contentResolver
    // NULLABLE — no invented now-playing track. These used to default to "World is Mine" /
    // "supercell feat. Hatsune Miku", which was not just printed on screen while something else
    // (Spotify, YouTube) played: it was fed to resolveCanonicalBpm(), hit the preseeded dictionary
    // entry for that song, and then WROTE Settings.Global miku_live_bpm = 165 and broadcast
    // com.miku.action.BPM_UPDATE — poisoning the live tempo for every BPM surface in the OS.
    val trackTitle: String? = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_title")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
    }
    val trackArtist: String? = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_artist")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
    }

    val calculatedTapBpmState = remember { mutableStateOf<Float?>(null) }
    var calculatedTapBpm by calculatedTapBpmState
    val dbStatsState = remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var dbStats by dbStatsState

    // Auto-resolve canonical BPM from SQLite / Preseeded dictionary on track change
    LaunchedEffect(trackArtist, trackTitle) {
        dbStats = bpmDb.getCalibrationStats()
        // No published track = nothing to look up. Never resolve (or broadcast) a tempo for a
        // track identity we invented.
        if (trackTitle == null || trackArtist == null) return@LaunchedEffect
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
    // State holders (not `by` delegates) so the extracted sub-composables below can share the
    // exact same MutableState objects and keep read-after-write semantics inside tap callbacks.
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    val tapCounterState = remember { mutableIntStateOf(0) }
    val timingOffsetMsState = remember { mutableIntStateOf(0) }
    val judgmentTitleState = remember { mutableStateOf("") }
    val judgmentColorState = remember { mutableStateOf(KawaiiSakuraPink) }
    val lastTapTimeMsState = remember { mutableLongStateOf(0L) }
    val perfectShockwaveTriggerState = remember { mutableIntStateOf(0) }

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
                isFever = feverSeconds > 0,
                ambient = currentSkin.ambient,
                skinPrimary = activeSkinPrimary,
                skinAccent = activeSkinAccent
            )

            // Dynamic Hit Particles System
            KawaiiJuicyParticleOverlay(
                particles = activeParticles,
                burst = currentSkin.burst,
                modifier = Modifier.fillMaxSize()
            )

            // Layout Column (Single Page, 0 Scroll)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. TOP HEADER & CUTE MODE PILLS + FEVER GAUGE
                BpmObservatoryHeader(
                    selectedModeState = selectedModeState,
                    currentSeason = currentSeason,
                    activeSkinPrimary = activeSkinPrimary,
                    feverEnergy = feverEnergy,
                    feverSeconds = feverSeconds,
                    onClose = onClose
                )

                // 2. HERO SECTION: TIMING & TRACK CARDS
                if (selectedMode == 0) {
                    BpmHeroRhythmMatchCard(
                        isPlaying = isPlaying,
                        hasLiveTempo = hasLiveTempo,
                        liveBpm = liveBpm,
                        beatIntervalMs = beatIntervalMs,
                        trackTitle = trackTitle,
                        trackArtist = trackArtist,
                        activeSkinPrimary = activeSkinPrimary,
                        timingOffsetMs = timingOffsetMsState.intValue,
                        judgmentColor = judgmentColorState.value,
                        lastTapTimeMs = lastTapTimeMsState.longValue
                    )
                } else if (selectedMode == 1) {
                    BpmHeroHarvestCard(
                        liveBpm = liveBpm,
                        leekCount = leekCount,
                        clickerCombo = clickerCombo,
                        feverSeconds = feverSeconds
                    )
                } else if (selectedMode == 2) {
                    BpmHeroSkillsCard(clickerSkills = clickerSkills)
                } else if (selectedMode == 3) {
                    BpmHeroSkinsCard(currentSkin = currentSkin, activeSkinPrimary = activeSkinPrimary)
                } else if (selectedMode == 4) {
                    BpmHeroQuestsCard(achievements = achievements, dbStats = dbStats)
                } else {
                    BpmHeroSeasonsCard(currentSeason = currentSeason, lifetimeStats = lifetimeStats)
                }

                // 3. CENTER: PROJECT DIVA APPROACH RING & ULTRA KAWAII BEAT NODE
                BpmBeatNodeStage(
                    approachRadius = beatApproachAnim.value,
                    pulseScale = beatPulseScale.value,
                    isPlaying = isPlaying,
                    hasLiveTempo = hasLiveTempo,
                    liveBpm = liveBpm,
                    beatIntervalMs = beatIntervalMs,
                    lastBeatEpochMs = lastBeatEpochMs,
                    goldenLeekVisible = goldenLeekVisible,
                    skin = currentSkin,
                    feverSeconds = feverSeconds,
                    clickerCombo = clickerCombo,
                    floatingTexts = floatingTexts,
                    trackArtist = trackArtist,
                    trackTitle = trackTitle,
                    bpmDb = bpmDb,
                    coroutineScope = coroutineScope,
                    tapTimestamps = tapTimestamps,
                    tapCounterState = tapCounterState,
                    calculatedTapBpmState = calculatedTapBpmState,
                    timingOffsetMsState = timingOffsetMsState,
                    judgmentTitleState = judgmentTitleState,
                    judgmentColorState = judgmentColorState,
                    lastTapTimeMsState = lastTapTimeMsState,
                    perfectShockwaveTriggerState = perfectShockwaveTriggerState,
                    dbStatsState = dbStatsState
                )

                // 4. BOTTOM DOCK (Modes 0..5)
                if (selectedMode == 1 || selectedMode == 2 || selectedMode == 3 || selectedMode == 4) {
                    BpmGameDockCards(
                        selectedMode = selectedMode,
                        leekCount = leekCount,
                        clickerBuildings = clickerBuildings,
                        clickerSkills = clickerSkills,
                        currentSkin = currentSkin,
                        achievements = achievements
                    )
                } else {
                    BpmCalibrationFooter(
                        isPlaying = isPlaying,
                        hasLiveTempo = hasLiveTempo,
                        liveBpm = liveBpm,
                        leekCount = leekCount,
                        clickerCombo = clickerCombo,
                        currentSeason = currentSeason,
                        trackArtist = trackArtist,
                        trackTitle = trackTitle,
                        bpmDb = bpmDb,
                        coroutineScope = coroutineScope,
                        tapCounterState = tapCounterState,
                        calculatedTapBpmState = calculatedTapBpmState,
                        dbStatsState = dbStatsState
                    )
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
    windows: MikuRhythmTiming.Windows,
    modifier: Modifier = Modifier
) {
    // The bar now DRAWS the real hit windows instead of a decorative 20% band: the player can
    // see how much room each tier actually has, and watch those bands widen or tighten when the
    // adaptive difficulty tier changes or a Timing Window Expander tier is bought. A scale you
    // can see is a scale you can aim at.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
    ) {
        val w = size.width
        val h = size.height
        val midX = w / 2f
        // Full scale = the OK window plus a little headroom, so a MISS still lands on the bar.
        val scaleMs = (windows.okMs * 1.25f).coerceAtLeast(40f)
        fun halfWidthPx(ms: Int): Float = (ms / scaleMs) * (w / 2f)

        drawLine(
            color = Color(0x44FFFFFF),
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Widest tier first so the tighter ones paint on top.
        fun band(ms: Int, color: Color) {
            val half = halfWidthPx(ms)
            drawRect(color = color, topLeft = Offset(midX - half, 0f), size = Size(half * 2f, h))
        }
        band(windows.okMs, KawaiiLavender.copy(alpha = 0.12f))
        band(windows.goodMs, KawaiiSoftTeal.copy(alpha = 0.18f))
        band(windows.greatMs, KawaiiSakuraPink.copy(alpha = 0.25f))
        band(windows.perfectMs, KawaiiHotPink.copy(alpha = 0.45f))

        drawLine(
            color = Color.White,
            start = Offset(midX, 0f),
            end = Offset(midX, h),
            strokeWidth = 2f
        )

        val fraction = (offsetMs / scaleMs).coerceIn(-1f, 1f)
        val pointerX = midX + (fraction * (w / 2f - 6f))

        val pointerColor = when {
            abs(offsetMs) <= windows.perfectMs -> KawaiiHotPink
            abs(offsetMs) <= windows.greatMs -> KawaiiSakuraPink
            offsetMs < 0 -> KawaiiPeach
            else -> KawaiiSoftTeal
        }

        drawCircle(color = pointerColor, radius = 6f, center = Offset(pointerX, h / 2f))
        drawCircle(color = Color.White, radius = 3f, center = Offset(pointerX, h / 2f))
    }
}

// =========================================================================
// 🎨 SKIN GEOMETRY — the beat node's silhouette, per skin
//
// A skin that only swaps a hue is not a skin. These build the actual outline of the node and
// of its approach ring, so Cyber Mirai is a hexagon closing on a hexagon while Snow Crystal is
// a six-point snowflake — recognisable from across the room, not on a colour chip.
// =========================================================================

private fun polygonPath(cx: Float, cy: Float, r: Float, sides: Int, rotationDeg: Float): Path {
    val p = Path()
    for (i in 0 until sides) {
        val a = Math.toRadians((rotationDeg + i * 360.0 / sides)).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun starPath(cx: Float, cy: Float, rOuter: Float, rInner: Float, points: Int, rotationDeg: Float): Path {
    val p = Path()
    val steps = points * 2
    for (i in 0 until steps) {
        val r = if (i % 2 == 0) rOuter else rInner
        val a = Math.toRadians((rotationDeg + i * 360.0 / steps)).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun skinOutline(
    shape: MikuBeatClickerEngine.NodeShape,
    cx: Float,
    cy: Float,
    r: Float
): Path = when (shape) {
    MikuBeatClickerEngine.NodeShape.ORB -> Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
    }
    MikuBeatClickerEngine.NodeShape.DIAMOND -> polygonPath(cx, cy, r, 4, -90f)
    MikuBeatClickerEngine.NodeShape.HEX -> polygonPath(cx, cy, r, 6, -90f)
    MikuBeatClickerEngine.NodeShape.STAR -> starPath(cx, cy, r, r * 0.46f, 5, -90f)
    MikuBeatClickerEngine.NodeShape.SNOWFLAKE -> starPath(cx, cy, r, r * 0.55f, 6, -90f)
}

/** The same silhouette as a clip/border [Shape] for the filled core of the node. */
private fun skinNodeShape(shape: MikuBeatClickerEngine.NodeShape): Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(size.width, size.height) / 2f
    addPath(skinOutline(shape, cx, cy, r))
}

/**
 * The PERFECT hit effect, one per skin. Same trigger, five completely different reads:
 * an expanding ring, shards thrown outward, concentric splash rings, a soft velvet swell,
 * or frost cracking away from the node.
 */
private fun DrawScope.drawSkinHitEffect(
    skin: MikuBeatClickerEngine.BpmSkin,
    center: Offset,
    baseRadius: Float,
    progress: Float,
    strokePx: Float
) {
    val alpha = (1f - progress).coerceIn(0f, 1f)
    val color = Color(skin.glowColor).copy(alpha = alpha * 0.85f)
    when (skin.hitEffect) {
        MikuBeatClickerEngine.HitEffect.RING ->
            drawCircle(color, baseRadius + progress * 70f, center, style = Stroke(width = strokePx))
        MikuBeatClickerEngine.HitEffect.SHARD -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0).toFloat()
                val inner = baseRadius + progress * 26f
                val outer = inner + 22f * (1f - progress)
                drawLine(
                    color,
                    Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                    Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                    strokeWidth = strokePx, cap = StrokeCap.Round
                )
            }
        }
        MikuBeatClickerEngine.HitEffect.SPLASH -> {
            for (i in 0 until 3) {
                val p = (progress + i * 0.18f).coerceAtMost(1f)
                drawCircle(
                    Color(skin.accentColor).copy(alpha = (1f - p) * 0.55f),
                    baseRadius + p * 52f, center, style = Stroke(width = strokePx * 0.8f)
                )
            }
        }
        MikuBeatClickerEngine.HitEffect.VELVET_PULSE ->
            drawPath(
                skinOutline(skin.nodeShape, center.x, center.y, baseRadius * (1f + progress * 0.55f)),
                Color(skin.primaryColor).copy(alpha = alpha * 0.30f)
            )
        MikuBeatClickerEngine.HitEffect.FROST_CRACK -> {
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0 + 15.0).toFloat()
                val r1 = baseRadius + progress * 18f
                val r2 = r1 + 34f * progress
                val midA = a + 0.16f
                drawLine(
                    color,
                    Offset(center.x + cos(a) * r1, center.y + sin(a) * r1),
                    Offset(center.x + cos(midA) * ((r1 + r2) / 2f), center.y + sin(midA) * ((r1 + r2) / 2f)),
                    strokeWidth = strokePx * 0.7f, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(center.x + cos(midA) * ((r1 + r2) / 2f), center.y + sin(midA) * ((r1 + r2) / 2f)),
                    Offset(center.x + cos(a - 0.1f) * r2, center.y + sin(a - 0.1f) * r2),
                    strokeWidth = strokePx * 0.7f, cap = StrokeCap.Round
                )
            }
        }
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
    skin: MikuBeatClickerEngine.BpmSkin,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The node's silhouette, its approach ring and its hit effect all come from the skin, so
    // switching skin changes the thing the player is literally staring at while they tap.
    val nodeShape = remember(skin) { skinNodeShape(skin.nodeShape) }
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

            // Approach Ring — follows the skin's silhouette (a hex closes on a hex).
            if (isPlaying && approachRadius > 0.01f) {
                val currentApproachR = baseR + (approachRadius * 26f)
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, currentApproachR),
                    color = if (feverActive) KawaiiGoldenHoney.copy(alpha = 0.8f) else Color(skin.primaryColor).copy(alpha = 0.85f),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, currentApproachR),
                    color = Color.White.copy(alpha = 0.6f),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            // Tap Ripple
            if (rippleAnim.value > 0.01f && rippleAnim.value < 0.99f) {
                val r = baseR + (rippleAnim.value * 35f)
                val alpha = (1f - rippleAnim.value) * 0.9f
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, r),
                    color = Color(skin.accentColor).copy(alpha = alpha),
                    style = Stroke(width = 4.dp.toPx() * (1f - rippleAnim.value))
                )
            }

            // Perfect hit effect — one per skin (ring / shards / splash / velvet / frost).
            if (shockwaveAnim.value > 0.01f && shockwaveAnim.value < 0.99f) {
                drawSkinHitEffect(skin, c, baseR, shockwaveAnim.value, 2.5.dp.toPx())
            }
        }

        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(nodeShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            if (feverActive) KawaiiGoldenHoney else Color(skin.primaryColor),
                            Color(skin.accentColor).copy(alpha = 0.55f)
                        ),
                        center = Offset(34f, 34f),
                        radius = 105f
                    )
                )
                .border(
                    BorderStroke(
                        2.5.dp,
                        Brush.linearGradient(listOf(Color.White, Color(skin.accentColor)))
                    ),
                    nodeShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The face of the node is the skin's own glyph alphabet.
                Text(
                    if (feverActive) "✨🥬✨" else skin.burstGlyphs.take(3).joinToString(""),
                    fontSize = 18.sp
                )
                Text(
                    text = if (skin.judgmentArcade) "TAP BEAT" else "tap beat",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = if (skin.judgmentArcade) AudiowideFont else null,
                    letterSpacing = (0.5f + skin.judgmentSpacing).sp
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
    burst: MikuBeatClickerEngine.BurstStyle,
    modifier: Modifier = Modifier
) {
    // Each skin's burst MOVES differently, not just in a different colour: petals fall and sway,
    // sparks shoot straight out and die fast, bubbles rise and swell, ribbons swirl around the
    // node, crystals hang and twinkle. This is the difference you notice without being told.
    val durationMs = when (burst) {
        MikuBeatClickerEngine.BurstStyle.SPARK_SHOT -> 380
        MikuBeatClickerEngine.BurstStyle.BUBBLE_RISE -> 900
        MikuBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT -> 1000
        else -> 620
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        particles.forEach { p ->
            key(p.id) {
                val animProgress = remember { Animatable(0f) }
                LaunchedEffect(p.id) {
                    animProgress.animateTo(
                        1f,
                        tween(
                            durationMs,
                            easing = if (burst == MikuBeatClickerEngine.BurstStyle.SPARK_SHOT) LinearEasing
                            else LinearOutSlowInEasing
                        )
                    )
                }
                val t = animProgress.value
                var x = p.x + p.vx * t
                var y = p.y + p.vy * t
                var scale = 1f
                when (burst) {
                    // Gravity plus a sideways sway — a petal never falls straight.
                    MikuBeatClickerEngine.BurstStyle.PETAL_FALL -> {
                        y += 70f * t * t
                        x += sin(t * 9f + p.vx) * 9f
                        scale = 1f - 0.2f * t
                    }
                    // Straight, fast, and gone.
                    MikuBeatClickerEngine.BurstStyle.SPARK_SHOT -> {
                        x = p.x + p.vx * t * 1.7f
                        y = p.y + p.vy * t * 1.7f
                        scale = 1f - 0.55f * t
                    }
                    // Slow buoyant rise, swelling as it goes.
                    MikuBeatClickerEngine.BurstStyle.BUBBLE_RISE -> {
                        y = p.y + p.vy * t * 0.35f - 60f * t
                        x += sin(t * 5f + p.vy) * 6f
                        scale = 1f + 0.45f * t
                    }
                    // Orbit the node while drifting outward.
                    MikuBeatClickerEngine.BurstStyle.RIBBON_SWIRL -> {
                        val ang = t * 3.4f
                        val rad = 18f + 46f * t
                        x = p.x + cos(ang + p.vx * 0.05f) * rad
                        y = p.y + sin(ang + p.vx * 0.05f) * rad
                        scale = 1f - 0.15f * t
                    }
                    // Hangs in the air, drifting down, twinkling out.
                    MikuBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT -> {
                        x = p.x + p.vx * t * 0.5f
                        y = p.y + p.vy * t * 0.5f + 26f * t
                        scale = 1f - 0.1f * t
                    }
                }
                val fade = (1f - t).coerceIn(0f, 1f)
                val twinkle =
                    if (burst == MikuBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT)
                        (0.55f + 0.45f * sin(t * 22f)).coerceIn(0f, 1f)
                    else 1f

                Text(
                    text = p.emoji,
                    color = Color(p.colorHex),
                    fontSize = p.sizeDp.sp,
                    modifier = Modifier
                        .offset(x = x.dp, y = y.dp)
                        .graphicsLayer {
                            alpha = fade * twinkle
                            scaleX = scale
                            scaleY = scale
                        }
                )
            }
        }
    }
}

/**
 * Ambient backdrop — one treatment per skin, drawn behind the whole sheet.
 *
 * Every skin used to share the same drifting hearts, which is most of why they read as "the same
 * screen, different tint". Now Sakura rains petals, Cyber Mirai runs a vertical hex/data rain,
 * Honey Sweet floats fat bubbles upward, Gothic Lolita hangs a slow damask veil of diamonds, and
 * Snow Crystal drifts snow sideways.
 */
@Composable
private fun KawaiiDreamyHeartCanvas(
    modifier: Modifier = Modifier,
    isFever: Boolean,
    ambient: MikuBeatClickerEngine.AmbientStyle,
    skinPrimary: Color,
    skinAccent: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "DreamyCanvas")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                when (ambient) {
                    MikuBeatClickerEngine.AmbientStyle.HEX_RAIN -> 2200
                    MikuBeatClickerEngine.AmbientStyle.DAMASK_VEIL -> 7000
                    else -> 4500
                },
                easing = LinearEasing
            ),
            RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val count = if (ambient == MikuBeatClickerEngine.AmbientStyle.HEX_RAIN) 22 else 16

        for (i in 0 until count) {
            val seed = i * 142.3f
            val baseX = (seed % w)
            val baseY = ((seed * 2.1f) % h)
            val scale = (0.5f + (i % 4) * 0.15f)
            val tint = when {
                isFever -> if (i % 2 == 0) KawaiiGoldenHoney else skinAccent
                i % 3 == 0 -> skinPrimary
                i % 3 == 1 -> skinAccent
                else -> KawaiiLavender
            }

            when (ambient) {
                // Petals tumble downward with a sideways sway.
                MikuBeatClickerEngine.AmbientStyle.PETALS -> {
                    val y = (baseY + (phase * h * 0.6f) + (i * 18f)).mod(h)
                    val x = baseX + sin((phase * 6.28f) + i) * 16f
                    val alpha = (0.2f + 0.5f * sin(phase * 3.14f + i)).coerceIn(0.1f, 0.7f)
                    drawKawaiiHeart(Offset(x, y), 14f * scale, tint.copy(alpha = alpha))
                }
                // Vertical data rain: short bright hex dashes falling fast.
                MikuBeatClickerEngine.AmbientStyle.HEX_RAIN -> {
                    val y = (baseY - (phase * h * 1.6f) + (i * 31f)).mod(h)
                    val len = 26f * scale
                    drawLine(
                        tint.copy(alpha = 0.28f),
                        Offset(baseX, y), Offset(baseX, y + len),
                        strokeWidth = 2f, cap = StrokeCap.Round
                    )
                    drawPath(
                        polygonPath(baseX, y + len, 5f * scale, 6, -90f),
                        tint.copy(alpha = 0.5f), style = Stroke(width = 1.4f)
                    )
                }
                // Fat syrup bubbles rising and wobbling.
                MikuBeatClickerEngine.AmbientStyle.HONEY_BUBBLES -> {
                    val y = (baseY - (phase * h * 0.5f) + (i * 23f)).mod(h)
                    val x = baseX + sin((phase * 4.2f) + i * 1.7f) * 12f
                    val r = (7f + (i % 5) * 4f) * scale
                    drawCircle(tint.copy(alpha = 0.18f), r, Offset(x, y))
                    drawCircle(tint.copy(alpha = 0.45f), r, Offset(x, y), style = Stroke(width = 1.4f))
                    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.28f, Offset(x - r * 0.3f, y - r * 0.3f))
                }
                // A slow, heavy veil: stacked damask diamonds barely moving.
                MikuBeatClickerEngine.AmbientStyle.DAMASK_VEIL -> {
                    val y = (baseY + (phase * h * 0.12f) + (i * 29f)).mod(h)
                    val r = 16f * scale
                    drawPath(polygonPath(baseX, y, r, 4, -90f), tint.copy(alpha = 0.16f))
                    drawPath(
                        polygonPath(baseX, y, r * 1.6f, 4, -90f),
                        tint.copy(alpha = 0.22f), style = Stroke(width = 1.2f)
                    )
                }
                // Snow crossing the screen sideways as it falls.
                MikuBeatClickerEngine.AmbientStyle.SNOW_DRIFT -> {
                    val y = (baseY + (phase * h * 0.45f) + (i * 21f)).mod(h)
                    val x = (baseX + phase * w * 0.35f).mod(w)
                    val r = 9f * scale
                    drawPath(
                        starPath(x, y, r, r * 0.42f, 6, -90f),
                        tint.copy(alpha = 0.45f), style = Stroke(width = 1.3f)
                    )
                }
            }
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


/**
 * Mode 0 hero: live BPM orb, now-playing card and millisecond timing-deviation gauge.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroRhythmMatchCard(
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    beatIntervalMs: Long,
    trackTitle: String?,
    trackArtist: String?,
    activeSkinPrimary: Color,
    timingOffsetMs: Int,
    judgmentColor: Color,
    lastTapTimeMs: Long
) {
    // MODE 0: KAWAII BEAT MATCH HERO (LIVE BPM + PRECISION OFFSET GAUGE + SSS+ GRADE)
    // The windows drawn here are the exact ones the tap handler judges against — same tier,
    // same skill bonus, same tempo clamp — so the gauge can never flatter the scoring.
    val rhythmTier by MikuBeatClickerEngine.rhythmTier.collectAsState()
    val tempoLock by MikuTempoLock.tempo.collectAsState()
    val windows = MikuRhythmTiming.windowsFor(
        beatPeriodMs = beatIntervalMs,
        leniency = rhythmTier.leniency,
        bonusMs = MikuBeatClickerEngine.timingWindowBonusMs
    )
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
                        text = if (isPlaying && hasLiveTempo) String.format(Locale.US, "%.0f", liveBpm) else "--",
                        color = Color.White,
                        fontSize = 35.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
                Text(
                    text = when {
                        !isPlaying -> "PAUSED"
                        tempoLock.isLocked -> "🔒 LOCKED"
                        else -> "LISTENING…"
                    },
                    color = if (isPlaying) activeSkinPrimary else KawaiiTextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    // The tempo readout is HELD by MikuTempoLock, so this number does not twitch
                    // between neighbouring BPMs while a track plays at one tempo. Before a lock
                    // exists it says so, rather than showing a per-onset estimate as fact.
                    text = when {
                        !isPlaying -> "ALSA Standby"
                        isPlaying && hasLiveTempo -> "${liveBpm.toInt()} BPM · ${beatIntervalMs}ms"
                        else -> tempoLock.label
                    },
                    color = KawaiiSoftTeal,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
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
                        // Audio can be playing from an app that publishes no metadata
                        // (Spotify, YouTube). Say "Unknown track" rather than naming one.
                        text = when {
                            !isPlaying -> "🌸 MikuOS Player"
                            trackTitle != null -> "♪ $trackTitle"
                            else -> "♪ Unknown track"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            !isPlaying -> "Nothing playing"
                            trackArtist != null -> trackArtist
                            else -> "External audio source · no metadata published"
                        },
                        color = KawaiiMikuMint,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                // Arcade Performance Grade Badge + the ADAPTIVE DIFFICULTY tier underneath it.
                // The tier is shown, never silent: a game that quietly moves the goalposts feels
                // broken, while one that says "🌱 PRACTICE → 💖 DIVA" makes tightening a reward.
                Column(horizontalAlignment = Alignment.End) {
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${rhythmTier.badge} ${rhythmTier.label} ±${windows.perfectMs}ms",
                        color = KawaiiGoldenHoney,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Visual Rhythm Timing Calibration Gauge + live combo ladder
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("EARLY ◀", color = KawaiiPeach, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        // The deadzone already flattened this to 0 if the tap was within about a
                        // frame of the beat, so "ON BEAT" here means genuinely unmeasurable-off,
                        // not "close enough".
                        if (lastTapTimeMs > 0) MikuRhythmTiming.nudgeHint(timingOffsetMs, windows) else "TAP ON BEAT",
                        color = judgmentColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text("▶ LATE", color = KawaiiSoftTeal, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                // Horizontal Target Bar with Deviation Pointer
                KawaiiTimingDeviationBar(offsetMs = timingOffsetMs, windows = windows)
                Spacer(Modifier.height(2.dp))
                BpmComboLadderLine()
            }
        }
    }
}

/**
 * The combo ladder in one line: where you are, what it is worth, and what the next rung costs.
 *
 * Risk/reward needs to be READABLE. "x17 combo" alone says nothing; "x17 · 3.0x yield · 15 more
 * → x4" turns the next fifteen taps into a decision the player can choose to make. The shields
 * on the right are the mercy rule made visible, so a break feels survivable rather than random.
 *
 * Kept as its own composable: MikuBpmObservatoryModal was already split because a Compose
 * function over ART's 16384-instruction JIT ceiling runs interpreted forever.
 */
@Composable
private fun BpmComboLadderLine() {
    val combo by MikuBeatClickerEngine.combo.collectAsState()
    val shields by MikuBeatClickerEngine.comboShields.collectAsState()
    val mult = MikuBeatClickerEngine.effectiveComboMultiplier(combo)
    val next = MikuBeatClickerEngine.nextComboRung(combo)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "x$combo · ${String.format(Locale.US, "%.1f", mult)}× YIELD",
            color = if (combo >= 16) KawaiiGoldenHoney else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            if (next != null) "${next.first} more → ${String.format(Locale.US, "%.1f", next.second)}×" else "★ TOP RUNG",
            color = KawaiiSakuraPink,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (shields > 0) "🛡️".repeat(shields) else "no shield",
            color = if (shields > 0) KawaiiMikuMint else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Today's Setlist — three daily goals, the session's reason to start and to come back.
 *
 * Every counter is judged taps only, and the accuracy goal reads "—" until there are enough
 * judged taps to state a rate honestly.
 */
@Composable
private fun BpmDailySetlistRow() {
    val daily by MikuBeatClickerEngine.dailySetlist.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BpmSetlistChip(
            "TAPS",
            "${daily.judgedTaps}/${MikuBeatClickerEngine.DailySetlist.GOAL_TAPS}",
            daily.tapsDone
        )
        BpmSetlistChip(
            "STREAK",
            "${daily.bestCombo}/${MikuBeatClickerEngine.DailySetlist.GOAL_COMBO}",
            daily.comboDone
        )
        BpmSetlistChip(
            "≥GREAT",
            if (daily.accuracyPct >= 0f) "${daily.accuracyPct.toInt()}%/${MikuBeatClickerEngine.DailySetlist.GOAL_ACCURACY_PCT.toInt()}%" else "—",
            daily.accuracyDone
        )
    }
}

/**
 * Tap-tempo vs detected-tempo agreement, stated honestly.
 *
 * BPM comparison must tolerate a few BPM (the detector is an estimator, not ground truth) and
 * must treat half/double-time as a match: a 170 BPM track tapped at 85 is CORRECT. With nothing
 * detected there is nothing to compare, and it says exactly that rather than implying a verdict.
 */
@Composable
private fun BpmTempoMatchLine(hasLiveTempo: Boolean, liveBpm: Float, tappedBpm: Float?) {
    if (tappedBpm == null) return
    val text: String
    val color: Color
    if (!hasLiveTempo) {
        text = "no detected tempo to compare against"
        color = KawaiiTextMuted
    } else {
        val m = MikuRhythmTiming.octaveMultiplier(tappedBpm, liveBpm)
        if (m != null) {
            text = "✓ matches detected ${liveBpm.toInt()} BPM (${MikuRhythmTiming.octaveLabel(m)}, ±${MikuRhythmTiming.bpmTolerance(liveBpm).toInt()} BPM)"
            color = KawaiiMikuMint
        } else {
            text = "✕ ${tappedBpm.toInt()} vs detected ${liveBpm.toInt()} BPM — try /2 or x2"
            color = KawaiiPeach
        }
    }
    Text(
        text,
        color = color,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * A live thumbnail of what a skin actually looks like: its node silhouette, its ambient
 * treatment and its burst glyphs, drawn with its own palette. A colour swatch cannot show that
 * Cyber Mirai is a hexagon in data rain and Snow Crystal is a snowflake in drifting snow — and
 * "you can't tell the skins apart" is exactly the complaint this answers.
 */
@Composable
private fun BpmSkinPreview(skin: MikuBeatClickerEngine.BpmSkin, dimmed: Boolean) {
    val alpha = if (dimmed) 0.35f else 1f
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(skin.primaryColor).copy(alpha = 0.5f * alpha), RoundedCornerShape(10.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.26f
            val primary = Color(skin.primaryColor).copy(alpha = alpha)
            val accent = Color(skin.accentColor).copy(alpha = alpha)

            // Ambient signature, three marks in the skin's own idiom.
            when (skin.ambient) {
                MikuBeatClickerEngine.AmbientStyle.PETALS ->
                    for (i in 0 until 3) drawKawaiiHeart(
                        Offset(size.width * (0.2f + i * 0.3f), size.height * (0.16f + (i % 2) * 0.62f)),
                        5f, accent.copy(alpha = 0.55f * alpha)
                    )
                MikuBeatClickerEngine.AmbientStyle.HEX_RAIN ->
                    for (i in 0 until 4) drawLine(
                        accent.copy(alpha = 0.5f * alpha),
                        Offset(size.width * (0.12f + i * 0.25f), 2f),
                        Offset(size.width * (0.12f + i * 0.25f), size.height * (0.22f + (i % 3) * 0.16f)),
                        strokeWidth = 2f, cap = StrokeCap.Round
                    )
                MikuBeatClickerEngine.AmbientStyle.HONEY_BUBBLES ->
                    for (i in 0 until 3) drawCircle(
                        accent.copy(alpha = 0.45f * alpha),
                        4f + i * 2f,
                        Offset(size.width * (0.18f + i * 0.32f), size.height * (0.8f - i * 0.12f)),
                        style = Stroke(width = 1.2f)
                    )
                MikuBeatClickerEngine.AmbientStyle.DAMASK_VEIL ->
                    for (i in 0 until 3) drawPath(
                        polygonPath(size.width * (0.2f + i * 0.3f), size.height * (0.2f + (i % 2) * 0.6f), 6f, 4, -90f),
                        accent.copy(alpha = 0.35f * alpha)
                    )
                MikuBeatClickerEngine.AmbientStyle.SNOW_DRIFT ->
                    for (i in 0 until 3) drawPath(
                        starPath(size.width * (0.18f + i * 0.32f), size.height * (0.18f + (i % 2) * 0.64f), 5f, 2f, 6, -90f),
                        accent.copy(alpha = 0.6f * alpha), style = Stroke(width = 1f)
                    )
            }

            // The node silhouette itself — the headline difference.
            drawPath(skinOutline(skin.nodeShape, c.x, c.y, r), primary.copy(alpha = 0.55f * alpha))
            drawPath(
                skinOutline(skin.nodeShape, c.x, c.y, r * 1.5f),
                Color.White.copy(alpha = 0.5f * alpha), style = Stroke(width = 1.4f)
            )
        }
        Text(
            skin.burstGlyphs.take(2).joinToString(""),
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .graphicsLayer { this.alpha = alpha }
        )
    }
}

/**
 * "NEXT: <reward> · <progress> → <where>" — the single most important progression line in the
 * game, because it is the only one that says why the next hundred taps are worth doing.
 */
@Composable
private fun BpmNextUnlockLine() {
    val ctx = LocalContext.current
    // Recomputed whenever the lifetime stats move, i.e. on every judged tap.
    val lifetime by MikuBpmSeasonsEngine.lifetime.collectAsState()
    val next = remember(lifetime) { MikuUnlocks.nextLocked(ctx) }
    if (next == null) {
        Text(
            "🏆 Every reward earned — all ${MikuUnlocks.ALL.size} unlocked",
            color = KawaiiGoldenHoney,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "NEXT: ${next.icon} ${next.title} → ${next.where}",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        val frac = MikuUnlocks.progressFraction(next)
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(KawaiiMikuMint, KawaiiGoldenHoney)))
            )
        }
        Text(
            MikuUnlocks.progressLabel(next),
            color = KawaiiGoldenHoney,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

/**
 * One cross-app reward, locked or earned, with its destination spelled out.
 */
@Composable
private fun BpmRewardCard(reward: MikuUnlocks.Reward, earned: Boolean) {
    Box(
        Modifier
            .width(168.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x33000000))
            .border(1.dp, if (earned) KawaiiGoldenHoney else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${reward.icon} ${reward.title}",
                    color = if (earned) Color.White else KawaiiTextMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(if (earned) "✅" else "🔒", fontSize = 11.sp)
            }
            // Where it lands, verbatim — a reward you cannot find is not a reward.
            Text(
                reward.where,
                color = KawaiiSoftTeal,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (earned) {
                Text("EARNED", color = KawaiiGoldenHoney, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
            } else {
                Text(
                    MikuUnlocks.progressLabel(reward),
                    color = KawaiiGoldenHoney,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BpmSetlistChip(label: String, value: String, done: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (done) Color(0x33FFD166) else Color(0x22FFFFFF))
            .border(1.dp, if (done) KawaiiGoldenHoney else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "${if (done) "✅" else "🎯"} $label $value",
            color = if (done) KawaiiGoldenHoney else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Latency calibration strip.
 *
 * The whole chain (mixer buffer + DAC ahead of the Visualizer capture, touch sampling on the way
 * back) adds a CONSTANT delay, which would otherwise make every honest tap read "late" forever.
 * The CALIBRATE chip applies the median of the player's own recent judged deviations — real
 * measured data, not a guess — and only appears once there are enough samples for a median to
 * mean something. The platform's latency figure is printed as information and never applied
 * silently, because on this device it is a lower bound (one mixer buffer), not the real value.
 */
@Composable
private fun BpmTimingCalibrationRow() {
    val ctx = LocalContext.current
    val offset by MikuRhythmCalibration.offsetMs.collectAsState()
    val suggestion by MikuRhythmCalibration.suggestionMs.collectAsState()
    val samples by MikuRhythmCalibration.sampleCount.collectAsState()
    val note by MikuRhythmCalibration.latencyNote.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "OFFSET ${if (offset >= 0) "+" else ""}${offset}ms" + if (note.isNotEmpty()) " · $note" else "",
            color = KawaiiTextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x2239C5BB))
                .border(1.dp, KawaiiMikuMint, RoundedCornerShape(8.dp))
                .clickable {
                    MikuRhythmCalibration.nudge(-5)
                    com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("−5", color = KawaiiMikuMint, fontSize = 10.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x2239C5BB))
                .border(1.dp, KawaiiMikuMint, RoundedCornerShape(8.dp))
                .clickable {
                    MikuRhythmCalibration.nudge(5)
                    com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("+5", color = KawaiiMikuMint, fontSize = 10.sp, fontWeight = FontWeight.Black) }
        if (suggestion != null) {
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x55FF3385))
                    .border(1.dp, KawaiiHotPink, RoundedCornerShape(8.dp))
                    .clickable {
                        val applied = MikuRhythmCalibration.applySuggestion()
                        com.miku.launcher.haptics.MikuHaptics.confirm(ctx)
                        if (applied != null) {
                            android.widget.Toast.makeText(
                                ctx,
                                "Timing offset calibrated to ${if (applied >= 0) "+" else ""}${applied}ms from your last $samples judged taps",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    "🎯 CALIBRATE ${if ((suggestion ?: 0) >= 0) "+" else ""}${suggestion}ms",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
            }
        }
    }
}

/**
 * Mode 1 hero: sweet leek harvest totals, per-second yield and combo tier.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroHarvestCard(
    liveBpm: Float,
    leekCount: Double,
    clickerCombo: Int,
    feverSeconds: Int
) {
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
}

/**
 * Mode 2 hero: producer skill-tree summary card.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSkillsCard(
    clickerSkills: List<MikuBeatClickerEngine.SkillUpgrade>
) {
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
}

/**
 * Mode 3 hero: active aesthetic skin card.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSkinsCard(
    currentSkin: MikuBeatClickerEngine.BpmSkin,
    activeSkinPrimary: Color
) {
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
}

/**
 * Mode 4 hero: quest progress plus calibration-database stat badges.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroQuestsCard(
    achievements: List<MikuBeatClickerEngine.Achievement>,
    dbStats: Map<String, Any>
) {
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
        // What the player is working toward RIGHT NOW, with the exact requirement. Progression
        // that cannot be seen coming does not motivate anyone.
        BpmNextUnlockLine()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val totalCal = dbStats["totalTracks"] ?: 0
            val userCal = dbStats["userCalibrated"] ?: 0
            val totalTaps = dbStats["totalTaps"] ?: 0
            // -1 (or dbStats not loaded yet) = no taps logged → "—", not a fake 100 %.
            val accPct = (dbStats["perfectAccuracyPct"] as? Int) ?: -1
            KawaiiStatBadge("DB TRACKS", "$totalCal", KawaiiSoftTeal)
            KawaiiStatBadge("USER CAL", "$userCal", KawaiiSakuraPink)
            KawaiiStatBadge("LOGGED TAPS", "$totalTaps", KawaiiGoldenHoney)
            // Labelled PERFECT %, not "ACCURACY": the query behind it counts only accuracy='PERFECT'
            // rows, and with five graded tiers a GREAT is not a failure — calling the perfect rate
            // "accuracy" would understate a good player exactly as badly as it once overstated one.
            KawaiiStatBadge("PERFECT %", if (accPct >= 0) "$accPct%" else "—", KawaiiMikuMint)
        }
    }
}

/**
 * Mode 5 hero: season rank, season score and lifetime stat badges.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSeasonsCard(
    currentSeason: MikuBpmSeasonsEngine.SeasonStats,
    lifetimeStats: MikuBpmSeasonsEngine.LifetimeStats
) {
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

/**
 * Center stage: golden-leek target, the Project DIVA beat node with its full tap-judgment
 * pipeline, floating numbers and the floating judgment title.
 *
 * The mutable tap/judgment state is passed in as the parent's own MutableState holders and
 * re-delegated below, so writes and the reads that follow them inside the single onTap
 * callback still observe the new value exactly as they did when this code lived inline.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun ColumnScope.BpmBeatNodeStage(
    approachRadius: Float,
    pulseScale: Float,
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    beatIntervalMs: Long,
    lastBeatEpochMs: Long,
    goldenLeekVisible: Boolean,
    skin: MikuBeatClickerEngine.BpmSkin,
    feverSeconds: Int,
    clickerCombo: Int,
    floatingTexts: List<MikuBeatClickerEngine.FloatingText>,
    trackArtist: String?,
    trackTitle: String?,
    bpmDb: MikuBpmDatabase,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    tapTimestamps: MutableList<Long>,
    tapCounterState: MutableIntState,
    calculatedTapBpmState: MutableState<Float?>,
    timingOffsetMsState: MutableIntState,
    judgmentTitleState: MutableState<String>,
    judgmentColorState: MutableState<Color>,
    lastTapTimeMsState: MutableLongState,
    perfectShockwaveTriggerState: MutableIntState,
    dbStatsState: MutableState<Map<String, Any>>
) {
    val ctx = LocalContext.current
    var tapCounter by tapCounterState
    var calculatedTapBpm by calculatedTapBpmState
    var timingOffsetMs by timingOffsetMsState
    var judgmentTitle by judgmentTitleState
    var judgmentColor by judgmentColorState
    var lastTapTimeMs by lastTapTimeMsState
    var perfectShockwaveTrigger by perfectShockwaveTriggerState
    var dbStats by dbStatsState
    // The most recent cross-app unlock, shown as a banner over the node for a few seconds.
    var newUnlock by remember { mutableStateOf<MikuUnlocks.Reward?>(null) }
    LaunchedEffect(newUnlock) {
        if (newUnlock != null) {
            delay(4500L)
            newUnlock = null
        }
    }

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
            approachRadius = approachRadius,
            pulseScale = pulseScale,
            isPlaying = isPlaying,
            feverActive = feverSeconds > 0,
            perfectShockwaveTrigger = perfectShockwaveTrigger,
            skin = skin,
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

                // ---- JUDGMENT ------------------------------------------------------------
                // A judgment requires a REAL, recent pulse from the detector. Everything below
                // is measured against that pulse and nothing else — deliberately NOT against
                // `lastBeatEpochMs`, the local metronome, whose phase is anchored on whenever
                // isPlaying/beatIntervalMs last changed and is therefore arbitrary. (That, plus
                // the older `elapsedRealtime % beatPeriod`, is exactly how every tap once scored
                // PERFECT against a beat that did not exist.) With no pulse this is a FREE TAP:
                // nothing is scored, nothing is persisted, and the UI says so.
                val tier = MikuBeatClickerEngine.rhythmTier.value
                val windows = MikuRhythmTiming.windowsFor(
                    beatPeriodMs = beatIntervalMs,
                    leniency = tier.leniency,
                    bonusMs = MikuBeatClickerEngine.timingWindowBonusMs
                )
                // Read the pulse timestamp from the engine: this composable was extracted for the
                // ART JIT limit and does not receive the whole BpmState.
                val signedOffset: Int? = if (isPlaying) MikuRhythmTiming.signedOffsetToBeat(
                    tapEpochMs = System.currentTimeMillis(),
                    lastPulseEpochMs = MikuBpmEngine.state.value.lastPulseEpochMs,
                    beatPeriodMs = beatIntervalMs,
                    // Constant device latency (output + touch) measured out, so a clean tap does
                    // not read permanently "late". See MikuRhythmCalibration.
                    calibrationMs = MikuRhythmCalibration.offsetMs.value
                ) else null
                val accuracy: HitAccuracy? = signedOffset?.let { MikuRhythmTiming.judge(it, windows) }
                // Deadzone: inside ~1 frame the deviation is below what this panel can resolve,
                // so it displays as a clean 0 rather than fake precision.
                timingOffsetMs = if (signedOffset != null) MikuRhythmTiming.displayOffsetMs(signedOffset, windows) else 0

                // Haptic FIRST — one short sharp pulse the instant the judgment is known,
                // before any scoring/DB work, so it lands on the finger, not after it.
                com.miku.launcher.haptics.MikuHaptics.beat(ctx, accuracy?.hapticStrength ?: 0)

                if (accuracy == null || signedOffset == null) {
                    // FREE TAP: the idle economy still responds to the finger, but no accuracy
                    // counter, combo, fever, season rank or telemetry row is touched.
                    MikuBeatClickerEngine.freeTap()
                    // Say WHY it is not scoring: acquiring a tempo lock, or nothing playing.
                    judgmentTitle = if (isPlaying) "FREE TAP · ${MikuTempoLock.tempo.value.label}" else "FREE TAP · nothing playing"
                    judgmentColor = KawaiiSoftTeal
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        MikuBeatClickerEngine.checkAchievements()
                    }
                } else {
                    val comboBefore = clickerCombo
                    // Real measured deviation feeds the offset calibrator (judged taps only).
                    MikuRhythmCalibration.recordJudgedDeviation(signedOffset)
                    MikuBeatClickerEngine.tap(accuracy, liveBpm, tier.scoreScale)
                    val comboAfter = MikuBeatClickerEngine.combo.value
                    // Crossing a combo rung is the moment worth celebrating — a distinct double
                    // tap of haptics marks it so the player feels the multiplier change.
                    if (MikuBeatClickerEngine.comboMultiplier(comboAfter) >
                        MikuBeatClickerEngine.comboMultiplier(comboBefore)) {
                        com.miku.launcher.haptics.MikuHaptics.confirm(ctx)
                    }
                    val pts = ((accuracy.seasonPoints * 5L +
                        comboBefore * (if (accuracy.isGreatOrBetter) 15L else 5L)) * tier.scoreScale).toLong()
                    // Only a REAL detected tempo may reach the seasons engine: it feeds
                    // lifetimeStats.highestBpmLocked, which the "HIGH BPM" badge renders. The
                    // 120f rhythm-game default used to be persisted there as a measurement.
                    MikuBpmSeasonsEngine.recordTap(
                        accuracy, comboBefore, pts,
                        if (hasLiveTempo) liveBpm else 0f
                    )

                    // Telemetry Logging to SQLite Database — judged taps only. deviationMs is
                    // the raw measured value, not the deadzone-flattened display value.
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        bpmDb.logTapTelemetry(
                            MikuBpmDatabase.TapTelemetryRecord(
                                artist = trackArtist ?: "",
                                title = trackTitle ?: "",
                                tapEpochMs = System.currentTimeMillis(),
                                targetBeatMs = beatIntervalMs,
                                deviationMs = signedOffset,
                                accuracy = accuracy.name,
                                // 0 = tempo not detected; never the 120f game default.
                                instantaneousBpm = if (hasLiveTempo) liveBpm else 0f,
                                comboAtTap = comboBefore,
                                isFever = feverSeconds > 0
                            )
                        )
                        MikuBeatClickerEngine.checkAchievements()
                        // Cross-app unlocks are earned here and ONLY here: this branch is the
                        // judged-tap branch, so nothing a free tap did can ever pay one out.
                        val granted = MikuUnlocks.evaluate(ctx)
                        if (granted.isNotEmpty()) {
                            newUnlock = granted.last()
                            MikuSeasonalAudioEngine.playLevelUpFanfare()
                        }
                        dbStats = bpmDb.getCalibrationStats()
                    }

                    // Feedback that TEACHES: the tier word plus which way to move next time.
                    judgmentTitle = "${accuracy.emoji} ${accuracy.label} · ${MikuRhythmTiming.nudgeHint(timingOffsetMs, windows)}"
                    judgmentColor = when (accuracy) {
                        HitAccuracy.PERFECT -> KawaiiHotPink
                        HitAccuracy.GREAT -> KawaiiSakuraPink
                        HitAccuracy.GOOD -> KawaiiSoftTeal
                        HitAccuracy.OK -> KawaiiGoldenHoney
                        HitAccuracy.MISS -> Color(0xFFFF6B8B)
                    }
                    if (accuracy == HitAccuracy.PERFECT) perfectShockwaveTrigger++
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

        // Cross-app unlock banner — names the reward AND where it shows up, so an unlock is a
        // concrete thing the player can go and use, not a vague "+1 unlocked".
        newUnlock?.let { r ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC2A1206))
                    .border(1.5.dp, KawaiiGoldenHoney, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "${r.icon} UNLOCKED · ${r.title}  →  ${r.where}",
                    color = KawaiiGoldenHoney,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Floating Judgment Title
        if (judgmentTitle.isNotEmpty()) {
            Text(
                // Typography is part of the skin: arcade skins shout in wide-tracked Audiowide,
                // the soft skins speak in the normal face. Same information, different voice.
                text = if (skin.judgmentArcade) judgmentTitle else judgmentTitle.lowercase(Locale.US),
                color = judgmentColor,
                fontSize = if (skin.judgmentArcade) 15.sp else 16.sp,
                fontWeight = FontWeight.Black,
                fontFamily = if (skin.judgmentArcade) AudiowideFont else null,
                letterSpacing = skin.judgmentSpacing.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-14).dp)
            )
        }
    }
}

/**
 * Bottom dock for modes 1..4: buildings shop, skill upgrades, skin selector, quest list.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmGameDockCards(
    selectedMode: Int,
    leekCount: Double,
    clickerBuildings: List<MikuBeatClickerEngine.Building>,
    clickerSkills: List<MikuBeatClickerEngine.SkillUpgrade>,
    currentSkin: MikuBeatClickerEngine.BpmSkin,
    achievements: List<MikuBeatClickerEngine.Achievement>
) {
    val ctx = LocalContext.current
    // Re-read when the tab changes (and on every recomposition of this dock) so a reward earned
    // mid-session shows as EARNED without reopening the modal.
    val earnedUnlocks = remember(selectedMode, achievements) { MikuUnlocks.unlockedIds(ctx) }

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
                // Availability is a real gate, and a locked card says exactly what earns it
                // instead of just being greyed out.
                val available = remember(skin, currentSkin) { skin.isAvailable(ctx) }
                val lockReq = remember(skin) {
                    skin.unlockId?.let { id -> MikuUnlocks.rewardFor(id) }
                }
                Box(
                    Modifier
                        .width(176.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(
                            1.5.dp,
                            if (isCurrent) Color(skin.primaryColor)
                            else if (!available) Color(0x22FFFFFF) else Color(0x44FFFFFF),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (MikuBeatClickerEngine.setSkin(ctx, skin)) {
                                com.miku.launcher.haptics.MikuHaptics.tick(ctx)
                            } else {
                                com.miku.launcher.haptics.MikuHaptics.reject(ctx)
                                android.widget.Toast.makeText(
                                    ctx,
                                    lockReq?.let { "🔒 ${it.title}: ${MikuUnlocks.progressLabel(it)}" }
                                        ?: "🔒 Locked",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(6.dp)
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        // A REAL preview of the difference — the actual node silhouette, ambient
                        // treatment and burst glyphs — not a colour chip.
                        BpmSkinPreview(skin = skin, dimmed = !available)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(
                                "${skin.icon} ${skin.displayName}",
                                color = if (available) Color.White else KawaiiTextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                skin.blurb,
                                color = KawaiiTextMuted,
                                fontSize = 8.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    !available && lockReq != null -> "🔒 ${MikuUnlocks.progressLabel(lockReq)}"
                                    isCurrent -> "✨ ACTIVE"
                                    else -> "TAP TO APPLY"
                                },
                                color = if (isCurrent) Color(skin.primaryColor) else if (available) KawaiiSoftTeal else KawaiiGoldenHoney,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )
                        }
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
            // Cross-app rewards live in the same row as the quests: earned ones first so the
            // player sees what they already own, then the ladder ahead of them in order.
            items(MikuUnlocks.ALL) { r ->
                BpmRewardCard(reward = r, earned = r.id in earnedUnlocks)
            }
        }
    }
}

/**
 * Bottom dock for modes 0 and 5: stat badges plus the tap-tempo calibration controls
 * (lock BPM, /2, x2, DB lookup) and the hint line.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmCalibrationFooter(
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    leekCount: Double,
    clickerCombo: Int,
    currentSeason: MikuBpmSeasonsEngine.SeasonStats,
    trackArtist: String?,
    trackTitle: String?,
    bpmDb: MikuBpmDatabase,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    tapCounterState: MutableIntState,
    calculatedTapBpmState: MutableState<Float?>,
    dbStatsState: MutableState<Map<String, Any>>
) {
    val ctx = LocalContext.current
    val tapCounter by tapCounterState
    var calculatedTapBpm by calculatedTapBpmState
    var dbStats by dbStatsState

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

        // Today's Setlist goals — the reason to start a session and to come back tomorrow.
        BpmDailySetlistRow()

        // Does the tapped tempo agree with what the detector heard? Octave-aware, so tapping
        // half-time on a 170 BPM track reads as CORRECT instead of wrong.
        BpmTempoMatchLine(hasLiveTempo = hasLiveTempo, liveBpm = liveBpm, tappedBpm = calculatedTapBpm)

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

                            // Save to SQLite Database — only for a track we can actually
                            // name. A calibration row keyed on an invented title would
                            // be served back later as a real user calibration.
                            if (trackArtist != null && trackTitle != null) {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    bpmDb.saveTrackBpm(
                                        MikuBpmDatabase.TrackBpmRecord(
                                            artist = trackArtist,
                                            title = trackTitle,
                                            canonicalBpm = tapVal,
                                            // 0 = nothing was auto-detected for this
                                            // track; don't store the game's 120f default
                                            // as a "raw detected" measurement.
                                            rawDetectedBpm = if (hasLiveTempo) liveBpm else 0f,
                                            userTappedBpm = tapVal,
                                            // Octave-aware and epsilon-tolerant: the detector is
                                            // an estimator, so "within a couple of BPM" is the
                                            // same tempo, and half/double-time is a MATCH with a
                                            // known multiplier rather than a disagreement. 1.0
                                            // when nothing was detected to relate it to.
                                            tempoMultiplier = if (hasLiveTempo)
                                                (MikuRhythmTiming.octaveMultiplier(tapVal, liveBpm) ?: 1.0f)
                                            else 1.0f,
                                            confidence = 1.0f,
                                            source = "USER_CALIBRATED",
                                            tapCount = tapCounter
                                        )
                                    )
                                    dbStats = bpmDb.getCalibrationStats()
                                }
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
                                if (trackArtist == null || trackTitle == null) {
                                    android.widget.Toast.makeText(ctx, "No track metadata published — nothing to look up", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val online = bpmDb.resolveCanonicalBpm(trackArtist, trackTitle)
                                if (online != null && online.canonicalBpm > 0f) {
                                    calculatedTapBpm = online.canonicalBpm
                                    android.widget.Toast.makeText(ctx, "🌐 DB Resolved: ${online.canonicalBpm.toInt()} BPM (${online.source})", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    // There is no DSP analyser to fall back to; don't claim one.
                                    android.widget.Toast.makeText(ctx, "No BPM found in the local DB or dictionary", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("🌐 DB", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
            }
        }

        // Constant output/touch latency trim — see BpmTimingCalibrationRow.
        BpmTimingCalibrationRow()

        Spacer(Modifier.height(3.dp))
        Text(
            text = if (isPlaying) "🌸 Tap node when the glowing ring closes on center!" else "⏸️ Playback Paused · Tap node to measure tempo (free taps are not scored)",
            color = if (isPlaying) KawaiiMikuMint else KawaiiTextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}


/**
 * Top header: drag handle, the six gamified mode tab pills, close button and the rainbow
 * sugar fever gauge.
 *
 * Extracted from [MikuBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmObservatoryHeader(
    selectedModeState: MutableIntState,
    currentSeason: MikuBpmSeasonsEngine.SeasonStats,
    activeSkinPrimary: Color,
    feverEnergy: Float,
    feverSeconds: Int,
    onClose: () -> Unit
) {
    var selectedMode by selectedModeState

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
}
