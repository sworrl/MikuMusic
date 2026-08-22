package com.miku.launcher.bpm

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Official Miku palette accents for this modal (CyberTheme's neon set stays for chrome).
private val MikuTeal = Color(0xFF39C5BB)
private val MikuSakuraPink = Color(0xFFFF4FA3)
private val MikuLightTeal = Color(0xFF9FF3EC)
private val MikuDeepDark = Color(0xFF091420)

/** Beat-match judgment tiers for the rhythm game. */
private enum class BeatJudgment { PERFECT, GOOD, MISS }

/**
 * Hatsune Miku Live BPM Cyber Observatory Modal.
 *
 * Every animation in here ticks off ONE beat clock derived from the live engine interval
 * (beatIntervalMs), re-anchored on each real BPM_PULSE broadcast — nothing free-wheels on a
 * fake timer. Contents: beat-pulsing hero tempo ring, now-playing telemetry, beat-synced
 * spectrum + waveform, a beat-match rhythm game around the tap orb (Perfect/Good/Miss,
 * combo + score), and a "Guess the BPM" challenge.
 */
@Composable
fun MikuBpmObservatoryModal(
    onClose: () -> Unit,
    bpmState: MikuBpmEngine.BpmState
) {
    val ctx = LocalContext.current
    // Tempo readouts MUST come from this one guarded value — never from a re-derived
    // interval (an old build showed the seconds-per-beat derivation, 60/128 ≈ "0.5", as if
    // it were the BPM). Finite + 40..260 or the 128 house-tempo fallback.
    val liveBpm = if (bpmState.bpm.isFinite() && bpmState.bpm in 40f..260f) bpmState.bpm else 128f
    val isPlaying = bpmState.isPlaying
    // Single canonical tick period; engine already sanitizes, clamp again for animation math.
    val beatIntervalMs = bpmState.beatIntervalMs.coerceIn(240L, 1600L)
    val dominantColor = Color(bpmState.dominantColor)

    // Now Playing metadata from Settings.Global. Keyed on the fields that change per TRACK,
    // not on bpmState itself — lastPulseEpochMs churns every beat and would re-run these
    // settings IPC reads ~2x/second for nothing.
    val cr = ctx.contentResolver
    val trackTitle = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_title") ?: "World is Mine" } catch (_: Throwable) { "World is Mine" }
    }
    val trackArtist = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_artist") ?: "supercell feat. Hatsune Miku" } catch (_: Throwable) { "supercell feat. Hatsune Miku" }
    }
    val trackFormat = remember(isPlaying, bpmState.dominantColor, bpmState.bpm) {
        try { android.provider.Settings.Global.getString(cr, "miku_now_playing_format") ?: "192kHz / 24-bit FLAC • ALSA Direct" } catch (_: Throwable) { "192kHz / 24-bit FLAC • ALSA Direct" }
    }

    // ================================================================
    // THE BEAT CLOCK — one metronome for the whole modal.
    // Restarting on lastPulseEpochMs re-anchors phase to the player's real
    // beat broadcasts; between broadcasts it free-runs at the live interval.
    // ================================================================
    var beatTick by remember { mutableLongStateOf(0L) }
    var lastBeatAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying, beatIntervalMs, bpmState.lastPulseEpochMs) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            lastBeatAtMs = SystemClock.elapsedRealtime()
            beatTick += 1
            delay(beatIntervalMs)
        }
    }
    // beatEnv: 1.0 at the beat, decays — the "punch" every visual multiplies by.
    // beatPhase: 0→1 linear sweep across the beat — arcs and closing rings.
    val beatEnv = remember { Animatable(0f) }
    val beatPhase = remember { Animatable(0f) }
    LaunchedEffect(beatTick) {
        if (beatTick == 0L) return@LaunchedEffect
        launch {
            beatEnv.snapTo(1f)
            beatEnv.animateTo(0f, tween((beatIntervalMs * 0.85f).toInt().coerceAtLeast(120), easing = LinearOutSlowInEasing))
        }
        launch {
            beatPhase.snapTo(0f)
            beatPhase.animateTo(1f, tween(beatIntervalMs.toInt(), easing = LinearEasing))
        }
    }

    // Tap-tempo calibration state (feeds the orb's readout).
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    var calculatedTapBpm by remember { mutableStateOf<Float?>(null) }

    // Beat-match rhythm game state.
    var gameScore by remember { mutableLongStateOf(0L) }
    var gameCombo by remember { mutableIntStateOf(0) }
    var gameBestCombo by remember { mutableIntStateOf(0) }
    var judgmentText by remember { mutableStateOf("") }
    var judgmentColor by remember { mutableStateOf(MikuTeal) }
    var judgmentSeq by remember { mutableIntStateOf(0) }
    val judgmentPop = remember { Animatable(0f) }
    LaunchedEffect(judgmentSeq) {
        if (judgmentSeq > 0) {
            judgmentPop.snapTo(1f)
            judgmentPop.animateTo(0f, tween(700, easing = LinearOutSlowInEasing))
        }
    }
    // Pink glow burst across the game card on a PERFECT hit.
    var flashSeq by remember { mutableIntStateOf(0) }
    val perfectFlash = remember { Animatable(0f) }
    LaunchedEffect(flashSeq) {
        if (flashSeq > 0) {
            perfectFlash.snapTo(0.55f)
            perfectFlash.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        }
    }

    // "Guess the BPM" challenge state: 0 idle · 1 tapping blind · 2 result.
    var guessPhase by remember { mutableIntStateOf(0) }
    val guessTaps = remember { mutableStateListOf<Long>() }
    var guessBpm by remember { mutableStateOf<Float?>(null) }
    var guessResult by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) } // guess, actual, diffPct
    val bpmHidden = guessPhase == 1 // hero masks the number while the player is guessing

    // Ambient per-bar oscillation for the spectrum (the beat punch is applied on top).
    val infiniteTransition = rememberInfiniteTransition(label = "ObservatoryAmbient")
    val spectrumOsc = List(20) { idx ->
        infiniteTransition.animateFloat(
            initialValue = 0.18f + (idx % 4) * 0.07f,
            targetValue = 0.85f - (idx % 5) * 0.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(240 + (idx * 37) % 340, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "spec_$idx"
        )
    }
    val waveScroll by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "waveScroll"
    )

    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6040D12))
            .clickable { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Cyber Glass Container (inset from physical screen edges)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B1B26), MikuDeepDark, Color(0xFA020A0E))
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                MikuTeal.copy(alpha = 0.85f),
                                dominantColor.copy(alpha = 0.6f),
                                MikuSakuraPink.copy(alpha = 0.7f)
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
                // Grab bar
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MikuTeal.copy(alpha = 0.6f))
                )
                Spacer(Modifier.height(8.dp))

                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚡ MIKU BPM & AUDIO ENGINE",
                                color = MikuTeal,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPlaying) Color(0x3300FF7F) else Color(0x3339C5BB))
                                    .border(0.8.dp, if (isPlaying) Color(0xFF00FF7F) else MikuTeal, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (isPlaying) "PLAYING" else "STANDBY",
                                    color = if (isPlaying) Color(0xFF00FF7F) else MikuTeal,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                        Text(
                            "Autocorrelation Tempo Engine • Beat-Synced Observatory",
                            color = MikuTextSecondary,
                            fontSize = 8.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // HERO: BEAT-PULSING TEMPO RING + LARGE LIVE BPM READOUT
                // ============================================================
                Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = center
                        val baseR = size.minDimension / 2f - 10f
                        val env = beatEnv.value
                        // Halo bloom on the beat
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(dominantColor.copy(alpha = 0.30f * env), Color.Transparent),
                                center = c, radius = baseR * (1.05f + 0.14f * env)
                            ),
                            radius = baseR * (1.05f + 0.14f * env), center = c
                        )
                        // Static track ring
                        drawCircle(
                            color = MikuTeal.copy(alpha = 0.22f),
                            radius = baseR, center = c, style = Stroke(width = 7f)
                        )
                        // Pulse ring — kicks outward and brightens on every beat
                        drawCircle(
                            color = lerp(MikuTeal, MikuSakuraPink, env).copy(alpha = 0.35f + 0.6f * env),
                            radius = baseR * (1f + 0.05f * env), center = c,
                            style = Stroke(width = 4f + 7f * env)
                        )
                        // Beat sweep arc — one full revolution per beat, tip = the metronome
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(Color.Transparent, MikuLightTeal, MikuSakuraPink), center = c
                            ),
                            startAngle = -90f,
                            sweepAngle = 360f * beatPhase.value,
                            useCenter = false,
                            topLeft = Offset(c.x - baseR, c.y - baseR),
                            size = androidx.compose.ui.geometry.Size(baseR * 2f, baseR * 2f),
                            style = Stroke(width = 6f, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            // Locale.US: tempo must never render with a comma decimal.
                            text = if (bpmHidden) "??" else String.format(Locale.US, "%.0f", liveBpm),
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = if (bpmHidden) "NO PEEKING" else "BPM",
                            color = if (bpmHidden) MikuSakuraPink else MikuTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 3.sp
                        )
                        if (!bpmHidden) {
                            Text(
                                text = String.format(Locale.US, "%.1f · %d ms/beat", liveBpm, beatIntervalMs),
                                color = MikuTextSecondary,
                                fontSize = 7.5.sp,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ============================================================
                // NOW PLAYING TELEMETRY CARD
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(dominantColor.copy(alpha = 0.22f), Color(0xFF041218), Color(0xFF02090D))
                            )
                        )
                        .border(1.dp, Brush.linearGradient(listOf(dominantColor.copy(alpha = 0.8f), MikuTeal.copy(alpha = 0.4f))), RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .scale(1f + 0.05f * beatEnv.value) // art chip nods to the beat
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF051820))
                                .border(1.dp, MikuTeal.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isPlaying) dominantColor else MikuTeal,
                                modifier = Modifier.size(22.dp)
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
                                color = MikuLightTeal,
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
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // LIVE SPECTRUM + WAVEFORM — punched by the beat envelope
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF06121C))
                        .border(1.dp, MikuTeal.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LIVE SPECTRUM · BEAT-SYNCED", color = MikuTeal, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            // 4-count beat step dots, driven by the real beat clock
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (0..3).forEach { step ->
                                    val active = isPlaying && step == (beatTick % 4).toInt()
                                    Box(
                                        Modifier
                                            .size(if (active) 6.dp else 5.dp)
                                            .clip(CircleShape)
                                            .background(if (active) MikuSakuraPink else Color(0x33FFFFFF))
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // 20-bar equalizer: ambient oscillation × beat punch (bass side hits harder)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            spectrumOsc.forEachIndexed { idx, barAnim ->
                                val bassBias = 1f - idx / 20f
                                val punch =
                                    if (isPlaying) 0.45f + 0.55f * (0.35f + 0.65f * beatEnv.value * (0.35f + 0.65f * bassBias))
                                    else 0.30f
                                val barColor = when {
                                    idx < 5 -> MikuTeal
                                    idx < 10 -> MikuLightTeal
                                    idx < 15 -> dominantColor
                                    else -> MikuSakuraPink
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 1.dp)
                                        .fillMaxHeight((barAnim.value * punch).coerceIn(0.05f, 1f))
                                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                                        .background(Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.25f))))
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Waveform ribbon: amplitude breathes with the beat envelope
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                        ) {
                            val amp = size.height * 0.42f * (if (isPlaying) 0.35f + 0.65f * beatEnv.value else 0.22f)
                            val midY = size.height / 2f
                            val path = Path()
                            val steps = 64
                            for (i in 0..steps) {
                                val x = size.width * i / steps
                                val t = i.toFloat() / steps
                                val y = midY +
                                    amp * kotlin.math.sin((t * 4f + waveScroll) * 2f * Math.PI.toFloat()) *
                                    kotlin.math.sin((t + waveScroll * 0.5f) * Math.PI.toFloat())
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path,
                                brush = Brush.horizontalGradient(listOf(MikuTeal, MikuLightTeal, MikuSakuraPink)),
                                style = Stroke(width = 3f, cap = StrokeCap.Round)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // BEAT MATCH — RHYTHM GAME around the Kawaii tap orb
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF071722))
                        .border(1.dp, Brush.linearGradient(listOf(MikuSakuraPink.copy(alpha = 0.7f), MikuTeal.copy(alpha = 0.5f))), RoundedCornerShape(14.dp))
                ) {
                    // PERFECT-hit glow burst over the whole card
                    if (perfectFlash.value > 0.01f) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(
                                    Brush.radialGradient(
                                        listOf(MikuSakuraPink.copy(alpha = perfectFlash.value), Color.Transparent)
                                    )
                                )
                        )
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "♥ BEAT MATCH · RHYTHM GAME",
                            color = MikuSakuraPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            if (isPlaying) "Tap the orb exactly ON the beat" else "Play a track to arm the beat judge — free tap-tempo below",
                            color = MikuTextSecondary,
                            fontSize = 8.sp
                        )

                        Spacer(Modifier.height(6.dp))

                        // Score row
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TapStatChip(label = "SCORE", value = "$gameScore", accent = MikuLightTeal)
                            TapStatChip(label = "COMBO", value = "x$gameCombo", accent = if (gameCombo >= 10) MikuGold else MikuSakuraPink)
                            TapStatChip(label = "MAX", value = "x$gameBestCombo", accent = MikuTeal)
                        }

                        Spacer(Modifier.height(6.dp))

                        Box(contentAlignment = Alignment.Center) {
                            // Rhythm-game closing target ring: spawns wide at each beat and
                            // contracts onto the orb rim exactly at the NEXT beat — that's
                            // the moment to tap.
                            if (isPlaying) {
                                Canvas(Modifier.size(200.dp)) {
                                    val c = center
                                    val orbR = 74.dp.toPx()
                                    val ringR = orbR + (size.minDimension / 2f - orbR) * (1f - beatPhase.value)
                                    drawCircle(
                                        color = MikuLightTeal.copy(alpha = 0.25f + 0.65f * beatPhase.value),
                                        radius = ringR, center = c,
                                        style = Stroke(width = 3f + 4f * beatPhase.value)
                                    )
                                }
                            }

                            KawaiiWaifuTapTempoOrb(
                                dominantColor = dominantColor,
                                calculatedBpm = calculatedTapBpm,
                                onTap = {
                                    val now = SystemClock.elapsedRealtime()

                                    // -- Tap-tempo calibration (original behavior) --
                                    // Same session rule as the orb's own game: a >2s gap starts
                                    // fresh, so a stale run can't skew the averaged tempo.
                                    if (tapTimestamps.isNotEmpty() && now - tapTimestamps.last() > 2000L) {
                                        tapTimestamps.clear()
                                    }
                                    tapTimestamps.add(now)
                                    if (tapTimestamps.size > 6) tapTimestamps.removeAt(0)
                                    if (tapTimestamps.size >= 2) {
                                        val intervals = (1 until tapTimestamps.size).map { tapTimestamps[it] - tapTimestamps[it - 1] }
                                        val avgInterval = intervals.average()
                                        if (avgInterval > 0) {
                                            calculatedTapBpm = (60_000.0 / avgInterval).toFloat().coerceIn(40f, 260f)
                                        }
                                    }

                                    // -- Beat-match judgment (only meaningful with a live beat) --
                                    if (isPlaying && lastBeatAtMs > 0L) {
                                        val since = (now - lastBeatAtMs).mod(beatIntervalMs)
                                        val err = minOf(since, beatIntervalMs - since)
                                        // Windows scale with tempo so fast tracks stay fair.
                                        val perfectWin = maxOf(60L, beatIntervalMs / 8)
                                        val goodWin = maxOf(130L, beatIntervalMs / 4)
                                        val judgment = when {
                                            err <= perfectWin -> BeatJudgment.PERFECT
                                            err <= goodWin -> BeatJudgment.GOOD
                                            else -> BeatJudgment.MISS
                                        }
                                        when (judgment) {
                                            BeatJudgment.PERFECT -> {
                                                gameCombo += 1
                                                val pts = 100L + gameCombo * 10L
                                                gameScore += pts
                                                judgmentText = "PERFECT!! +$pts"
                                                judgmentColor = MikuSakuraPink
                                                flashSeq += 1
                                                // Celebration double-buzz on top of the orb's own
                                                // haptic — VIBRATE is declared in the manifest.
                                                try {
                                                    val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                        vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 18, 40, 26), intArrayOf(0, 255, 0, 180), -1))
                                                    }
                                                } catch (_: Throwable) {}
                                            }
                                            BeatJudgment.GOOD -> {
                                                gameCombo += 1
                                                val pts = 50L + gameCombo * 5L
                                                gameScore += pts
                                                judgmentText = "GOOD +$pts"
                                                judgmentColor = MikuLightTeal
                                            }
                                            BeatJudgment.MISS -> {
                                                gameCombo = 0
                                                judgmentText = "MISS…"
                                                judgmentColor = Color(0xFFFF6E6E)
                                            }
                                        }
                                        if (gameCombo > gameBestCombo) gameBestCombo = gameCombo
                                        if (gameCombo > 0 && gameCombo % 10 == 0) {
                                            judgmentText = "★ COMBO x$gameCombo ★"
                                            judgmentColor = MikuGold
                                        }
                                        judgmentSeq += 1
                                    }
                                }
                            )

                            // Judgment pop — scales up and floats away above the orb
                            if (judgmentPop.value > 0.02f && judgmentText.isNotEmpty()) {
                                Text(
                                    text = judgmentText,
                                    color = judgmentColor,
                                    fontSize = (12f + 6f * judgmentPop.value).sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .graphicsLayer {
                                            alpha = judgmentPop.value
                                            translationY = -26f * (1f - judgmentPop.value)
                                        }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // GUESS THE BPM — blind tempo challenge
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF06131E))
                        .border(1.dp, MikuLightTeal.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "🎯 GUESS THE BPM",
                            color = MikuLightTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        when (guessPhase) {
                            0 -> {
                                Text(
                                    "Think you know the tempo? The readout hides while you tap it from feel.",
                                    color = MikuTextSecondary,
                                    fontSize = 8.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                GuessActionPill(text = "START CHALLENGE", accent = MikuSakuraPink, enabled = isPlaying) {
                                    guessTaps.clear()
                                    guessBpm = null
                                    guessResult = null
                                    guessPhase = 1
                                }
                                if (!isPlaying) {
                                    Spacer(Modifier.height(3.dp))
                                    Text("Needs a playing track", color = MikuTextSecondary, fontSize = 7.5.sp)
                                }
                            }
                            1 -> {
                                Text(
                                    "Tap the pink pad to the music — at least 4 taps, then lock it in.",
                                    color = MikuTextSecondary,
                                    fontSize = 8.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                // The guess pad — its own tap session, 2.5s idle gap resets
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .background(Brush.horizontalGradient(listOf(MikuSakuraPink.copy(alpha = 0.35f), Color(0xFF13060F))))
                                        .border(1.2.dp, MikuSakuraPink, RoundedCornerShape(26.dp))
                                        .clickable {
                                            val now = SystemClock.elapsedRealtime()
                                            if (guessTaps.isNotEmpty() && now - guessTaps.last() > 2500L) guessTaps.clear()
                                            guessTaps.add(now)
                                            if (guessTaps.size > 8) guessTaps.removeAt(0)
                                            if (guessTaps.size >= 2) {
                                                val iv = (1 until guessTaps.size).map { guessTaps[it] - guessTaps[it - 1] }.average()
                                                if (iv > 0) guessBpm = (60_000.0 / iv).toFloat().coerceIn(40f, 260f)
                                            }
                                            try {
                                                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    vib?.vibrate(android.os.VibrationEffect.createOneShot(20, 160))
                                                }
                                            } catch (_: Throwable) {}
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (guessTaps.isEmpty()) "TAP THE BEAT HERE"
                                        else "${guessTaps.size} TAP${if (guessTaps.size == 1) "" else "S"}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 1.5.sp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GuessActionPill(text = "LOCK IN", accent = MikuTeal, enabled = guessTaps.size >= 4 && guessBpm != null) {
                                        val guess = guessBpm
                                        if (guess != null) {
                                            // Capture the engine tempo at the moment of lock-in.
                                            val actual = liveBpm
                                            val diffPct = (kotlin.math.abs(guess - actual) / actual * 100f)
                                            guessResult = Triple(guess, actual, diffPct)
                                            guessPhase = 2
                                        }
                                    }
                                    GuessActionPill(text = "CANCEL", accent = MikuTextSecondary, enabled = true) {
                                        guessPhase = 0
                                    }
                                }
                            }
                            else -> {
                                val res = guessResult
                                if (res != null) {
                                    val (guess, actual, diffPct) = res
                                    val accuracy = (100f - diffPct).coerceIn(0f, 100f)
                                    val (grade, gradeColor) = when {
                                        diffPct <= 3f -> "S" to MikuGold
                                        diffPct <= 8f -> "A" to MikuTeal
                                        diffPct <= 15f -> "B" to MikuLightTeal
                                        else -> "C" to Color(0xFFFF6E6E)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            grade,
                                            color = gradeColor,
                                            fontSize = 34.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                String.format(Locale.US, "YOU %.0f · ENGINE %.0f BPM", guess, actual),
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                String.format(Locale.US, "%.1f%% accuracy", accuracy),
                                                color = gradeColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    GuessActionPill(text = "TRY AGAIN", accent = MikuSakuraPink, enabled = isPlaying) {
                                        guessTaps.clear()
                                        guessBpm = null
                                        guessResult = null
                                        guessPhase = 1
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // HARDWARE PULSAR & DOCK LINK MATRIX
                // ============================================================
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF05131A))
                            .border(1.dp, MikuSakuraPink.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MikuSakuraPink, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PULSAR LED", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                            Text("Front SGM31324 PWM", color = MikuTextSecondary, fontSize = 7.5.sp)
                            Text("⚡ Hardware Beat Sync: ON", color = Color(0xFF00FF7F), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF05131A))
                            .border(1.dp, MikuTeal.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MikuTeal, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("DOCK ANCHOR", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                            Text("16s Slow Hypnotic Spin", color = MikuTextSecondary, fontSize = 7.5.sp)
                            Text("🌈 Chroma Aura: Active", color = MikuTeal, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Compact action pill for the Guess-the-BPM challenge. */
@Composable
private fun GuessActionPill(text: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) accent.copy(alpha = 0.20f) else Color(0x22333B44))
            .border(1.dp, if (enabled) accent else Color(0x44607078), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            color = if (enabled) accent else Color(0x88AABBC4),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp
        )
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
    // Game state: per-session tap timestamps → tap count + a "steadiness" score (how even your
    // intervals are). A gap > 2s starts a fresh session so the counter/score are per-run.
    val orbTapTimes = remember { mutableStateListOf<Long>() }
    var steadiness by remember { mutableStateOf(0f) }   // 0..1, higher = more even taps
    var bestStreak by remember { mutableIntStateOf(0) }

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
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(170.dp),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Expanding Ripple Shockwave on Tap
            if (shockwaveAnim.value > 0.01f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = (size.width / 2f) * (1.0f + shockwaveAnim.value * 0.7f)
                    drawCircle(
                        color = MikuNeonPink.copy(alpha = (1.0f - shockwaveAnim.value) * 0.8f),
                        radius = radius,
                        style = Stroke(width = 4f)
                    )
                }
            }

            // Layer 2: Steadily Spinning Dual-Tone Aura Ring
            Box(
                Modifier
                    .size(160.dp)
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
                    .size(148.dp)
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
                        // ---- Game mechanic: per-session tap count + steadiness score ----
                        val now = SystemClock.elapsedRealtime()
                        if (orbTapTimes.isNotEmpty() && now - orbTapTimes.last() > 2000L) {
                            // Idle gap → new session: bank the best streak, reset the run.
                            if (tapCounter > bestStreak) bestStreak = tapCounter
                            orbTapTimes.clear()
                            tapCounter = 0
                            steadiness = 0f
                        }
                        orbTapTimes.add(now)
                        if (orbTapTimes.size > 8) orbTapTimes.removeAt(0)
                        tapCounter++
                        if (tapCounter > bestStreak) bestStreak = tapCounter
                        // Steadiness = how even the intervals are (1 - coefficient of variation).
                        if (orbTapTimes.size >= 3) {
                            val iv = (1 until orbTapTimes.size).map { (orbTapTimes[it] - orbTapTimes[it - 1]).toDouble() }
                            val mean = iv.average()
                            if (mean > 0) {
                                val sd = kotlin.math.sqrt(iv.sumOf { (it - mean) * (it - mean) } / iv.size)
                                steadiness = (1.0 - (sd / mean)).coerceIn(0.0, 1.0).toFloat()
                            }
                        }
                        // Haptic feedback — punchier the steadier you are.
                        try {
                            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                            val amp = (60 + (steadiness * 195f)).toInt().coerceIn(1, 255)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, amp))
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
                    if (calculatedBpm != null && tapCounter >= 2) {
                        // Live BPM front and center, big, as you tap.
                        Text(
                            text = String.format(Locale.US, "%.0f", calculatedBpm),
                            color = Color.White,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = "BPM",
                            color = MikuCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 2.sp
                        )
                    } else {
                        Text(
                            text = "♥",
                            color = MikuNeonPink,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "TAP",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Game stat row: tap counter · steadiness score · best streak ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TapStatChip(label = "TAPS", value = "$tapCounter", accent = MikuNeonPink)
            val steadyPct = (steadiness * 100f).toInt()
            val steadyColor = when {
                tapCounter < 3 -> MikuTextSecondary
                steadyPct >= 90 -> Color(0xFF00FF7F)
                steadyPct >= 70 -> Color(0xFFFFD600)
                else -> Color(0xFFFF6E6E)
            }
            TapStatChip(
                label = "STEADY",
                value = if (tapCounter < 3) "--" else "$steadyPct%",
                accent = steadyColor
            )
            TapStatChip(label = "BEST", value = "$bestStreak", accent = MikuCyan)
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                calculatedBpm == null -> "Tap the orb in rhythm to lock the tempo"
                steadiness >= 0.9f && tapCounter >= 4 -> "✨ PERFECT LOCK · ${String.format(Locale.US, "%.1f", calculatedBpm)} BPM"
                else -> "✨ TAP BPM: ${String.format(Locale.US, "%.1f", calculatedBpm)}"
            },
            color = if (calculatedBpm != null) Color(0xFF00FF7F) else MikuTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}

/** Compact stat pill for the game readouts (taps / steadiness / best / score / combo). */
@Composable
private fun TapStatChip(label: String, value: String, accent: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD08202A))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = value,
            color = accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp
        )
    }
}
