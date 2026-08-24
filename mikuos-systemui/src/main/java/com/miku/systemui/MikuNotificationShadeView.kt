package com.miku.systemui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.systemui.weather.MikuWeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}

@Composable
fun CyberHeartGlyph(
    color: Color,
    size: androidx.compose.ui.unit.Dp = 9.5.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, 0f, h * 0.38f, 0f, h * 0.24f)
            cubicTo(0f, h * 0.08f, w * 0.18f, 0f, w * 0.36f, 0f)
            cubicTo(w * 0.44f, 0f, w * 0.5f, h * 0.08f, w * 0.5f, h * 0.16f)
            cubicTo(w * 0.5f, h * 0.08f, w * 0.56f, 0f, w * 0.64f, 0f)
            cubicTo(w * 0.82f, 0f, w, h * 0.08f, w, h * 0.24f)
            cubicTo(w, h * 0.38f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
fun KawaiiHeartColon(
    color: Color,
    scale: Float,
    size: androidx.compose.ui.unit.Dp = 9.5.dp,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .padding(horizontal = 3.dp)
            .scale(scale)
    ) {
        CyberHeartGlyph(color = color, size = size)
        CyberHeartGlyph(color = color, size = size)
    }
}

@Composable
fun KawaiiAnimatedDigitPair(
    digits: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { idx, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) { height -> -height } + fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.65f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    )) togetherWith (slideOutVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { height -> height } + fadeOut(tween(160)) + scaleOut(
                        targetScale = 1.18f,
                        animationSpec = tween(220)
                    ))
                },
                label = "KawaiiDigit_${idx}_$char"
            ) { targetChar ->
                Text(
                    text = targetChar.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun CyberPlasmaGlowClock(
    time: String,
    date: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ClockPlasmaPulse")

    val colorShiftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "24BitColorShift"
    )

    val secondTick by produceState(initialValue = (System.currentTimeMillis() / 1000) % 2) {
        while (true) {
            val sec = (System.currentTimeMillis() / 1000)
            value = sec % 2
            val msToNextSec = 1000L - (System.currentTimeMillis() % 1000L)
            delay(msToNextSec.coerceIn(50L, 1000L))
        }
    }

    val heartbeatScale = remember { Animatable(1.0f) }
    LaunchedEffect(secondTick) {
        heartbeatScale.snapTo(1.32f)
        heartbeatScale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }

    val hoursShiftColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.85f, brightness = 1.0f)
    val hoursCoreColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.12f, brightness = 1.0f)

    val colonHarmonicOffset = if (secondTick == 0L) 25f else 165f
    val colonHue = colorShiftPhase + colonHarmonicOffset
    val colonColor = cyber24BitColorShift(colonHue, saturation = 0.95f, brightness = 1.0f)
    val colonHaloColor = cyber24BitColorShift(colonHue + 20f, saturation = 0.85f, brightness = 0.95f)

    val minutesShiftColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.85f, brightness = 1.0f)
    val minutesCoreColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.12f, brightness = 1.0f)

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PlasmaAlpha"
    )

    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = -2.8f,
        targetValue = 2.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ChromaticPhase"
    )

    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ParticlePhase"
    )

    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanlineY"
    )

    val bar1 by infiniteTransition.animateFloat(initialValue = 0.25f, targetValue = 0.95f, animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1")
    val bar2 by infiniteTransition.animateFloat(initialValue = 0.85f, targetValue = 0.20f, animationSpec = infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "b2")
    val bar3 by infiniteTransition.animateFloat(initialValue = 0.35f, targetValue = 1.0f, animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b3")
    val bar4 by infiniteTransition.animateFloat(initialValue = 0.90f, targetValue = 0.45f, animationSpec = infiniteRepeatable(tween(510), RepeatMode.Reverse), label = "b4")
    val bar5 by infiniteTransition.animateFloat(initialValue = 0.20f, targetValue = 0.80f, animationSpec = infiniteRepeatable(tween(390), RepeatMode.Reverse), label = "b5")
    val bar6 by infiniteTransition.animateFloat(initialValue = 0.70f, targetValue = 0.30f, animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "b6")

    val timeParts = remember(time) {
        if (time.contains(":")) {
            val idx = time.indexOf(":")
            Pair(time.substring(0, idx), time.substring(idx + 1))
        } else {
            Pair(time, "")
        }
    }

    Column {
        Box(contentAlignment = Alignment.CenterStart) {
            Canvas(
                modifier = Modifier
                    .size(width = 135.dp, height = 40.dp)
            ) {
                val particles = listOf(
                    Triple(0.12f, 0.25f, Color(0xFF00E5FF)),
                    Triple(0.28f, 0.65f, Color(0xFFFF007F)),
                    Triple(0.45f, 0.15f, Color(0xFF00FFCC)),
                    Triple(0.58f, 0.80f, Color(0xFFFFFFFF)),
                    Triple(0.72f, 0.35f, Color(0xFF00E5FF)),
                    Triple(0.85f, 0.70f, Color(0xFFFF007F)),
                    Triple(0.92f, 0.20f, Color(0xFF76FF03)),
                    Triple(0.38f, 0.90f, Color(0xFF00E5FF)),
                    Triple(0.65f, 0.45f, Color(0xFFFFD600))
                )

                particles.forEachIndexed { i, p ->
                    val baseX = p.first * size.width
                    val baseY = p.second * size.height
                    val driftY = (baseY - (particlePhase * size.height) + (i * 12f)) % size.height
                    val wobbleX = baseX + (kotlin.math.sin((particlePhase * 6.28f) + i) * 6f).toFloat()
                    val pAlpha = (kotlin.math.sin((particlePhase * 3.14f) + (i * 0.7f)).toFloat().coerceIn(0.15f, 0.9f)) * glowAlpha
                    val radius = if (i % 2 == 0) 2.2f else 1.5f

                    drawCircle(
                        color = p.third.copy(alpha = pAlpha),
                        radius = radius,
                        center = Offset(wobbleX, driftY)
                    )
                }

                val scanY = scanlineY * size.height
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            hoursShiftColor.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.75f),
                            minutesShiftColor.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, scanY),
                    end = Offset(size.width, scanY),
                    strokeWidth = 1.2f
                )
            }

            // Layer 2: Radiant Volumetric 24-Bit Plasma Halo
            Box(
                Modifier
                    .offset(x = 1.dp, y = 2.dp)
                    .size(width = 135.dp, height = 40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                hoursShiftColor.copy(alpha = glowAlpha * 0.65f),
                                minutesShiftColor.copy(alpha = glowAlpha * 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Layer 3: Chromatic Aberration Fringe (Offset 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (-1.2).dp + (shimmerPhase * 0.25f).dp, y = (-0.7).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 180f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.9f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 230f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
            }

            // Layer 4: Chromatic Aberration Fringe (Offset 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (1.2).dp - (shimmerPhase * 0.25f).dp, y = (0.9).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 290f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.85f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 340f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
            }

            // Layer 5: Intense 24-Bit Neon Under-Glow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = 0.6.dp, y = 0.6.dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
            }

            // Layer 6: Brilliant 24-Bit Holographic Luminous Core
            Row(verticalAlignment = Alignment.CenterVertically) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursCoreColor,
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesCoreColor,
                    fontSize = 36.sp
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Date Subheader
        Box(contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dateParts = date.split("/")
                if (dateParts.size == 3) {
                    Text(
                        text = dateParts[0],
                        color = Color(0xFF80FFFF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = " / ",
                        color = Color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = dateParts[1],
                        color = Color(0xFF70FFF0),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = " / ",
                        color = Color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = dateParts[2],
                        color = Color(0xFFD1B3FF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                } else {
                    Text(
                        text = date,
                        color = MikuCyan,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 6-Channel Mini Audio Spectrum Phosphor Bars
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier.height(11.dp)
                ) {
                    Box(Modifier.width(2.dp).height((11 * bar1).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(MikuCyan, Color(0xFF00FFCC)))))
                    Box(Modifier.width(2.dp).height((11 * bar2).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(MikuNeonPink, Color(0xFFFF4081)))))
                    Box(Modifier.width(2.dp).height((11 * bar3).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Color(0xFF00FF7F), Color(0xFF76FF03)))))
                    Box(Modifier.width(2.dp).height((11 * bar4).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(MikuCyan, Color(0xFF00B0FF)))))
                    Box(Modifier.width(2.dp).height((11 * bar5).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(MikuNeonPink, Color(0xFFFF007F)))))
                    Box(Modifier.width(2.dp).height((11 * bar6).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Color(0xFF00FFCC), MikuCyan))))
                }
            }
        }
    }
}

