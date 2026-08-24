package com.miku.launcher.ui

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink
import com.miku.launcher.haptics.MikuTactileHaptics

/**
 * Universal Tactile Cyberpunk Press & Release Interactive Modifier.
 *
 * Delivers:
 *  - Tactile physical spring squash (scale down to 0.88f) on touch down
 *  - Springy overshoot bounce back to 1.0f on release with medium damping
 *  - Luminescent neon cyber glow aura on press
 *  - Mechanical micro-tick haptic feedback
 */
fun Modifier.mikuPressScale(
    pressedScale: Float = 0.88f,
    glowColor: Color = MikuCyan,
    hapticFeedback: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val ctx = LocalContext.current
    val actualSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by actualSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "mikuPressScaleAnim"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 80 else 240),
        label = "mikuPressGlowAnim"
    )

    LaunchedEffect(isPressed) {
        if (isPressed && hapticFeedback) {
            MikuTactileHaptics.playRatchetTick(ctx)
        }
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (glowAlpha > 0.01f) {
                drawRoundRect(
                    color = glowColor.copy(alpha = glowAlpha * 0.45f),
                    size = size,
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
}

/**
 * Interactive App Icon Press Modifier with combined click + long-press,
 * spring bounce physics, and cyan/pink glowing feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.mikuAppIconClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    glowColor: Color = MikuCyan
): Modifier = composed {
    val ctx = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "mikuAppIconScale"
    )

    LaunchedEffect(isPressed) {
        if (isPressed) {
            MikuTactileHaptics.playRatchetTick(ctx)
        }
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}
