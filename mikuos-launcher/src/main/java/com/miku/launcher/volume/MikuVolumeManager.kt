package com.miku.launcher.volume
import com.miku.launcher.*

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan
import com.miku.launcher.theme.MikuDiurnalTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VolumeState(
    val volumePct: Int = 50,
    val maxVolume: Int = 15,
    val currentVolume: Int = 8,
    val isMuted: Boolean = false,
    val isHudVisible: Boolean = false
)

object MikuVolumeManager {
    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state

    private var hudDismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRegistered = false

    // ---- HiBy fullscreen volume dialog vs. our color-changing modal ------------------------
    // MikuOS default: HiBy's stock fullscreen volume overlay is OFF and our spectral
    // MikuCyberVolumeHudOverlay is shown instead. Users can opt back into HiBy's via
    // setUseHibyDialog(ctx, true). The choice is persisted and enforced at the system level
    // (Settings.Global hiby_volume_dialog_enable / _indicator) on every init().
    private const val PREFS = "miku_volume_prefs"
    private const val KEY_USE_HIBY = "use_hiby_volume_dialog"
    private const val HIBY_ENABLE_KEY = "hiby_volume_dialog_enable"
    private const val HIBY_INDICATOR_KEY = "hiby_volume_dialog_indicator"

    /** True if the user has opted into HiBy's stock fullscreen volume dialog. Default false. */
    fun useHibyDialog(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_HIBY, false)

    /** Persist + apply the HiBy-vs-Miku volume-UI choice. false = our color modal (default),
     *  true = HiBy's fullscreen dialog. */
    fun setUseHibyDialog(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_HIBY, enabled).apply()
        applyHibyDialogState(ctx)
    }

    /** Enforce the current choice at the OS level. Default (useHiby=false) turns HiBy's stock
     *  fullscreen volume overlay OFF so it doesn't fight our modal. */
    private fun applyHibyDialogState(ctx: Context) {
        val v = if (useHibyDialog(ctx)) 1 else 0
        writeGlobalInt(ctx, HIBY_ENABLE_KEY, v)
        writeGlobalInt(ctx, HIBY_INDICATOR_KEY, v)
    }

    /** Platform-signed launcher can write secure/global settings directly; fall back to a root
     *  shell for non-platform installs. Never throws. */
    private fun writeGlobalInt(ctx: Context, key: String, value: Int) {
        val ok = try {
            Settings.Global.putInt(ctx.applicationContext.contentResolver, key, value)
        } catch (_: Throwable) { false }
        if (!ok) {
            scope.launch(Dispatchers.IO) {
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global $key $value")).waitFor() }
                catch (_: Throwable) {}
            }
        }
    }

    fun init(ctx: Context) {
        updateFromSystem(ctx)
        applyHibyDialogState(ctx)   // enforce HiBy-off-by-default (or the user's saved choice) at boot/init
        if (isRegistered) return
        isRegistered = true

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateFromSystem(ctx)
                triggerHud(ctx)
            }
        }
        try {
            ctx.applicationContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer
            )
        } catch (_: Throwable) {}

        // Global volume change action receiver (catches Spotify, Chrome, physical hardware buttons/rotary dial)
        val volumeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                updateFromSystem(ctx)
                triggerHud(ctx)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
            addAction("android.media.RINGER_MODE_CHANGED")
        }
        try {
            ctx.applicationContext.registerReceiver(volumeReceiver, filter)
        } catch (_: Throwable) {}
    }

    private fun updateFromSystem(ctx: Context) {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val pct = ((cur.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val isMuted = cur == 0

        _state.value = _state.value.copy(
            volumePct = pct,
            currentVolume = cur,
            maxVolume = max,
            isMuted = isMuted
        )
        // Keep the placed volume widgets in step with every level change. pushUpdate throttles
        // itself (knob spins fire in bursts) and no-ops with none placed; never crash the manager.
        try { com.miku.launcher.widget.MikuVolumeWidget.pushUpdate(ctx.applicationContext) } catch (_: Throwable) {}
    }

    fun triggerHud(ctx: Context, delta: Int? = null) {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (delta != null) {
            val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        updateFromSystem(ctx)

        // Pulsar RGB volume feedback — active for exactly the HUD window, INCLUDING when the
        // HiBy dialog owns the on-screen UI (the light shows whenever the modal "is or would
        // be" up). Blue low → purple mid → red high.
        MikuPulsarLight.showVolume(_state.value.volumePct)

        // If the user opted into HiBy's stock fullscreen volume dialog, it owns the on-screen UI —
        // still adjust the level above, but don't raise our modal.
        if (useHibyDialog(ctx)) return

        _state.value = _state.value.copy(isHudVisible = true)
        hudDismissJob?.cancel()
        hudDismissJob = scope.launch {
            delay(3500L)
            _state.value = _state.value.copy(isHudVisible = false)
        }
    }

    fun setVolume(ctx: Context, targetPct: Int) {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = ((targetPct.coerceIn(0, 100) * max) / 100).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        updateFromSystem(ctx)
        triggerHud(ctx)
    }

    fun toggleMute(ctx: Context) {
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        updateFromSystem(ctx)
        triggerHud(ctx)
    }

    fun handleKeyDown(keyCode: Int, ctx: Context): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                triggerHud(ctx, +1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                triggerHud(ctx, -1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                toggleMute(ctx)
                true
            }
            else -> false
        }
    }
}

/**
 * Resolves the dynamic spectral color by sampling along the static vertical volume gradient (0..100%).
 * Bottom (0%): Miku Teal/Mint -> Mid (50%): Electric Lavender -> High (75%): Neon Pink -> Danger (>80%): Coral/Crimson.
 */
fun sampleVolumeGradientColor(pct: Int, isMuted: Boolean = false): Color {
    if (isMuted) return Color(0xFFFF5252)
    val t = (pct.coerceIn(0, 100) / 100f)
    val stops = listOf(
        0.00f to Color(0xFF00E5FF), // Miku Bright Teal (0%)
        0.25f to Color(0xFF00F5D4), // Mint Cyan (25%)
        0.50f to Color(0xFF9D4EDD), // Electric Lavender / Purple (50%)
        0.75f to Color(0xFFFF3399), // Miku Neon Pink (75%)
        0.88f to Color(0xFFFF5252), // Hazard Coral (88%)
        1.00f to Color(0xFFFF1744)  // Danger Red (100%)
    )
    for (i in 0 until stops.size - 1) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (t in p0..p1) {
            val localT = if (p1 > p0) (t - p0) / (p1 - p0) else 0f
            return Color(
                red = c0.red + (c1.red - c0.red) * localT,
                green = c0.green + (c1.green - c0.green) * localT,
                blue = c0.blue + (c1.blue - c0.blue) * localT,
                alpha = 1.0f
            )
        }
    }
    return Color(0xFFFF1744)
}

