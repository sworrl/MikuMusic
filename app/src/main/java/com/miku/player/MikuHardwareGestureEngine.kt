package com.miku.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Miku Hardware Button Press Gesture Engine & Textured Tactile Feedback.
 *
 * Supported Gestures:
 * 1. [HOLD POWER + DOUBLE-PRESS NEXT (or PLAY)] -> Like / Toggle Favorite for the currently playing song!
 * 2. [HOLD POWER + DOUBLE-PRESS PREV] -> Dislike / Remove from Favorites.
 * 3. [HOLD PLAY + DOUBLE-PRESS NEXT] -> Jump to Next Album / Playlist.
 *
 * Tactile Feedback:
 * - Emits a custom textured "bumpy but good" micro-waveform vibration feeling like mechanical
 *   gear teeth / textured notches clicking smoothly under fingers.
 */
object MikuHardwareGestureEngine {
    private const val TAG = "HardwareGestureEngine"

    // Time window for double-press detection (ms)
    private const val DOUBLE_PRESS_WINDOW_MS = 480L
    // Maximum time since Power press to consider Power "Held / Chorded" (ms)
    private const val POWER_CHORD_WINDOW_MS = 1400L

    private var isPowerDown = false
    private var powerDownTimestamp = 0L

    private var isPlayDown = false
    private var playDownTimestamp = 0L

    private var lastNextPressTimestamp = 0L
    private var nextPressCount = 0

    private var lastPlayPressTimestamp = 0L
    private var playPressCount = 0

    private var lastPrevPressTimestamp = 0L
    private var prevPressCount = 0

    // Heart Toast State
    data class HeartToastState(
        val visible: Boolean = false,
        val isLiked: Boolean = true,
        val trackTitle: String = "",
        val trackArtist: String = ""
    )

    private val _heartToast = MutableStateFlow(HeartToastState())
    val heartToast: StateFlow<HeartToastState> = _heartToast.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideToastRunnable: Runnable? = null

    /**
     * Intercepts KeyDown events for chorded gestures.
     * Returns TRUE if the gesture was consumed and standard action should be aborted.
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?, context: Context): Boolean {
        val now = SystemClock.uptimeMillis()

        when (keyCode) {
            KeyEvent.KEYCODE_POWER -> {
                isPowerDown = true
                powerDownTimestamp = now
                return false // Don't block initial power press unless chord succeeds
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                isPlayDown = true
                playDownTimestamp = now

                // Check for Power + Double-Press Play chord
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                if (isPowerChorded) {
                    if (now - lastPlayPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        playPressCount++
                    } else {
                        playPressCount = 1
                    }
                    lastPlayPressTimestamp = now

                    if (playPressCount >= 2) {
                        playPressCount = 0
                        triggerLikeGesture(context)
                        return true // Consumed! Prevents pause/play toggle
                    }
                }
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                val isPlayChorded = isPlayDown || (now - playDownTimestamp < POWER_CHORD_WINDOW_MS)

                if (isPowerChorded) {
                    if (now - lastNextPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        nextPressCount++
                    } else {
                        nextPressCount = 1
                    }
                    lastNextPressTimestamp = now

                    if (nextPressCount >= 2) {
                        nextPressCount = 0
                        triggerLikeGesture(context)
                        return true // Consumed! Prevents track skipping
                    }
                } else if (isPlayChorded) {
                    // Hold Play + Double Next = Next Album
                    if (now - lastNextPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        nextPressCount++
                    } else {
                        nextPressCount = 1
                    }
                    lastNextPressTimestamp = now

                    if (nextPressCount >= 2) {
                        nextPressCount = 0
                        triggerNextAlbumGesture(context)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                if (isPowerChorded) {
                    if (now - lastPrevPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        prevPressCount++
                    } else {
                        prevPressCount = 1
                    }
                    lastPrevPressTimestamp = now

                    if (prevPressCount >= 2) {
                        prevPressCount = 0
                        triggerDislikeGesture(context)
                        return true
                    }
                }
            }
        }

        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_POWER -> isPowerDown = false
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY -> isPlayDown = false
        }
        return false
    }

    /**
     * Executes the Like / Favorite toggle on the currently playing song with textured haptics.
     */
    private fun triggerLikeGesture(context: Context) {
        val player = PlayerHolder.ensure(context)
        val currentItem = player.currentMediaItem
        val mediaId = currentItem?.mediaId?.toLongOrNull()
        val title = currentItem?.mediaMetadata?.title?.toString()
            ?: try { android.provider.Settings.Global.getString(context.contentResolver, "miku_now_playing_title") ?: "Current Track" } catch (_: Throwable) { "Current Track" }
        val artist = currentItem?.mediaMetadata?.artist?.toString()
            ?: try { android.provider.Settings.Global.getString(context.contentResolver, "miku_now_playing_artist") ?: "Hatsune Miku" } catch (_: Throwable) { "Hatsune Miku" }

        val isNowLiked = if (mediaId != null) {
            LikeStore.toggle(context, mediaId)
        } else {
            // If mediaId is not a raw numeric ID, toggle by artist/title
            LikeStore.toggleArtist(context, artist)
        }

        // Bumpy Tactile Feedback
        playBumpyLikeTexture(context)

        // Pulsar LED Flash
        if (isNowLiked) {
            PulsarLight.indicateHearted(context)
        }

        // Display Floating HUD
        showHeartToast(isLiked = isNowLiked, title = title, artist = artist)
        Log.i(TAG, "Hardware Gesture Triggered: LIKED=$isNowLiked for '$title' by '$artist'")
    }

