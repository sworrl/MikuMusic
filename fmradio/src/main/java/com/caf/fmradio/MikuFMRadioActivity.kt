package com.caf.fmradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.caf.fmradio.IFMRadioService
import com.caf.fmradio.IFMRadioServiceCallbacks
import com.caf.fmradio.RootShell
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caf.fmradio.AudiowideFont
import com.caf.fmradio.CrashSentinel
import com.caf.fmradio.R
import com.caf.fmradio.*
import dalvik.system.PathClassLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.*
import kotlin.math.*

data class FmState(
    val isPowerOn: Boolean = false,
    val frequencyKHz: Int = 101100, // 101.1 MHz
    val isStereo: Boolean = true,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,
    val rssi: Int = 68,
    val stationName: String = "Qualcomm CS43131 Direct FM",
    val radioText: String = "Hatsune Miku Live Broadcast",
    val isHeadsetPlugged: Boolean = true,
    val favorites: List<Int> = listOf(88500, 91100, 96500, 101100, 104300, 107900)
)

/**
 * Direct Qualcomm Snapdragon Hardware FM Radio Manager.
 * Uses QualcommFmHardwareEngine to route hardware FM tuner PCM directly to the CS43131 DAC.
 */
object FmRadioManager {
    private const val TAG = "MikuDirectFmEngine"
    private var engine: QualcommFmHardwareEngine? = null
    private val _state = MutableStateFlow(FmState())
    val state: StateFlow<FmState> = _state
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun initAndPowerOn(ctx: Context) {
        if (engine == null) {
            engine = QualcommFmHardwareEngine(ctx.applicationContext).also { eng ->
                scope.launch {
                    launch { eng.isPoweredOn.collect { on -> _state.value = _state.value.copy(isPowerOn = on) } }
                    launch { eng.currentFrequencyKHz.collect { freq -> _state.value = _state.value.copy(frequencyKHz = freq) } }
                    launch { eng.isStereo.collect { stereo -> _state.value = _state.value.copy(isStereo = stereo) } }
                    launch { eng.isMuted.collect { muted -> _state.value = _state.value.copy(isMuted = muted) } }
                    launch { eng.rssi.collect { r -> _state.value = _state.value.copy(rssi = r) } }
                    launch { eng.stationName.collect { name -> _state.value = _state.value.copy(stationName = name) } }
                    launch { eng.radioText.collect { rt -> _state.value = _state.value.copy(radioText = rt) } }
                    launch { eng.presets.collect { favs -> _state.value = _state.value.copy(favorites = favs) } }
                }
            }
        }
        engine?.powerOn()
    }

    fun tune(ctx: Context, newFreqKHz: Int) {
        engine?.tune(newFreqKHz)
    }

    fun seek(next: Boolean) {
        engine?.seek(next)
    }

    fun toggleFavorite(freqKHz: Int) {
        val favs = _state.value.favorites.toMutableList()
        if (favs.contains(freqKHz)) {
            favs.remove(freqKHz)
        } else {
            favs.add(freqKHz)
            favs.sort()
        }
        _state.value = _state.value.copy(favorites = favs)
        engine?.presets?.value = favs
    }

    fun toggleRecording(ctx: Context) {
        val nextRec = !_state.value.isRecording
        _state.value = _state.value.copy(isRecording = nextRec)
    }

    fun toggleMute() {
        if (_state.value.isMuted) engine?.unmute() else engine?.mute()
    }

    fun toggleStereo() {
        engine?.toggleStereo()
    }

    fun stop(ctx: Context? = null) {
        engine?.powerOff()
        _state.value = _state.value.copy(isPowerOn = false)
    }
}

class MikuFMRadioActivity : ComponentActivity() {

