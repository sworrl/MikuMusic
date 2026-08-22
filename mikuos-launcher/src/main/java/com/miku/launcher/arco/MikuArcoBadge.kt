package com.miku.launcher.arco

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.CyberBespokeBadge

/**
 * Top-bar badge for the arcobocconotto RGB fleet, styled to match the other
 * launcher top-bar badges (MikuThermalBadge / MikuBpmEngineBadge / etc — all
 * built on [CyberBespokeBadge] in MikuLauncherActivity.kt). Read-only display;
 * [onClick] is where the host wires up opening [MikuArcoModal].
 *
 * Self-sufficient: calls [ArcoClient.init] defensively so it works whether or
 * not anything else in the app touched the client first.
 */
@Composable
fun MikuArcoBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ArcoClient.init(ctx) }

    val connectionState by ArcoClient.connectionState.collectAsState()
    val activeEffect by ArcoClient.activeEffect.collectAsState()
    val themes by ArcoClient.themes.collectAsState()

    val isConnected = connectionState == ArcoConnectionState.CONNECTED
    val isBusy = connectionState == ArcoConnectionState.CONNECTING

    // Tint the badge with the active theme's first palette color when known,
    // so the RGB badge visually echoes what the rig is actually doing.
    val themeAccent = themes.firstOrNull { it.id == activeEffect }
        ?.palette_hex?.firstOrNull()
        ?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() }

    val accentColor = when (connectionState) {
        ArcoConnectionState.CONNECTED -> if (activeEffect == "off") Color(0xFF8BA6A9) else (themeAccent ?: com.miku.launcher.MikuNeonPink)
        ArcoConnectionState.CONNECTING -> Color(0xFFFFD600)
        ArcoConnectionState.ERROR -> Color(0xFFFF1744)
        ArcoConnectionState.DISCONNECTED -> Color(0xFFFF9100)
        ArcoConnectionState.UNPAIRED -> Color(0xFF8BA6A9)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ArcoBadgePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ArcoBadgePulseAlpha"
    )

    val displayText = when (connectionState) {
        ArcoConnectionState.CONNECTED -> if (activeEffect.isBlank() || activeEffect == "off") "OFF" else activeEffect.take(9).uppercase()
        ArcoConnectionState.CONNECTING -> "SYNC"
        ArcoConnectionState.ERROR -> "ERR"
        ArcoConnectionState.DISCONNECTED -> "LINK"
        ArcoConnectionState.UNPAIRED -> "RGB"
    }

    Box(modifier = modifier) {
        CyberBespokeBadge(
            onClick = onClick,
            accentColor = accentColor,
            gradient = listOf(accentColor.copy(alpha = if (isConnected) 0.32f else 0.16f), Color(0xFF030D14)),
            shape = CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp, topEnd = 3.dp, bottomStart = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "💡",
                    fontSize = 7.5.sp,
                    color = accentColor.copy(alpha = if (isBusy) pulseAlpha else 1f),
                    modifier = Modifier.padding(end = 1.5.dp)
                )
                Text(
                    text = displayText,
                    color = if (isConnected) Color.White else accentColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1
                )
            }
        }
    }
}