/** Legacy alias */
fun getVolumeSpectralColor(pct: Int, isMuted: Boolean): Color = sampleVolumeGradientColor(pct, isMuted)

/**
 * Bespoke In-Theme Volume Badge for Designated Top Telemetry Bar.
 * Exact color matches the tip of the volume bar along the static gradient.
 */
@Composable
fun CyberVolumeBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val volState by MikuVolumeManager.state.collectAsState()
    val isDanger = volState.volumePct >= 80
    val dynamicColor = sampleVolumeGradientColor(volState.volumePct, volState.isMuted)

    val infiniteTransition = rememberInfiniteTransition(label = "VolDangerPulse")
    val hazardGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "VolHazard"
    )

    val badgeBorderColor = if (isDanger) dynamicColor.copy(alpha = hazardGlow) else dynamicColor.copy(alpha = 0.9f)

    Box(
        modifier = modifier
            .width(72.dp)
            .height(20.dp)
            .clip(CutCornerShape(4.dp))
            .background(
                Brush.horizontalGradient(
                    if (isDanger) listOf(Color(0x66FF1744), Color(0xFF200508))
                    else listOf(dynamicColor.copy(alpha = 0.28f), Color(0xFF030D14))
                )
            )
            .border(0.9.dp, badgeBorderColor, CutCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = when {
                    volState.isMuted || volState.volumePct == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                    volState.volumePct < 40 -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = "Volume",
                tint = dynamicColor,
                modifier = Modifier.size(10.5.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = if (volState.isMuted) "MUTE" else "VOL ${volState.volumePct}%",
                color = Color.White,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

/**
 * Stylized Vertical Right-Edge Cyber Volume Slider Modal.
 * Features a static, full-height multi-stop fuzzy neon gradient that the volume bar grows THROUGH.
 * High-volume warning is stamped over top without displacing internal geometry.
 */
@Composable
fun MikuCyberVolumeHudOverlay(
    ctx: Context,
    modifier: Modifier = Modifier
) {
    // Self-init on first composition so the volume observer registers and the HiBy-off-by-default
    // state is enforced, without requiring a call from the launcher monolith's onCreate.
    LaunchedEffect(Unit) { MikuVolumeManager.init(ctx.applicationContext) }
    val volState by MikuVolumeManager.state.collectAsState()
    val isDanger = volState.volumePct >= 80
    val dynamicColor = sampleVolumeGradientColor(volState.volumePct, volState.isMuted)

    val infiniteTransition = rememberInfiniteTransition(label = "VolumeHudGlow")
    val hazardPulse by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HazardPulse"
    )

    val glowBrightness = (volState.volumePct / 100f).coerceIn(0.4f, 1.0f)

    AnimatedVisibility(
        visible = volState.isHudVisible,
        enter = fadeIn(tween(140)) + slideInHorizontally(tween(160)) { it },
        exit = fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { it },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .width(52.dp)
                .height(235.dp)
                .clip(CutCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        if (isDanger) listOf(Color(0xF5240810), Color(0xFA140205))
                        else listOf(Color(0xF008202D), Color(0xFA030D14))
                    )
                )
                .border(
                    (1.0f + glowBrightness * 0.8f).dp,
                    Brush.verticalGradient(
                        if (isDanger) listOf(Color(0xFFFF1744), Color(0xFFFF007F), Color(0xFFFF5252))
                        else listOf(dynamicColor, MikuCyan, Color(0xFF7C4DFF))
                    ),
                    CutCornerShape(12.dp)
                )
                .padding(vertical = 7.dp, horizontal = 4.dp)
        ) {
            // FIXED INTERNAL GEOMETRY COLUMN
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top: Plus Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (isDanger) Color(0x44FF1744) else dynamicColor.copy(alpha = 0.25f))
                        .clickable { MikuVolumeManager.triggerHud(ctx, +1) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Vol Up",
                        tint = if (isDanger) Color(0xFFFF1744) else dynamicColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Center: Vertical Sliding Volume Core Gauge (Grows THROUGH static fuzzy gradient)
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0x5504141E))
                        .border(
                            0.9.dp,
                            if (isDanger) Color(0xFFFF1744).copy(alpha = hazardPulse)
                            else dynamicColor.copy(alpha = glowBrightness),
                            RoundedCornerShape(11.dp)
                        )
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, _ ->
                                change.consume()
                                val y = change.position.y
                                val h = size.height.toFloat()
                                val pct = ((1f - (y / h)) * 100f).toInt().coerceIn(0, 100)
                                MikuVolumeManager.setVolume(ctx, pct)
                            }
                        }
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val frac = (volState.volumePct / 100f).coerceIn(0f, 1f)

                        // 1. Static full-height fuzzy gradient brush (anchored across entire track 0..h)
                        val staticBrush = if (volState.isMuted) {
                            Brush.verticalGradient(
                                listOf(Color(0xFFFF5252), Color(0xFFB71C1C)),
                                startY = 0f,
                                endY = h
                            )
                        } else {
                            Brush.verticalGradient(
                                0.00f to Color(0xFFFF1744), // 100% Danger Red
                                0.12f to Color(0xFFFF5252), // 88% Hazard Coral
                                0.25f to Color(0xFFFF3399), // 75% Neon Pink
                                0.50f to Color(0xFF9D4EDD), // 50% Electric Lavender
                                0.75f to Color(0xFF00F5D4), // 25% Mint Cyan
                                1.00f to Color(0xFF00E5FF), // 0% Miku Bright Teal
                                startY = 0f,
                                endY = h
                            )
                        }

                        // 2. Filled bar grows from bottom through the static gradient
                        val fillHeight = h * frac
                        val topY = h - fillHeight
                        if (fillHeight > 0f) {
                            drawRoundRect(
                                brush = staticBrush,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, topY),
                                size = androidx.compose.ui.geometry.Size(w, fillHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx(), 11.dp.toPx())
                            )

                            // 3. Fuzzy glowing crest line at the exact top tip of the bar
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(dynamicColor, dynamicColor.copy(alpha = 0.4f), Color.Transparent),
                                    radius = w * 1.8f,
                                    center = androidx.compose.ui.geometry.Offset(w / 2f, topY)
                                ),
                                radius = w * 1.8f,
                                center = androidx.compose.ui.geometry.Offset(w / 2f, topY)
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.95f),
                                start = androidx.compose.ui.geometry.Offset(2.dp.toPx(), topY + 1.dp.toPx()),
                                end = androidx.compose.ui.geometry.Offset(w - 2.dp.toPx(), topY + 1.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }
                }

                // Volume % Text (Animated Danger Color)
                Text(
                    text = if (volState.isMuted) "MUTE" else "${volState.volumePct}%",
                    color = if (isDanger) Color(0xFFFF5252) else Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    textAlign = TextAlign.Center
                )

                // Bottom: Volume Minus Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(dynamicColor.copy(alpha = 0.25f))
                        .clickable { MikuVolumeManager.triggerHud(ctx, -1) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Vol Down",
                        tint = dynamicColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Mute Icon Toggle
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (volState.isMuted) Color(0x66FF1744) else dynamicColor.copy(alpha = 0.2f))
                        .clickable { MikuVolumeManager.toggleMute(ctx) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (volState.isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Mute",
                        tint = if (volState.isMuted) Color(0xFFFF5252) else dynamicColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // High Volume Hazard Warning - Stamped over top without shifting internal geometry
            if (isDanger) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-5).dp)
                        .clip(CutCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFF1744), Color(0xFFFF007F))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.95f), CutCornerShape(4.dp))
                        .padding(horizontal = 4.5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "⚠️ >80dB",
                        color = Color.White,
                        fontSize = 6.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }
    }
}