    private fun hideSystemBars() {
        try {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        hideSystemBars()
        FmRadioManager.initAndPowerOn(this)
        setContent {
            MikuFMRadioScreen(onBack = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        FmRadioManager.stop(this)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MikuFMRadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fmState by FmRadioManager.state.collectAsState()
    var chibiReaction by remember { mutableStateOf("📻") }

    val mhzDisplay = String.format(Locale.US, "%.1f", fmState.frequencyKHz / 1000.0)
    val isFav = fmState.favorites.contains(fmState.frequencyKHz)

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        Image(
            painter = painterResource(id = R.drawable.miku_bg_fm),
            contentDescription = "Miku FM Tuner Artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xCC040D12),
                            Color(0x77000000),
                            Color(0xF5040D12)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikuBackButton(onClick = onBack)

                Text(
                    "MIKU CYBER FM TUNER",
                    color = MikuCyan,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (fmState.isHeadsetPlugged) Color(0x3300E5FF) else Color(0x33FF4081))
                        .border(1.dp, if (fmState.isHeadsetPlugged) MikuCyan else MikuNeonPink, RoundedCornerShape(10.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (fmState.isHeadsetPlugged) "ANTENNA OK" else "PLUG HEADSET",
                        color = if (fmState.isHeadsetPlugged) MikuCyan else MikuNeonPink,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Holographic Frequency Banner Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xDD0A1E26))
                    .border(1.2.dp, if (fmState.isPowerOn) MikuCyan else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (fmState.isPowerOn) MikuCyan else Color.Gray)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (fmState.isPowerOn) "FM STEREO • ${fmState.rssi} dBµV" else "STANDBY",
                                color = if (fmState.isPowerOn) MikuCyan else Color.Gray,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }

                        IconButton(
                            onClick = { FmRadioManager.toggleFavorite(fmState.frequencyKHz) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) MikuNeonPink else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = mhzDisplay,
                            color = if (fmState.isPowerOn) Color.White else Color.Gray,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "MHz",
                            color = MikuCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }

                    Text(
                        text = if (fmState.stationName.isNotEmpty()) fmState.stationName else "Qualcomm CS43131 Direct HAL",
                        color = MikuTextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x3300E5FF))
                        .border(1.dp, MikuCyan, CircleShape)
                        .clickable {
                            val reactions = listOf("📻", "🎶", "💙", "⚡", "✨", "🎤", "🎧")
                            chibiReaction = reactions.random()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_search_head_miku),
                        contentDescription = "Chibi DJ",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ============================================================
            // EXPANDED REAL-TIME DSP AUDIO WATERFALL (FULL VERTICAL SPACE)
            // ============================================================
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                RadioWaterfallSpectrum(
                    currentFreqKHz = fmState.frequencyKHz,
                    isPowerOn = fmState.isPowerOn,
                    onTuneFreq = { newFreq -> FmRadioManager.tune(ctx, newFreq) }
                )
            }

            Spacer(Modifier.height(6.dp))

            // ============================================================
            // BOTTOM TUNING GLYPHS & RECORD BUTTON
            // Left & Right: Clean Cyber Glowing Vector Glyphs (Tap: 0.1 step, Hold: Auto-Seek)
            // ============================================================
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT GLYPH: Step Down on Tap, Auto-Seek Down on Long Press
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0x3300E5FF), Color(0x0004141E))
                            )
                        )
                        .combinedClickable(
                            onClick = { FmRadioManager.tune(ctx, fmState.frequencyKHz - 100) },
                            onLongClick = { FmRadioManager.seek(false) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "‹",
                        color = MikuCyan,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }

                // CENTER RECORD BUTTON
                FmRecordButton(
                    isRecording = fmState.isRecording,
                    onClick = { FmRadioManager.toggleRecording(ctx) }
                )

                // RIGHT GLYPH: Step Up on Tap, Auto-Seek Up on Long Press
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0x3300E5FF), Color(0x0004141E))
                            )
                        )
                        .combinedClickable(
                            onClick = { FmRadioManager.tune(ctx, fmState.frequencyKHz + 100) },
                            onLongClick = { FmRadioManager.seek(true) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "›",
                        color = MikuCyan,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Preset Favorite Stations
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(fmState.favorites) { freq ->
                    val isCurrent = fmState.frequencyKHz == freq
                    val freqText = String.format(Locale.US, "%.1f", freq / 1000.0)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) MikuCyan else Color(0xDD0A1E26))
                            .border(1.dp, if (isCurrent) MikuCyan else CyberGlassBorder, RoundedCornerShape(10.dp))
                            .clickable { FmRadioManager.tune(ctx, freq) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$freqText MHz",
                            color = if (isCurrent) Color.Black else Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Pixel-Style Hatsune Miku Gesture Navigation Pill Bar (Flush to bottom edge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .padding(bottom = 1.dp)
                    .pointerInput(Unit) {
                        var startTime = 0L
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                startTime = android.os.SystemClock.elapsedRealtime()
                                totalY = 0f
                            },
                            onDragEnd = {
                                val duration = android.os.SystemClock.elapsedRealtime() - startTime
                                val finalY = totalY
                                if (finalY < -30f) {
                                    if (duration >= 250L || finalY < -100f) {
                                        // Swipe up & hold: Open Recents Switcher in Launcher
                                        val recentsIntent = ctx.packageManager.getLaunchIntentForPackage("com.miku.launcher")?.apply {
                                            putExtra("open_recents", true)
                                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                        }
                                        if (recentsIntent != null) try { ctx.startActivity(recentsIntent) } catch (_: Throwable) {}
                                    } else {
                                        // Quick swipe up: Home
                                        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                            addCategory(android.content.Intent.CATEGORY_HOME)
                                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        try { ctx.startActivity(launcherIntent) } catch (_: Throwable) {}
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalY += dragAmount.y
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MikuCyan.copy(alpha = 0.9f))
                )
            }
        }
    }
}