@Composable
fun CyberQuickTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val tileShape = remember { RoundedCornerShape(14.dp) }

    Box(
        modifier = modifier
            .clip(tileShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isActive) 0.25f else 0.12f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.6f)
                    )
                )
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(0.9.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.verticalGradient(
                        if (isActive) listOf(accentColor.copy(alpha = 0.35f), Color(0xFF071922), Color(0xFF030D12))
                        else listOf(Color(0xEE0B222C), Color(0xFF05131A), Color(0xFF02090D))
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            if (isActive) listOf(
                                accentColor.copy(alpha = 0.95f),
                                Color(0xFFB388FF).copy(alpha = 0.6f),
                                accentColor.copy(alpha = 0.8f)
                            )
                            else listOf(
                                CyberGlassBorder.copy(alpha = 0.7f),
                                Color.Transparent,
                                CyberGlassBorder.copy(alpha = 0.4f)
                            )
                        )
                    ),
                    RoundedCornerShape(13.dp)
                )
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isActive) accentColor.copy(alpha = 0.22f) else Color(0x18FFFFFF))
                        .border(
                            0.8.dp,
                            if (isActive) accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = title,
                        tint = if (isActive) accentColor else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1
                    )
                    Text(
                        text = subtitle,
                        color = if (isActive) accentColor else MikuTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun MikuNotificationShadeView(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Hardware Audio States
    var gainMode by remember { mutableStateOf(CirrusLogicManager.getGainMode(ctx)) }
    var filterMode by remember { mutableStateOf(CirrusLogicManager.getDigitalFilter(ctx)) }
    var dreEnabled by remember { mutableStateOf(CirrusLogicManager.isDreEnabled(ctx)) }
    var isPulsarActive by remember { mutableStateOf(true) }

    // Telemetry & Weather
    val weatherState by MikuWeatherService.state.collectAsState()

    // Brightness Controller
    val cr = ctx.contentResolver
    var brightness by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { 128 }
        )
    }

    // Volume Controller
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var streamVol by remember {
        mutableStateOf(am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8)
    }
    val maxVol = remember { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }

    // System Status
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    val isCharging = try {
        val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val bStatus = ctx.registerReceiver(null, ifilter)?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        bStatus == BatteryManager.BATTERY_STATUS_CHARGING || bStatus == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Throwable) { false }

    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val isWifiConnected = wm?.isWifiEnabled == true && wm.connectionInfo?.networkId != -1

    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    var currentDate by remember { mutableStateOf(SimpleDateFormat("MM / dd / yyyy", Locale.getDefault()).format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            currentDate = SimpleDateFormat("MM / dd / yyyy", Locale.getDefault()).format(Date())
            gainMode = CirrusLogicManager.getGainMode(ctx)
            filterMode = CirrusLogicManager.getDigitalFilter(ctx)
            dreEnabled = CirrusLogicManager.isDreEnabled(ctx)
            delay(1000)
        }
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6040D12))
            .clickable { onDismiss() }
    ) {
        // High-Tech Cyber Glass Container (Safe screen inset with smooth corners)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(22.dp))
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
                                CyberGlassBorder.copy(alpha = 0.4f),
                                MikuNeonPink.copy(alpha = 0.65f)
                            )
                        )
                    ),
                    RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Grabber handle — swipe UP or tap to dismiss the shade
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -4f) onDismiss()
                            }
                        },
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .padding(bottom = 8.dp)
                            .width(46.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MikuCyan.copy(alpha = 0.7f))
                            .clickable { onDismiss() }
                    )
                }

                // Top Control Header: Clock, Battery Pill & Settings
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CyberPlasmaGlowClock(time = currentTime, date = currentDate)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Battery Chip
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF061820))
                                .border(0.8.dp, MikuCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                    contentDescription = null,
                                    tint = if (isCharging) Color(0xFF00E676) else MikuCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$batteryPct%",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }

                        // Open Miku Cyber Settings Gear Button
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0x3300E5FF))
                                .border(1.dp, MikuCyan, CircleShape)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Cyber Settings", tint = MikuCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Dual Network / Bluetooth Connectivity Pills
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Internet Card (Wi-Fi + 4G LTE)
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isWifiConnected) MikuCyan.copy(alpha = 0.2f) else Color(0xFF081820))
                            .border(1.dp, if (isWifiConnected) MikuCyan else Color(0xFF1E3A45), RoundedCornerShape(14.dp))
                            .clickable {
                                try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (_: Throwable) {}
                            }
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = if (isWifiConnected) MikuCyan else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Internet", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text(if (isWifiConnected) "5GHz Wi-Fi + LTE" else "LTE Connected", color = MikuCyan, fontSize = 9.sp)
                            }
                        }
                    }

                    // Bluetooth Card (LDAC Hi-Res)
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0A1828))
                            .border(1.dp, Color(0xFF2979FF).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .clickable {
                                try {
                                    val intent = Intent().setClassName("com.miku.settings", "com.miku.settings.MikuSettingsActivity").apply {
                                        putExtra("extra_section", "bluetooth")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    ctx.startActivity(intent)
                                } catch (_: Throwable) {
                                    try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (_: Throwable) {}
                                }
                            }
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = Color(0xFF2979FF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Bluetooth", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("LDAC Hi-Res 990k", color = Color(0xFF82B1FF), fontSize = 9.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Interactive Brightness Slider
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF081C24))
                        .border(1.dp, MikuCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = MikuCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = brightness.toFloat(),
                            onValueChange = { newB ->
                                brightness = newB.toInt()
                                try {
                                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness)
                                } catch (_: Throwable) {}
                            },
                            valueRange = 10f..255f,
                            colors = SliderDefaults.colors(
                                thumbColor = MikuCyan,
                                activeTrackColor = MikuCyan,
                                inactiveTrackColor = Color(0xFF0D2C35)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Master Audio Output & Volume Slider
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A1420))
                        .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFFB388FF), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = streamVol.toFloat(),
                            onValueChange = { newV ->
                                streamVol = newV.toInt()
                                try {
                                    am?.setStreamVolume(AudioManager.STREAM_MUSIC, streamVol, 0)
                                } catch (_: Throwable) {}
                            },
                            valueRange = 0f..maxVol.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFB388FF),
                                activeTrackColor = Color(0xFF7C4DFF),
                                inactiveTrackColor = Color(0xFF140D26)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("${((streamVol.toFloat() / maxVol.toFloat()) * 100).toInt()}%", color = Color(0xFFB388FF), fontSize = 10.sp, fontFamily = AudiowideFont)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 8 Cyber Quick Hardware Tiles (MAGICAL MIRAI SOUNDBOARD)
                Text(
                    text = "MAGICAL MIRAI SOUNDBOARD",
                    color = MikuCyan,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                Spacer(Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Row 1: CS43131 Gain + Filter Mode
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "MASTER DYN / GAIN",
                            subtitle = if (gainMode == CirrusLogicManager.GainMode.HIGH) "HIGH (+6dB)" else "LOW (0dB)",
                            icon = Icons.Default.VolumeUp,
                            accentColor = if (gainMode == CirrusLogicManager.GainMode.HIGH) MikuNeonPink else MikuCyan,
                            isActive = gainMode == CirrusLogicManager.GainMode.HIGH,
                            onClick = {
                                val next = if (gainMode == CirrusLogicManager.GainMode.LOW) CirrusLogicManager.GainMode.HIGH else CirrusLogicManager.GainMode.LOW
                                gainMode = next
                                scope.launch(Dispatchers.IO) {
                                    CirrusLogicManager.setGainMode(ctx, next)
                                }
                            }
                        )

                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "VOCAL FILTER",
                            subtitle = filterMode.label,
                            icon = Icons.Default.Tune,
                            accentColor = MikuCyan,
                            isActive = true,
                            onClick = {
                                val all = CirrusLogicManager.DigitalFilter.values()
                                val next = all[(filterMode.ordinal + 1) % all.size]
                                filterMode = next
                                scope.launch(Dispatchers.IO) {
                                    CirrusLogicManager.setDigitalFilter(ctx, next)
                                }
                            }
                        )
                    }

                    // Row 2: Pulsar RGB + Direct ALSA Bypass
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "PULSAR RGB",
                            subtitle = if (isPulsarActive) "BPM SYNC (ON)" else "DISABLED",
                            icon = Icons.Default.Lightbulb,
                            accentColor = MikuNeonPink,
                            isActive = isPulsarActive,
                            onClick = {
                                isPulsarActive = !isPulsarActive
                                scope.launch(Dispatchers.IO) {
                                    PulsarLight.setMode(ctx, if (isPulsarActive) PulsarLight.Mode.AUDIOPHILE_AUTO else PulsarLight.Mode.OFF)
                                }
                            }
                        )

                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "BIT-PERFECT ALSA",
                            subtitle = if (dreEnabled) "DRE ENHANCED" else "STANDARD HAL",
                            icon = Icons.Default.Headphones,
                            accentColor = Color(0xFF00E676),
                            isActive = dreEnabled,
                            onClick = {
                                dreEnabled = !dreEnabled
                                scope.launch(Dispatchers.IO) {
                                    CirrusLogicManager.setDreEnabled(ctx, dreEnabled)
                                }
                            }
                        )
                    }

                    // Row 3: Wireless ADB + System Tools
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val adbIp = remember(isWifiConnected) {
                            try {
                                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                                if (ipInt != 0) {
                                    String.format(
                                        Locale.US,
                                        "%d.%d.%d.%d",
                                        ipInt and 0xff,
                                        ipInt shr 8 and 0xff,
                                        ipInt shr 16 and 0xff,
                                        ipInt shr 24 and 0xff
                                    )
                                } else ""
                            } catch (_: Throwable) { "" }
                        }
                        var isAdbEnabled by remember {
                            mutableStateOf(
                                try {
                                    val p = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
                                    p.inputStream.bufferedReader().readText().trim() == "5555"
                                } catch (_: Throwable) { false }
                            )
                        }

                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "WIRELESS ADB",
                            subtitle = if (isAdbEnabled) "$adbIp:5555" else "PORT 5555",
                            icon = Icons.Default.DeveloperMode,
                            accentColor = if (isAdbEnabled) Color(0xFF00E676) else MikuNeonPink,
                            isActive = isAdbEnabled,
                            onClick = {
                                val next = !isAdbEnabled
                                isAdbEnabled = next
                                scope.launch(Dispatchers.IO) {
                                    val cmd = if (next) {
                                        "setprop persist.adb.tcp.port 5555 && stop adbd && start adbd"
                                    } else {
                                        "setprop persist.adb.tcp.port -1 && stop adbd && start adbd"
                                    }
                                    try {
                                        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                                    } catch (_: Throwable) {}
                                }
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (next) "⚡ Wireless ADB Active on $adbIp:5555" else "Wireless ADB Disabled",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )

                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "DEV OPTIONS",
                            subtitle = "SYSTEM & TOOLS",
                            icon = Icons.Default.Build,
                            accentColor = MikuCyan,
                            isActive = true,
                            onClick = {
                                onDismiss()
                                val intent = ctx.packageManager.getLaunchIntentForPackage("com.miku.settings")?.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                if (intent != null) ctx.startActivity(intent)
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var pauseOnUnplug by remember {
                            mutableStateOf(
                                Settings.Global.getInt(ctx.contentResolver, "miku_pause_on_unplug", 1) == 1
                            )
                        }
                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "PAUSE ON UNPLUG",
                            subtitle = if (pauseOnUnplug) "STOCK BEHAVIOR" else "KEEP PLAYING",
                            icon = Icons.Default.HeadsetOff,
                            accentColor = if (pauseOnUnplug) Color(0xFF00E676) else MikuNeonPink,
                            isActive = pauseOnUnplug,
                            onClick = {
                                val next = !pauseOnUnplug
                                pauseOnUnplug = next
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        Settings.Global.putInt(
                                            ctx.contentResolver, "miku_pause_on_unplug", if (next) 1 else 0
                                        )
                                    }.getOrNull() ?: RootShell.execFast(
                                        "settings put global miku_pause_on_unplug ${if (next) 1 else 0}"
                                    )
                                }
                                ctx.sendBroadcast(
                                    Intent("com.miku.player.SET_PAUSE_ON_UNPLUG")
                                        .setPackage("com.miku.player")
                                        .putExtra("enabled", next)
                                    )
                            }
                        )

                        var isDacMode by remember {
                            mutableStateOf(
                                RootShell.execOut("getprop sys.usb.config")?.contains("audio_source") == true
                            )
                        }

                        CyberQuickTile(
                            modifier = Modifier.weight(1f),
                            title = "USB DAC MODE",
                            subtitle = if (isDacMode) "AUDIO IN ACTIVE" else "MTP / ADB",
                            icon = Icons.Default.Usb,
                            accentColor = if (isDacMode) Color(0xFF00E676) else MikuCyan,
                            isActive = isDacMode,
                            onClick = {
                                val next = !isDacMode
                                isDacMode = next
                                scope.launch(Dispatchers.IO) {
                                    val currentConfig = RootShell.execOut("getprop sys.usb.config")?.trim() ?: "mtp"
                                    val usbConfig = if (next) {
                                        if (currentConfig.contains("audio_source")) currentConfig else currentConfig.replace("mtp", "audio_source")
                                    } else {
                                        if (currentConfig.contains("mtp")) currentConfig else currentConfig.replace("audio_source", "mtp")
                                    }
                                    RootShell.execFast("setprop sys.usb.config $usbConfig")
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Data-Only SIM Shield Banner
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x3300E676))
                        .border(1.dp, Color(0xFF00E676), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00E676)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "🛡️ DATA-ONLY SIM SHIELD: GOOGLE FI & IMS NAGS SUPPRESSED",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Weather & Conditions Card
                val w = weatherState.weather
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x3300E5FF))
                        .border(1.dp, MikuCyan, RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.icon, fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${w.tempF.toInt()}°F · ${w.summary} (Feels ${w.feelsLikeF.toInt()}°F)",
                                color = Color.White,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            Text(
                                text = "💧 Humidity: ${w.humidityPct}% · 💨 Wind: ${w.windSpeedMph.toInt()}mph ${w.windDirectionCompass} · ☔ Precip: ${w.precipitationProbPct}%",
                                color = MikuTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Footer Action Bar: Power Menu (Reboot / Power Off / Dismiss)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")).waitFor()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                        border = BorderStroke(1.dp, MikuCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("⚡ REBOOT", color = MikuCyan, fontSize = 14.sp, fontFamily = AudiowideFont)
                    }

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")).waitFor()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF4081)),
                        border = BorderStroke(1.dp, MikuNeonPink),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("💤 POWER OFF", color = MikuNeonPink, fontSize = 14.sp, fontFamily = AudiowideFont)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("✕ CLOSE", color = Color.White, fontSize = 14.sp, fontFamily = AudiowideFont)
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
