package com.miku.launcher.ingest

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.*
import com.miku.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch

private val MikuMuted = Color(0xFF9EC4C7)

/**
 * Hatsune Miku File System & Audio Ingestion Observatory Modal.
 * High-legibility scaled dashboard for compact Hi-DPI DAP screens.
 * Displays storage volumes, codec taxonomy, live MediaScanner rescan,
 * rsync ingestion server telemetry, and real-time indexing logs.
 */
@Composable
fun MikuFsIngestObservatoryModal(
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { MikuIngestEngine.init(ctx) }

    val ingestState by MikuIngestEngine.state.collectAsState()

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD030A0E))
            .clickable { onClose() }
            .swipeUpFromBottomToDismiss { onClose() }
            .padding(top = 18.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF207171C),
                            Color(0xFA030C10)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF00FF88).copy(alpha = 0.85f),
                            CyberGlassBorder.copy(alpha = 0.45f),
                            MikuCyan.copy(alpha = 0.8f)
                        )
                    ),
                    RoundedCornerShape(18.dp)
                )
                .clickable(enabled = false) {}
                .padding(14.dp)
        ) {
            Column(Modifier.fillMaxSize()) {

                // =========================================================================
                // 1. MODAL HEADER: Title, Subtitle, Close Button
                // =========================================================================
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x3300FF88))
                                .border(1.2.dp, Color(0xFF00FF88), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💽", fontSize = 22.sp)
                        }

                        Spacer(Modifier.width(12.dp))

                        Column {
                            Text(
                                "FS & INGESTION OBSERVATORY",
                                color = Color.White,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.4.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                "Storage Volumes · Codec Taxonomy · MediaScanner",
                                color = Color(0xFF00FF88),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x28FFFFFF))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // =========================================================================
                // 2. SCROLLABLE TELEMETRY CARDS (High-Legibility Scaled Typography)
                // =========================================================================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Card 1: Ingest Status & Live Rescan Actions ─────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF071B20))
                                .border(1.2.dp, Color(0xFF00FF88).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(if (ingestState.isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when {
                                            ingestState.isScanning -> "ACTIVE MEDIA SCAN"
                                            !ingestState.engineEnabled -> "ENGINE OFF · LOCAL SD ONLY"
                                            else -> "INDEXED & BIT-PERFECT"
                                        },
                                        color = when {
                                            ingestState.isScanning -> Color(0xFFFFD54F)
                                            !ingestState.engineEnabled -> MikuMuted
                                            else -> Color(0xFF00FF88)
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 0.3.sp
                                    )
                                }

                                Text(
                                    "${ingestState.totalTracks} Tracks",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                ingestState.statusMessage,
                                color = MikuMuted,
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp
                            )

                            if (ingestState.isScanning) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { ingestState.scanProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF00FF88),
                                    trackColor = Color(0x3300FF88)
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // Ingest engine master switch — mirrors the INGRESS ENGINE shade tile.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "INGRESS ENGINE (RSYNC)",
                                        color = if (ingestState.engineEnabled) Color(0xFF00FF88) else MikuMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Text(
                                        if (ingestState.engineEnabled) "Network ingest armed · auto-resumes when the server is reachable"
                                        else "Off · only local SD card scan updates run",
                                        color = MikuMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = ingestState.engineEnabled,
                                    onCheckedChange = { MikuIngestEngine.setEngineEnabled(ctx, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF031410),
                                        checkedTrackColor = Color(0xFF00FF88),
                                        uncheckedThumbColor = MikuMuted,
                                        uncheckedTrackColor = Color(0xFF0B2228)
                                    )
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // Action Buttons (Scaled for Easy Tapping)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { MikuIngestEngine.triggerForceScan(ctx) },
                                    enabled = !ingestState.isScanning,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF030D10), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("FORCE SCAN", color = Color(0xFF030D10), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Button(
                                    onClick = { MikuIngestEngine.triggerRsyncSync(ctx) },
                                    enabled = !ingestState.isScanning && ingestState.engineEnabled,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Rsync Ingest", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // ── Card 2: Storage Volumes & File System Hierarchy ────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF07141A))
                                .border(1.2.dp, MikuCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                "STORAGE VOLUMES & PARTITIONS",
                                color = MikuCyan,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(Modifier.height(12.dp))

                            // 1. Internal Flash Storage
                            val intTotal = ingestState.internalTotalBytes
                            val intUsed = ingestState.internalUsedBytes
                            val intPct = if (intTotal > 0) ((intUsed.toFloat() / intTotal) * 100).toInt() else 0

                            VolumeProgressRow(
                                title = "Internal Flash Memory",
                                path = "/storage/emulated/0",
                                usedStr = MikuIngestEngine.formatBytes(intUsed),
                                totalStr = MikuIngestEngine.formatBytes(intTotal),
                                pct = intPct,
                                barColor = MikuCyan
                            )

                            Spacer(Modifier.height(14.dp))

                            // 2. MicroSD Removable Card
                            if (ingestState.isSdCardMounted) {
                                val sdTotal = ingestState.sdCardTotalBytes
                                val sdUsed = ingestState.sdCardUsedBytes
                                val sdPct = if (sdTotal > 0) ((sdUsed.toFloat() / sdTotal) * 100).toInt() else 0

                                VolumeProgressRow(
                                    title = "MicroSD Removable Card",
                                    path = ingestState.sdCardPath,
                                    usedStr = MikuIngestEngine.formatBytes(sdUsed),
                                    totalStr = MikuIngestEngine.formatBytes(sdTotal),
                                    pct = sdPct,
                                    barColor = Color(0xFFFF4081)
                                )
                            } else {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF0E1A20))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.SdCard, contentDescription = null, tint = MikuMuted, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("MicroSD Card Slot", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text("No external TF/MicroSD card mounted", color = MikuMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── Card 3: Ingested Audio Codec & Taxonomy Breakdown ──────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF07141A))
                                .border(1.2.dp, Color(0xFFB388FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "AUDIO CODEC TAXONOMY",
                                    color = Color(0xFFB388FF),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.3.sp
                                )
                                Text(
                                    "${ingestState.hiResPercent}% Hi-Res Lossless",
                                    color = Color(0xFF00FF88),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // 2x2 Grid of Codec Counts (Scaled Up)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "DSD",
                                    count = ingestState.dsdCount,
                                    desc = "Direct Stream 1-bit",
                                    color = Color(0xFFFFD54F)
                                )
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "FLAC",
                                    count = ingestState.flacCount,
                                    desc = "24-bit / 192k Master",
                                    color = Color(0xFF00E5FF)
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "WAV / ALAC",
                                    count = ingestState.wavCount + ingestState.alacCount,
                                    desc = "Lossless PCM Audio",
                                    color = Color(0xFFB388FF)
                                )
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "MP3 / AAC",
                                    count = ingestState.mp3Count + ingestState.aacCount,
                                    desc = "Compressed Standard",
                                    color = Color(0xFFFF80AB)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Artists: ${ingestState.totalArtists}", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("Total Albums: ${ingestState.totalAlbums}", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // ── Card 4: Ingestion Sync Server & Config ──────────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF07141A))
                                .border(1.2.dp, Color(0xFFFFD54F).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                "INGESTION SYNC SERVER CONFIG",
                                color = Color(0xFFFFD54F),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(Modifier.height(10.dp))

                            val syncHost = MikuIngestConfig.syncHost(ctx).ifBlank { "souffle (Auto-Active)" }
                            val rsyncPort = MikuIngestConfig.rsyncPort(ctx)

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Sync Daemon Endpoint", color = MikuMuted, fontSize = 12.5.sp)
                                Text("$syncHost:$rsyncPort", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Protocol Engine", color = MikuMuted, fontSize = 12.5.sp)
                                Text("rsync v3.2.7 (uid=0 direct)", color = Color(0xFF00FF88), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last Index Timestamp", color = MikuMuted, fontSize = 12.5.sp)
                                Text(ingestState.lastScanTime, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // ── Card 5: Real-Time Ingestion Console Log ─────────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF03090D))
                                .border(1.2.dp, Color(0x4400FF88), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("LIVE INGESTION LOG", color = Color(0xFF00FF88), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("Console Stream", color = MikuMuted, fontSize = 11.sp)
                            }

                            Spacer(Modifier.height(10.dp))

                            ingestState.logMessages.takeLast(8).forEach { line ->
                                Text(
                                    line,
                                    color = if (line.contains("Completed") || line.contains("Ready")) Color(0xFF00FF88) else Color(0xFFA6C5C8),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }

                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
fun VolumeProgressRow(
    title: String,
    path: String,
    usedStr: String,
    totalStr: String,
    pct: Int,
    barColor: Color
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(1.dp))
                Text(path, color = MikuMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("$usedStr / $totalStr", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(1.dp))
                Text("$pct% Used", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { (pct / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color(0x28FFFFFF)
        )
    }
}

@Composable
fun CodecMetricPill(
    modifier: Modifier = Modifier,
    badge: String,
    count: Int,
    desc: String,
    color: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.2.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(badge, color = color, fontSize = 13.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Text(count.toString(), color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }
            Spacer(Modifier.height(3.dp))
            Text(desc, color = MikuMuted, fontSize = 11.sp)
        }
    }
}