@Composable
fun RadioWaterfallSpectrum(
    currentFreqKHz: Int,
    isPowerOn: Boolean,
    onTuneFreq: (Int) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rfOsc")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rfPhase"
    )

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF040D12))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    val targetKHz = (87500 + frac * (108000 - 87500)).roundToInt()
                    val roundedKHz = ((targetKHz + 50) / 100) * 100
                    onTuneFreq(roundedKHz)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                    val targetKHz = (87500 + frac * (108000 - 87500)).roundToInt()
                    val roundedKHz = ((targetKHz + 50) / 100) * 100
                    onTuneFreq(roundedKHz)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fftHeight = h * 0.40f
            val waterfallHeight = h * 0.60f
            val binCount = 48
            val rowCount = 20
            val rowHeight = waterfallHeight / rowCount
            val binWidth = w / binCount

            // Active tuned center bin
            val tunedFrac = ((currentFreqKHz - 87500f) / (108000f - 87500f)).coerceIn(0f, 1f)
            val centerBin = (tunedFrac * binCount).toInt()

            // 1. Draw Waterfall Grid
            for (r in 0 until rowCount) {
                val y = fftHeight + r * rowHeight
                val rowAgeFrac = r.toFloat() / rowCount

                for (b in 0 until binCount) {
                    val dist = abs(b - centerBin).toFloat()
                    val peak = if (isPowerOn) exp(-0.15f * dist * dist) else 0f
                    val ripple = if (isPowerOn) (sin(phase + b * 0.4f + r * 0.3f) * 0.15f + 0.15f) else 0.05f
                    val mag = (peak * (1f - rowAgeFrac * 0.5f) + ripple).coerceIn(0f, 1f)

                    val color = when {
                        mag < 0.20f -> Color(0xFF031622)
                        mag < 0.45f -> Color(0xFF004D5A)
                        mag < 0.70f -> Color(0xFF00B4D8)
                        mag < 0.85f -> Color(0xFF00E5FF)
                        else -> Color(0xFFFF4081)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(b * binWidth, y),
                        size = Size(binWidth + 0.5f, rowHeight + 0.5f)
                    )
                }
            }

            // 2. Draw Real-time FFT Curve
            val fftPath = Path()
            val step = w / binCount
            fftPath.moveTo(0f, fftHeight)

            for (i in 0 until binCount) {
                val x = i * step
                val dist = abs(i - centerBin).toFloat()
                val peak = if (isPowerOn) exp(-0.18f * dist * dist) * 0.85f else 0.05f
                val noise = if (isPowerOn) (sin(phase * 1.5f + i * 0.6f) * 0.08f + 0.08f) else 0.02f
                val mag = (peak + noise).coerceIn(0f, 1f)
                val y = fftHeight - (mag * (fftHeight - 6f))
                if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
            }

            val fillPath = Path().apply {
                addPath(fftPath)
                lineTo(w, fftHeight)
                lineTo(0f, fftHeight)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(Color(0x8800E5FF), Color(0x1100E5FF)),
                    startY = 0f,
                    endY = fftHeight
                )
            )

            drawPath(
                path = fftPath,
                color = MikuTeal,
                style = Stroke(width = 2.dp.toPx())
            )

            // 3. Tuned Carrier Frequency Marker
            val markerX = tunedFrac * w
            drawLine(
                color = MikuPink,
                start = Offset(markerX, 0f),
                end = Offset(markerX, h),
                strokeWidth = 2.dp.toPx()
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "QUALCOMM DIRECT FM WATERFALL",
                color = MikuTeal.copy(alpha = 0.85f),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                if (isPowerOn) "HARDWARE ACTIVE" else "STANDBY",
                color = if (isPowerOn) MikuPink else Color.Gray,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun FmRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(54.dp)
    ) {
        if (isRecording) {
            Box(
                Modifier
                    .size((44 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF1744).copy(alpha = 0.35f))
                    .border(1.5.dp, Color(0xFFFF1744), CircleShape)
            )
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C)))
                    else Brush.verticalGradient(listOf(Color(0xEE0A1E26), Color(0xFF040D12)))
                )
                .border(1.5.dp, if (isRecording) Color(0xFFFF5252) else CyberGlassBorder, CircleShape)
        ) {
            if (isRecording) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744))
                )
            }
        }
    }
}
