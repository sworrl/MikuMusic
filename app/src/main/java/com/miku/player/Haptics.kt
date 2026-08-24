package com.miku.player

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.ui.composed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A little cassette glyph (shell, two reels, head mouth) — the app has no CD anywhere. */
@Composable
fun TapeIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = w * 0.07f
        drawRoundRect(
            tint, topLeft = Offset(w * 0.04f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(stroke)
        )
        drawCircle(tint, w * 0.115f, Offset(w * 0.335f, h * 0.50f), style = Stroke(stroke * 0.85f))
        drawCircle(tint, w * 0.115f, Offset(w * 0.665f, h * 0.50f), style = Stroke(stroke * 0.85f))
        drawLine(tint, Offset(w * 0.45f, h * 0.50f), Offset(w * 0.55f, h * 0.50f), stroke * 0.85f)
        drawLine(tint, Offset(w * 0.30f, h * 0.80f), Offset(w * 0.36f, h * 0.68f), stroke * 0.8f)
        drawLine(tint, Offset(w * 0.36f, h * 0.68f), Offset(w * 0.64f, h * 0.68f), stroke * 0.8f)
        drawLine(tint, Offset(w * 0.64f, h * 0.68f), Offset(w * 0.70f, h * 0.80f), stroke * 0.8f)
    }
}

/** App-wide crisp haptic tick — used for physical media keys and programmatic feedback. */
object Haptics {
    @Volatile private var vib: Vibrator? = null
    private fun vib(ctx: Context): Vibrator? {
        if (vib == null) {
            // Use applicationContext: the returned Vibrator retains the Context, and this object
            // is process-lifetime — caching one built from an Activity would leak it.
            val app = ctx.applicationContext
            vib = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            }
        }
        return vib
    }

    fun tick(ctx: Context, ms: Long = 16) {
        try {
            val v = vib(ctx) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Throwable) {}
    }
}

/**
 * Play/pause glyph with a springy pop-morph between states — every transport key already
 * animates its own press (see [HapticIconButton]), so an instant icon swap inside it reads as
 * a render glitch next to that. One definition, used by the mini bar and full Now Playing.
 */
@Composable
fun PlayPauseGlyph(isPlaying: Boolean, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) + fadeIn(tween(90)))
                .togetherWith(scaleOut(targetScale = 0.55f, animationSpec = tween(90)) + fadeOut(tween(70)))
        },
        label = "playGlyph"
    ) { playing ->
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            "Play/Pause", tint = tint, modifier = Modifier.size(size)
        )
    }
}

/**
 * IconButton replacement with three channels of feedback on every press:
 *  - haptic: a Compose long-press cue plus a hardware vibrator tick
 *  - visual: a springy scale-down while held
 *  - physical: a 3D-embossed key face — raised (lit top, drop shadow below) at rest, and
 *    inverted to a pressed-in well while held, like a real hardware button
 * Drop-in for the app's `IconButton(onClick = …) { … }` call sites.
 */
@Composable
fun HapticIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    face: Color? = null,   // give the key a colored face (e.g. MikuTeal play button); null = dark key
    flat: Boolean = false, // true = haptic/scale feedback only, no embossed key face — for slim
                            // glassy nav rows (top bars, list headers) where a hardware-key pill
                            // reads as a stray dark blob rather than a control
    keyShape: Shape? = null, // override the default stadium/circle key silhouette with an arbitrary
                              // Shape (same raised/pressed bevel language, just a different outline)
                              // — for clusters like ControlAssembly where each button needs its own
                              // molded silhouette to read as "merged but distinct", not a row of
                              // identical circles in a shared trough.
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(), label = "btnScale")
    // Key-face lighting: lit from the top at rest, inverted (sunk) while pressed.
    val faceTop = if (face != null) lerp(face, Color.White, 0.28f) else Color(0xFF17403F)
    val faceBot = if (face != null) lerp(face, Color.Black, 0.30f) else Color(0xFF082022)
    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Haptics.tick(ctx)
            onClick()
        },
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (flat) return@drawBehind
                if (keyShape != null) {
                    val outline = keyShape.createOutline(size, layoutDir, density)
                    val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
                    if (!pressed) {
                        translate(top = 2.2f) { drawPath(path, Color(0x59000000)) }
                        drawPath(path, Brush.verticalGradient(listOf(faceTop, faceBot)))
                        drawPath(path, Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.6f), style = Stroke(1.4f))
                    } else {
                        drawPath(path, Brush.verticalGradient(listOf(faceBot, faceTop)))
                        drawPath(path, Color(0x73000000), style = Stroke(2.4f))
                    }
                    return@drawBehind
                }
                // Stadium-shaped key: a circle on square buttons, a wide pill on oblong ones.
                val pad = 2.dp.toPx()
                if (size.minDimension <= pad * 2) return@drawBehind
                val tl = Offset(pad, pad)
                val sz = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad)
                val rr = androidx.compose.ui.geometry.CornerRadius(sz.minDimension / 2f, sz.minDimension / 2f)
                if (!pressed) {
                    // raised key: soft drop shadow under, face lit from the top, specular rim
                    drawRoundRect(Color(0x59000000), topLeft = Offset(pad, pad + 2.2f), size = sz, cornerRadius = rr)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(faceTop, faceBot), startY = pad, endY = pad + sz.height),
                        topLeft = tl, size = sz, cornerRadius = rr)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)), startY = pad, endY = pad + sz.height * 0.6f),
                        topLeft = tl, size = sz, cornerRadius = rr, style = Stroke(1.4f))
                } else {
                    // pressed in: inverted lighting + inner occlusion ring, like a sunk key
                    drawRoundRect(
                        Brush.verticalGradient(listOf(faceBot, faceTop), startY = pad, endY = pad + sz.height),
                        topLeft = tl, size = sz, cornerRadius = rr)
                    drawRoundRect(Color(0x73000000), topLeft = Offset(pad + 0.8f, pad + 0.8f),
                        size = androidx.compose.ui.geometry.Size(sz.width - 1.6f, sz.height - 1.6f),
                        cornerRadius = rr, style = Stroke(2.4f))
                }
            },
        interactionSource = interaction,
        content = content
    )
}

/**
 * Universal tactile press & release spring bounce modifier.
 * Applies a smooth hardware-feel spring compression (0.92f) when pressed,
 * bounces back smoothly on release, and triggers haptic tick.
 */
fun Modifier.mikuTactile(
    hapticTick: Boolean = true,
    pressedScale: Float = 0.92f,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 1200f),
        label = "mikuTactileScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null
                ) {
                    if (hapticTick) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Haptics.tick(ctx)
                    }
                    onClick()
                }
            } else Modifier
        )
}

fun Modifier.mikuBounce(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.93f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 1200f),
        label = "mikuBounceScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

