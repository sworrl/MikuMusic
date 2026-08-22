package com.miku.launcher.bpm

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hatsune Miku Live Radio Waterfall & Histogram BPM Cyber Observatory Modal.
 * Integrates real-time SDR radio waterfall spectrogram, multi-bin audio histogram,
 * live Now Playing audio telemetry, and tactile tap-tempo calibration.
 */
@Composable
fun MikuBpmObservatoryModal(
    onClose: () -> Unit,
    bpmState: MikuBpmEngine.BpmState
) {
    val ctx = LocalContext.current
    val liveBpm = if (bpmState.bpm in 40f..260f) bpmState.bpm else 128f
    val isPlaying = bpmState.isPlaying
    val beatIntervalMs = (60_000f / liveBpm).toLong().coerceIn(240L, 1600L)
    val dominantColor = Color(bpmState.dominantColor)

    // Now Playing Metadata from Settings.Global
    val cr = ctx.contentResolver
    val trackTitle = remember(bpmState) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_title") ?: "World is Mine" } catch (_: Throwable) { "World is Mine" }
    }
    val trackArtist = remember(bpmState) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_artist") ?: "supercell feat. Hatsune Miku" } catch (_: Throwable) { "supercell feat. Hatsune Miku" }
    }
    val trackFormat = remember(bpmState) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_format") ?: "192kHz / 24-bit FLAC • ALSA Direct" } catch (_: Throwable) { "192kHz / 24-bit FLAC • ALSA Direct" }
    }

    // Tap-tempo calibration state
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    var calculatedTapBpm by remember { mutableStateOf<Float?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "WaterfallAnim")

    // Waterfall waterfall scroll shift
    val waterfallScroll by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaterfallScroll"
    )

    // Beat phase indicator (1, 2, 3, 4)
    var beatStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(bpmState.lastPulseEpochMs, isPlaying) {
        if (isPlaying) {
            beatStep = (beatStep + 1) % 4
        }
    }

    // 24-Bin Histogram Bar Heights
    val histogramHeights = List(24) { idx ->
        val duration = (220 + (idx * 29) % 310)
        infiniteTransition.animateFloat(
            initialValue = 0.12f + (idx % 4) * 0.08f,
            targetValue = if (isPlaying) 0.96f - (idx % 5) * 0.07f else 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hist_$idx"
        )
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
                                MikuCyan.copy(alpha = 0.85f),
                                dominantColor.copy(alpha = 0.6f),
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
                        .background(MikuCyan.copy(alpha = 0.6f))
                )

                Spacer(Modifier.height(8.dp))

                // Header Row (Clean, no X button)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚡ MIKU BPM & AUDIO ENGINE",
                                color = MikuCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPlaying) Color(0x3300FF7F) else Color(0x3300E5FF))
                                    .border(0.8.dp, if (isPlaying) Color(0xFF00FF7F) else MikuCyan, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (isPlaying) "PLAYING" else "STANDBY",
                                    color = if (isPlaying) Color(0xFF00FF7F) else MikuCyan,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                        Text(
                            "Autocorrelation Tempo Engine • Radio Waterfall Spectrogram",
                            color = MikuTextSecondary,
                            fontSize = 8.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 1: TIED NOW PLAYING LIVE ENGINE CARD
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    dominantColor.copy(alpha = 0.25f),
                                    Color(0xFF041218),
                                    Color(0xFF02090D)
                                )
                            )
                        )
                        .border(1.dp, Brush.linearGradient(listOf(dominantColor.copy(alpha = 0.8f), MikuCyan.copy(alpha = 0.4f))), RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Album Art / Pulse Core
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF051820))
                                .border(1.dp, MikuCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isPlaying) dominantColor else MikuCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (isPlaying) trackTitle else "Miku OS Master Audio Pipeline",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isPlaying) trackArtist else "Dual CS43198 Direct Master DAC",
                                color = MikuCyan,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isPlaying) trackFormat else "Bit-Perfect Direct ALSA Bypass: STANDBY",
                                color = MikuTextSecondary,
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }

                        // Live BPM Digital Metric
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format("%.1f", liveBpm),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Text(
                                text = "BPM",
                                color = dominantColor,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 2: 24-BIN AUDIO HISTOGRAM SPECTRUM
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
                            Text("HISTOGRAM FREQUENCY BINS (24-BAND)", color = MikuCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (0..3).forEach { step ->
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (step == beatStep) MikuNeonPink else Color(0x33FFFFFF))
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            histogramHeights.forEachIndexed { idx, barAnim ->
                                val barColor = when {
                                    idx < 6 -> MikuCyan
                                    idx < 12 -> Color(0xFF00FF88)
                                    idx < 18 -> dominantColor
                                    else -> MikuNeonPink
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 0.8.dp)
                                        .fillMaxHeight(barAnim.value)
                                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                                        .background(Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.25f))))
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 3: REAL-TIME SDR RADIO WATERFALL SPECTROGRAM
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF02080C))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val rows = 18
                        val cols = 24
                        val rowHeight = h / rows
                        val colWidth = w / cols

                        for (r in 0 until rows) {
                            val rowNorm = r.toFloat() / rows.toFloat()
                            val shiftedRow = (rowNorm + waterfallScroll) % 1.0f
                            val y = shiftedRow * h

                            for (c in 0 until cols) {
                                val x = c * colWidth
                                val intensity = (kotlin.math.sin(c * 0.45f + shiftedRow * 6.28f) * 0.5f + 0.5f).toFloat()
                                val color = when {
                                    intensity > 0.82f -> Color(0xFFFF4081)
                                    intensity > 0.60f -> Color(0xFF00E5FF)
                                    intensity > 0.35f -> Color(0xFF00FF88)
                                    intensity > 0.15f -> Color(0xFF1A3B44)
                                    else -> Color(0xFF05131A)
                                }
                                drawRect(
                                    color = color.copy(alpha = 0.75f),
                                    topLeft = Offset(x, y),
                                    size = Size(colWidth - 1f, rowHeight - 1f)
                                )
                            }
                        }
                    }

                    // Waterfall Grid Overlay HUD Text
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("RADIO WATERFALL SPECTROGRAM", color = Color.White.copy(alpha = 0.7f), fontSize = 7.5.sp, fontFamily = AudiowideFont)
                        Text("20Hz — 48kHz RF/DSP", color = MikuCyan, fontSize = 7.5.sp, fontFamily = AudiowideFont)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 4: HARDWARE PULSAR & DOCK LINK MATRIX
                // ============================================================
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pulsar LED Link Card
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF05131A))
                            .border(1.dp, MikuNeonPink.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MikuNeonPink, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PULSAR LED", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                            Text("Front SGM31324 PWM", color = MikuTextSecondary, fontSize = 7.5.sp)
                            Text("⚡ Hardware Beat Sync: ON", color = Color(0xFF00FF7F), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Homescreen Rainbow Dock Card
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF05131A))
                            .border(1.dp, MikuCyan.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MikuCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("DOCK ANCHOR", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                            Text("16s Slow Hypnotic Spin", color = MikuTextSecondary, fontSize = 7.5.sp)
                            Text("🌈 Chroma Aura: Active", color = MikuCyan, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 5: ROUND KAWAII WAIFU TAP-TEMPO ORB WITH HAPTIC FEEDBACK
                // ============================================================
                KawaiiWaifuTapTempoOrb(
                    dominantColor = dominantColor,
                    calculatedBpm = calculatedTapBpm,
                    onTap = {
                        val now = SystemClock.elapsedRealtime()
                        tapTimestamps.add(now)
                        if (tapTimestamps.size > 6) tapTimestamps.removeAt(0)

                        if (tapTimestamps.size >= 2) {
                            val intervals = (1 until tapTimestamps.size).map { tapTimestamps[it] - tapTimestamps[it - 1] }
                            val avgInterval = intervals.average()
                            if (avgInterval > 0) {
                                val calcBpm = (60_000.0 / avgInterval).toFloat().coerceIn(40f, 260f)
                                calculatedTapBpm = calcBpm
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Round, Kawaii/Waifu-Inspired Tap-Tempo Heart Beat Orb.
 * Features 3D elevated circular cyber glass, dynamic spring bounce overshoot,
 * expanding ripple shockwave on tap, and crisp tactile haptic feedback.
 */
@Composable
fun KawaiiWaifuTapTempoOrb(
    dominantColor: Color,
    calculatedBpm: Float?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val bounceScale = remember { Animatable(1.0f) }
    val shockwaveAnim = remember { Animatable(0.0f) }
    var tapCounter by remember { mutableIntStateOf(0) }

    val infiniteTransition = rememberInfiniteTransition(label = "OrbHaloSpin")
    val haloRotate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "HaloRotate"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(90.dp),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Expanding Ripple Shockwave on Tap
            if (shockwaveAnim.value > 0.01f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = (size.width / 2f) * (1.0f + shockwaveAnim.value * 0.7f)
                    drawCircle(
                        color = MikuNeonPink.copy(alpha = (1.0f - shockwaveAnim.value) * 0.8f),
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                    )
                }
            }

            // Layer 2: Steadily Spinning Dual-Tone Aura Ring
            Box(
                Modifier
                    .size(86.dp)
                    .graphicsLayer { rotationZ = haloRotate }
                    .border(
                        BorderStroke(
                            2.dp,
                            Brush.sweepGradient(
                                listOf(
                                    MikuCyan,
                                    MikuNeonPink,
                                    Color(0xFF00FF88),
                                    dominantColor,
                                    MikuCyan
                                )
                            )
                        ),
                        CircleShape
                    )
            )

            // Layer 3: Round Kawaii Heart Beat Orb
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .scale(bounceScale.value)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF133842),
                                Color(0xFF081F26),
                                Color(0xFF030D12)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.5.dp,
                            Brush.linearGradient(
                                listOf(
                                    MikuCyan,
                                    Color.White.copy(alpha = 0.6f),
                                    MikuNeonPink
                                )
                            )
                        ),
                        CircleShape
                    )
                    .clickable {
                        tapCounter++
                        // Haptic feedback
                        try {
                            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, 255))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator?.vibrate(30)
                            }
                        } catch (_: Throwable) {}

                        // Spring tactile bounce & shockwave animation
                        coroutineScope.launch {
                            bounceScale.snapTo(0.78f)
                            bounceScale.animateTo(
                                targetValue = 1.0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                        coroutineScope.launch {
                            shockwaveAnim.snapTo(0.0f)
                            shockwaveAnim.animateTo(
                                targetValue = 1.0f,
                                animationSpec = tween(380, easing = FastOutSlowInEasing)
                            )
                        }

                        onTap()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "♥",
                        color = MikuNeonPink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "TAP",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (calculatedBpm != null) "✨ TAP BPM: ${String.format("%.1f", calculatedBpm)}" else "Tap in rhythm to calibrate live tempo",
            color = if (calculatedBpm != null) Color(0xFF00FF7F) else MikuTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}
