package com.miku.launcher.ingest

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.CyberBespokeBadge
import com.miku.launcher.CyberGlassBorder
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink

/**
 * Hatsune Miku Ingestion & File System Telemetry Badge for Top-Bar Quilt.
 * Displays real-time library ingestion state, track counter (e.g. 11k), format tiers (DSD/FLAC),
 * and live pulsing animation during active MediaScanner / Rsync runs.
 */
@Composable
fun MikuIngestionBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MikuIngestEngine.init(ctx) }

    val ingestState by MikuIngestEngine.state.collectAsState()

    val infinitePulse = rememberInfiniteTransition(label = "IngestPulse")
    val pulseAlpha by infinitePulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IngestPulseAlpha"
    )

    val isScanning = ingestState.isScanning
    val accentColor = if (isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88) // Cyber Emerald / Gold

    val badgeShape = remember { CutCornerShape(4.dp) }

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(badgeShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isScanning) 0.22f else 0.12f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.6f)
                    )
                )
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(0.8.dp)
                .clip(badgeShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isScanning) Color(0x33FFD54F) else Color(0x2800FF88),
                            Color(0xFF031410)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            accentColor.copy(alpha = if (isScanning) pulseAlpha else 0.85f),
                            CyberGlassBorder.copy(alpha = 0.35f),
                            accentColor.copy(alpha = 0.7f)
                        )
                    ),
                    badgeShape
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Live LED indicator dot
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = if (isScanning) pulseAlpha else 1f))
                )
                Spacer(Modifier.width(4.dp))

                // Ingestion Text
                val labelText = if (isScanning) {
                    "INGEST ${(ingestState.scanProgress * 100).toInt()}%"
                } else {
                    "FS ${ingestState.abbreviatedTracks}"
                }

                Text(
                    text = labelText,
                    color = if (isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}