    /**
     * Executes Dislike / Remove from Favorites gesture.
     */
    private fun triggerDislikeGesture(context: Context) {
        val player = PlayerHolder.ensure(context)
        val currentItem = player.currentMediaItem
        val mediaId = currentItem?.mediaId?.toLongOrNull()
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Current Track"
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "Hatsune Miku"

        if (mediaId != null && LikeStore.isLiked(mediaId)) {
            LikeStore.toggle(context, mediaId)
        }

        playBumpyDislikeTexture(context)
        showHeartToast(isLiked = false, title = title, artist = artist)
    }

    private fun triggerNextAlbumGesture(context: Context) {
        playRatchetTick(context)
        // Seek to next track
        val player = PlayerHolder.ensure(context)
        player.seekToNextMediaItem()
    }

    /**
     * Creates a rich, physical "bumpy but good" textured vibration.
     * Simulates micro-notches with tactile amplitude crests.
     */
    fun playBumpyLikeTexture(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Waveform: 7 micro-ridges that ramp up and down in amplitude with 12ms gaps
                val timings = longArrayOf(0, 12, 14, 16, 12, 22, 14, 28, 14, 20, 12, 14, 10, 8)
                val amplitudes = intArrayOf(0, 90, 0, 150, 0, 210, 0, 255, 0, 180, 0, 120, 0, 60)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } catch (_: Throwable) {
                vib.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 15, 20, 25, 20, 35), -1)
        }
    }

    /**
     * Downward rumble texture for unliking.
     */
    fun playBumpyDislikeTexture(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 24, 16, 18, 14, 12, 10, 8)
                val amplitudes = intArrayOf(0, 240, 0, 170, 0, 100, 0, 40)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Crisp mechanical ratchet tick for gear gestures.
     */
    fun playRatchetTick(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 8, 10, 8, 10, 12)
                val amplitudes = intArrayOf(0, 180, 0, 220, 0, 255)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }
            } catch (_: Throwable) {}
        }
    }

    private fun showHeartToast(isLiked: Boolean, title: String, artist: String) {
        _heartToast.value = HeartToastState(
            visible = true,
            isLiked = isLiked,
            trackTitle = title,
            trackArtist = artist
        )
        hideToastRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { _heartToast.value = _heartToast.value.copy(visible = false) }
        hideToastRunnable = r
        mainHandler.postDelayed(r, 2200L)
    }
}

/**
 * Animated Cyber Heart Floating Toast Overlay.
 */
@Composable
fun MikuHeartGestureHudOverlay(modifier: Modifier = Modifier) {
    val toastState by MikuHardwareGestureEngine.heartToast.collectAsState()

    val scaleAnim = remember { Animatable(0.8f) }
    LaunchedEffect(toastState.visible) {
        if (toastState.visible) {
            scaleAnim.snapTo(0.75f)
            scaleAnim.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    AnimatedVisibility(
        visible = toastState.visible,
        enter = fadeIn(tween(140)) + scaleIn(tween(160, easing = LinearOutSlowInEasing), initialScale = 0.8f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220, easing = FastOutLinearInEasing), targetScale = 0.85f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 90.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .scale(scaleAnim.value)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            if (toastState.isLiked) listOf(Color(0xF02A0015), Color(0xF5100008))
                            else listOf(Color(0xE6081A24), Color(0xF2030D13))
                        )
                    )
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(
                            if (toastState.isLiked) listOf(Color(0xFFFF2277), Color(0xFFFF88AA))
                            else listOf(Color(0xFF39C5BB), Color(0x66FFFFFF))
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (toastState.isLiked) Color(0x33FF2277) else Color(0x2239C5BB)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (toastState.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (toastState.isLiked) Color(0xFFFF2277) else Color(0xFF39C5BB),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.widthIn(max = 240.dp)) {
                        Text(
                            text = if (toastState.isLiked) "♥ ADDED TO FAVORITES" else "REMOVED FROM FAVORITES",
                            color = if (toastState.isLiked) Color(0xFFFF5599) else Color(0xFF9FF3EC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = toastState.trackTitle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = toastState.trackArtist,
                            color = Color(0xFFB0D0D8),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
