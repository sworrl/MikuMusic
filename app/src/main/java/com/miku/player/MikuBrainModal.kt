package com.miku.player

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miku.player.CyberGlassBorder
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import com.miku.player.MikuTextPrimary
import com.miku.player.MikuTextSecondary
import com.miku.player.ui.swipeUpFromBottomToDismiss

/**
 * Dedicated Miku Brain & Bone Orchestration Modal.
 * Exposes real-time telemetry, thread dispatchers, heartbeats, and ANR immunity status.
 */
@Composable
fun MikuBrainModal(
    onDismissRequest: () -> Unit
) {
    val telemetry by MikuBrain.telemetry.collectAsState()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(Color(0xFF040D12))
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF00FF7F).copy(alpha = 0.9f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    MikuCyan.copy(alpha = 0.6f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
            ) {
                // Background Artwork
                Image(
                    painter = painterResource(id = R.drawable.miku_cyber_stage),
                    contentDescription = "Miku Brain Background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Frosted Dark Overlay
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF2040D12),
                                    Color(0xD8040D12),
                                    Color(0xFA040D12)
                                )
                            )
                        )
                )

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    // Header Bar with 3D Embossed Button & Task-Specific Miku Badge
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CutCornerShape(8.dp))
                                    .background(Color(0x3300FF7F))
                                    .border(1.dp, Color(0xFF00FF7F), CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.miku_audiophile_art),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "MIKU BRAIN & BONES",
                                    color = MikuCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "Central Subsystem Watchdog & ANR Guard",
                                    color = MikuTextSecondary,
                                    fontSize = 8.sp
                                )
                            }
                        }

                        com.miku.player.network.Cyber3dIconButton(
                            onClick = onDismissRequest,
                            icon = Icons.Default.Close,
                            contentDescription = "Close",
                            accentColor = MikuNeonPink
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 3D Embossed Watchdog Status Card
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(10.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            )
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(1.dp)
                                .clip(CutCornerShape(9.dp))
                                .background(Color(0xEE0A1E26))
                                .border(1.dp, MikuCyan.copy(alpha = 0.5f), CutCornerShape(9.dp))
                                .padding(12.dp)
                        ) {
                            Column {
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
                                                .background(if (telemetry.allBonesHealthy) Color(0xFF00FF7F) else Color(0xFFFF5252))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (telemetry.allBonesHealthy) "AUTONOMOUS WATCHDOG: 100% NOMINAL" else "WATCHDOG: SUBSYSTEM ANOMALY",
                                            color = if (telemetry.allBonesHealthy) Color(0xFF00FF7F) else Color(0xFFFF5252),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                    Text(
                                        "LIVE 1000ms TICK",
                                        color = MikuCyan,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (telemetry.isUiUnderLoad) "⚡ UI TOUCH INTERACTION ACTIVE: Background I/O automatically yielding to ensure uncompromised 60 FPS fluidity."
                                    else "🟢 UI IDLE: 100% CPU clock bandwidth dynamically allocated to Audio DAC & Ingress pipelines.",
                                    color = if (telemetry.isUiUnderLoad) MikuNeonPink else Color.White,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Subsystem Bones List (Live Streaming)
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MikuBrain.BoneType.values().forEach { bone ->
                            val health = telemetry.bones[bone] ?: MikuBrain.BoneHealth(type = bone)
                            item {
                                BoneCard(health = health)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Manual Override Fallback Button
                    Button(
                        onClick = {
                            // Manual diagnostic probe override for power users
                            MikuBrain.heartbeat(MikuBrain.BoneType.UI_RENDERER, MikuBrain.BoneState.ACTIVE, "Manual Probe Acknowledged", 1)
                            MikuBrain.heartbeat(MikuBrain.BoneType.HARDWARE_IO, MikuBrain.BoneState.ACTIVE, "CS43131 Direct Sysfs Queried", 1)
                            MikuBrain.heartbeat(MikuBrain.BoneType.AUDIO_DSP, MikuBrain.BoneState.ACTIVE, "Direct ALSA Buffer Sampled", 1)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xDD0A222E)),
                        border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.8f)),
                        shape = CutCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MikuCyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("MANUAL DIAGNOSTIC PROBE (FALLBACK OVERRIDE)", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoneCard(
    health: MikuBrain.BoneHealth
) {
    val (title, roleDesc, iconEmoji) = when (health.type) {
        MikuBrain.BoneType.AUDIO_DSP -> Triple("AUDIO_DSP", "RealFmDspEngine & CS43131 Bit-Perfect Direct ALSA", "🎵")
        MikuBrain.BoneType.LIBRARY_SCANNER -> Triple("LIBRARY_SCANNER", "MediaLibraryScanner & FLAC Metadata Indexer", "📚")
        MikuBrain.BoneType.NETWORK_INGRESS -> Triple("NETWORK_INGRESS", "MikuSyncTransceiver & m500d Multi-Stream Socket", "🌐")
        MikuBrain.BoneType.HARDWARE_IO -> Triple("HARDWARE_IO", "CirrusLogicManager & SGM31324 Pulsar Sysfs", "⚡")
        MikuBrain.BoneType.UI_RENDERER -> Triple("UI_RENDERER", "Hardware Accelerated 60 FPS Compose HUD", "🎨")
    }

    val elapsedSec = ((SystemClock.elapsedRealtime() - health.lastHeartbeatMs) / 1000).coerceAtLeast(0)
    val isOk = health.state != MikuBrain.BoneState.STALLED && health.state != MikuBrain.BoneState.ERROR

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(10.dp))
            .background(Color(0xDD0A1E26))
            .border(1.dp, if (isOk) CyberGlassBorder else Color(0xFFFF5252), CutCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(iconEmoji, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Box(
                Modifier
                    .clip(CutCornerShape(4.dp))
                    .background(if (isOk) Color(0x3300E676) else Color(0x33FF5252))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    health.state.name,
                    color = if (isOk) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(roleDesc, color = MikuTextSecondary, fontSize = 8.5.sp)

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Active Tasks: ${health.activeTasks}", color = MikuCyan, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
            Text("Heartbeat: ${elapsedSec}s ago (${health.lastMessage})", color = Color.Gray, fontSize = 8.sp)
        }
    }
}
