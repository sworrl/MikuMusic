package com.miku.player

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.CyberDarkBg
import com.miku.player.CyberGlassBorder
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MikuMonitorModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val syncState by MikuSyncTransceiver.state.collectAsState()
    val daemon = syncState.daemon

    var isScanningLocal by remember { mutableStateOf(false) }
    var isRunningSpeedTest by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Overview, 1: Workers, 2: Logs

    val serverTracks = daemon.serverAudioCount
    val cardTracks = daemon.cardAudioCount
    val missingTracks = (serverTracks - cardTracks).coerceAtLeast(0)
    val syncPercent = if (serverTracks > 0) ((cardTracks.toFloat() / serverTracks) * 100f).roundToInt() else 0

    val serverGB = daemon.serverAudioBytes / (1024L * 1024L * 1024L)
    val cardGB = daemon.cardAudioBytes / (1024L * 1024L * 1024L)
    val missingGB = (serverGB - cardGB).coerceAtLeast(0L)
    val sdFreeGB = daemon.sdCardFreeBytes / (1024L * 1024L * 1024L)
    val sdTotalGB = daemon.sdCardTotalBytes / (1024L * 1024L * 1024L)
    val internalFreeGB = daemon.internalFreeBytes / (1024L * 1024L * 1024L)
    val internalTotalGB = daemon.internalTotalBytes / (1024L * 1024L * 1024L)
    // Music library / ingestion target is the SD card (the large removable volume).
    val sdLabel = if (daemon.sdCardPath.isNotEmpty()) daemon.sdCardPath else "SD card"

    // Self-Diagnostic Health Evaluation
    val isDaemonOnline = daemon.online
    val isServerReachable = daemon.serverReachable
    val isSdSpaceAdequate = sdFreeGB > 20L
    val isHealthy = isDaemonOnline && isServerReachable && isSdSpaceAdequate

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
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
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xF8061B24),
                                Color(0xF8030F14)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    if (isHealthy) MikuCyan.copy(alpha = 0.8f) else MikuNeonPink,
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    MikuNeonPink.copy(alpha = 0.6f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(14.dp)
            ) {
                // ============================================================
                // MODAL HEADER & CLOSE WITH BESPOKE DJ MIKU AVATAR
                // ============================================================
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
                                .background(Color(0x3300E5FF))
                                .border(1.dp, MikuCyan, CutCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.miku_chibi_hearts),
                                contentDescription = "Miku DJ Monitor",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "MIKU MEDIA INGRESS MONITOR",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.8.sp
                        )
                    }

                    com.miku.player.network.Cyber3dIconButton(
                        onClick = onDismissRequest,
                        icon = Icons.Default.Close,
                        contentDescription = "Close",
                        accentColor = MikuNeonPink
                    )
                }

                Spacer(Modifier.height(8.dp))

            // ============================================================
            // POSITIVE HEALTH & TROUBLESHOOTING BANNER
            // ============================================================
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isHealthy) {
                            if (syncState.isTransferring) Color(0x3300E5FF) else Color(0x2200E676)
                        } else Color(0x33FF4081)
                    )
                    .border(
                        1.dp,
                        if (isHealthy) (if (syncState.isTransferring) MikuCyan else Color(0xFF00E676)) else MikuNeonPink,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isHealthy) {
                                    if (syncState.isTransferring) MikuCyan else Color(0xFF00E676)
                                } else MikuNeonPink
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when {
                                !isDaemonOnline -> "⚠️ PC DAEMON OFFLINE · NO LINK"
                                !isServerReachable -> "⚠️ SOUFFLE SERVER UNREACHABLE"
                                !isSdSpaceAdequate -> "⚠️ SD CARD STORAGE LOW (<20GB)"
                                syncState.isTransferring -> "⚡ INGRESS ACTIVE · 10 WORKERS RUNNING"
                                else -> "🟢 ALL SYSTEMS NOMINAL · 0 ISSUES"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = when {
                                !isDaemonOnline -> "m500d daemon not responding on 127.0.0.1:8787. Check USB cable / reverse tunnel."
                                !isServerReachable -> "Host PC cannot reach Souffle master storage pool."
                                !isSdSpaceAdequate -> "SD card is nearly full. Free up space on $sdLabel."
                                syncState.isTransferring -> "Fetching missing albums from Souffle and staging for SD card push."
                                else -> "Host daemon online (${daemon.pingLatencyMs}ms ping). SD card has ${sdFreeGB}GB available space."
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ============================================================
            // NAVIGATION TABS (CYBER GLASS)
            // ============================================================
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x4404121A))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("OVERVIEW", "WORKERS (${daemon.workers.size})", "LOGS & FAILS").forEachIndexed { idx, label ->
                    val isSel = selectedTab == idx
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .then(
                                if (isSel) Modifier.background(Brush.horizontalGradient(listOf(MikuCyan, Color(0xFF00B0FF))))
                                else Modifier.background(Color.Transparent)
                            )
                            .clickable { selectedTab = idx }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) Color(0xFF031015) else Color.White.copy(alpha = 0.75f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ============================================================
            // TAB CONTENT BODY
            // ============================================================
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> OverviewTab(
                        daemon = daemon,
                        syncState = syncState,
                        serverTracks = serverTracks,
                        cardTracks = cardTracks,
                        missingTracks = missingTracks,
                        syncPercent = syncPercent,
                        serverGB = serverGB,
                        cardGB = cardGB,
                        missingGB = missingGB,
                        sdFreeGB = sdFreeGB,
                        sdTotalGB = sdTotalGB,
                        internalFreeGB = internalFreeGB,
                        internalTotalGB = internalTotalGB,
                        sdLabel = sdLabel
                    )
                    1 -> WorkersTab(workers = daemon.workers)
                    2 -> LogsTab(failures = daemon.failures, lastEvent = daemon.lastEvent)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Status message readout if any
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    color = MikuCyan,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }

            // ============================================================
            // INTERACTIVE DIAGNOSTIC ACTION BUTTONS WITH TACTILE FEEDBACK
            // ============================================================
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Resume / Stop Sync Button
                CyberTactileButton(
                    text = if (daemon.isTransferring) "⏹ STOP INGRESS" else "⚡ SYNC INGRESS",
                    onClick = {
                        val isStarting = !daemon.isTransferring
                        MikuSyncTransceiver.triggerDaemonSync(isStarting) { success, msg ->
                            statusMessage = msg
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                    accentColor = if (daemon.isTransferring) MikuNeonPink else MikuCyan,
                    icon = if (daemon.isTransferring) Icons.Default.Stop else Icons.Default.FlashOn,
                    isDanger = daemon.isTransferring
                )

                // Speedtest Button
                CyberTactileButton(
                    text = if (isRunningSpeedTest) "TESTING…" else "🚀 SPEEDTEST",
                    onClick = {
                        isRunningSpeedTest = true
                        MikuSyncTransceiver.runSpeedTest { res ->
                            isRunningSpeedTest = false
                            statusMessage = "⚡ ${String.format("%.1f", res.speedMBs)} MB/s (${res.latencyMs}ms)"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    accentColor = Color(0xFFFFD600),
                    icon = Icons.Default.Speed
                )

                // Rescan SD Card Button
                CyberTactileButton(
                    text = if (isScanningLocal) "SCANNING…" else "📁 RESCAN SD",
                    onClick = {
                        isScanningLocal = true
                        // Ingest from the real removable SD volume (resolved at runtime), not the
                        // internal /sdcard alias.
                        MikuSyncTransceiver.scanAndIntegrateDirectory(ctx, MikuSyncTransceiver.getSdMusicPath(ctx)) { count ->
                            isScanningLocal = false
                            statusMessage = "✓ Ingested $count tracks"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    accentColor = Color(0xFF00FFCC),
                    icon = Icons.Default.FolderOpen
                )
            }
        }
    }
}
}

@Composable
fun CyberTactileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MikuCyan,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isDanger: Boolean = false
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1.0f, label = "buttonScale")

    Box(
        modifier = modifier
            .scale(scale)
            .height(36.dp)
            .clip(CutCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    if (isPressed) listOf(accentColor.copy(alpha = 0.45f), Color(0xFF041218))
                    else listOf(accentColor.copy(alpha = 0.20f), Color(0xFF030D12))
                )
            )
            .border(
                BorderStroke(
                    if (isPressed) 1.5.dp else 1.dp,
                    if (isPressed) accentColor else accentColor.copy(alpha = 0.6f)
                ),
                CutCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (isDanger) MikuNeonPink else accentColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = if (isDanger) MikuNeonPink else Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

/** A real storage-volume readout: title/path + used bar + "X GB free of Y GB" from live StatFs. */
@Composable
private fun StorageVolumeRow(
    title: String,
    subtitle: String,
    freeGB: Long,
    totalGB: Long,
    accent: Color
) {
    val usedGB = (totalGB - freeGB).coerceAtLeast(0L)
    val usedFrac = if (totalGB > 0L) (usedGB.toFloat() / totalGB.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.Gray, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (totalGB > 0L) "$freeGB GB free / $totalGB GB" else "unavailable",
                color = if (totalGB > 0L) accent else Color.Gray,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { usedFrac },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = accent,
            trackColor = Color(0xFF102830)
        )
    }
}

@Composable
private fun OverviewTab(
    daemon: MikuSyncTransceiver.DaemonStatus,
    syncState: MikuSyncTransceiver.SyncState,
    serverTracks: Int,
    cardTracks: Int,
    missingTracks: Int,
    syncPercent: Int,
    serverGB: Long,
    cardGB: Long,
    missingGB: Long,
    sdFreeGB: Long,
    sdTotalGB: Long,
    internalFreeGB: Long,
    internalTotalGB: Long,
    sdLabel: String
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Master Library Reconciliation Matrix
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "LIBRARY RECONCILIATION",
                    color = MikuCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Spacer(Modifier.height(6.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { syncPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MikuCyan,
                    trackColor = Color(0xFF102830)
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$cardTracks of $serverTracks tracks synced ($syncPercent%)", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text("$missingTracks missing ($missingGB GB)", color = MikuNeonPink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Master Server (Souffle)", color = Color.Gray, fontSize = 10.sp)
                    Text("$serverTracks tracks ($serverGB GB)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("M500 SD Card ($sdLabel)", color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Text("$cardTracks tracks ($cardGB GB)", color = MikuCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))

                // Real physical storage volumes (live StatFs, no hardcoded sizes).
                Text(
                    "PHYSICAL VOLUMES",
                    color = MikuCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Spacer(Modifier.height(4.dp))
                // SD card — the large removable volume; the music library / ingestion target.
                StorageVolumeRow(
                    title = "SD Card ▸ MUSIC LIBRARY",
                    subtitle = sdLabel,
                    freeGB = sdFreeGB,
                    totalGB = sdTotalGB,
                    accent = MikuCyan
                )
                Spacer(Modifier.height(6.dp))
                // Internal — system / apps.
                StorageVolumeRow(
                    title = "Internal ▸ system / apps",
                    subtitle = "/data",
                    freeGB = internalFreeGB,
                    totalGB = internalTotalGB,
                    accent = Color(0xFF9E9E9E)
                )
            }
        }

        // Ingress Real-Time Throughput Graph & Transfer Histogram
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "THROUGHPUT & HISTOGRAM",
                        color = MikuCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                    val fetchMB = daemon.fetchRateBps / (1024.0 * 1024.0)
                    val pushMB = daemon.pushRateBps / (1024.0 * 1024.0)
                    Text(
                        "📥 ${String.format(Locale.US, "%.1f", fetchMB)} MB/s · 📤 ${String.format(Locale.US, "%.1f", pushMB)} MB/s",
                        color = Color(0xFF00FFCC),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = OrbitronFont
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Throughput Bar Histogram
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(CutCornerShape(6.dp))
                        .background(Color(0xFF020E14))
                        .border(0.6.dp, MikuCyan.copy(alpha = 0.3f), CutCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Real rolling throughput history: each bar is a past sample of the live
                    // fetch+push rate normalized against the active transport's max speed. No
                    // hardcoded/mock values — bars flatten to zero when the pipeline is idle.
                    val maxMBs = syncState.transport.maxSpeedMBs.coerceAtLeast(1).toFloat()
                    val curMBs = ((daemon.fetchRateBps + daemon.pushRateBps) / (1024.0 * 1024.0)).toFloat()
                    val history = remember { mutableStateListOf<Float>().apply { repeat(10) { add(0f) } } }
                    LaunchedEffect(curMBs) {
                        if (history.isNotEmpty()) history.removeAt(0)
                        history.add((curMBs / maxMBs).coerceIn(0f, 1f))
                    }
                    history.forEach { rate ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(rate.coerceIn(0.02f, 1.0f))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(MikuCyan, Color(0xFF00B0FF))
                                    )
                                )
                        )
                    }
                }
            }
        }

        // Live Ingress Pipeline Telemetry
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "INGRESS PIPELINE TELEMETRY",
                    color = MikuCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pipeline Stage", color = Color.Gray, fontSize = 10.sp)
                    val stageText = when {
                        daemon.stage.equals("fetching", ignoreCase = true) -> "STAGE 1: FETCHING (${daemon.fetchActive} WORKERS)"
                        daemon.stage.equals("pushing", ignoreCase = true) -> "STAGE 2: PUSHING TO SD"
                        syncState.isTransferring -> "ACTIVE INGRESS"
                        else -> "IDLE / STANDBY"
                    }
                    Text(
                        stageText,
                        color = if (syncState.isTransferring) MikuNeonPink else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Host Staging Cache", color = Color.Gray, fontSize = 10.sp)
                    val cacheGbStr = String.format("%.2f GB", daemon.cacheBytes / (1024.0 * 1024.0 * 1024.0))
                    Text("${daemon.cacheAlbums} albums ($cacheGbStr)", color = MikuCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Parallel Ingress Streams", color = Color.Gray, fontSize = 10.sp)
                    Text("${daemon.fetchActive} fetching from Souffle / ${daemon.pushActive} pushing to SD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Souffle Server Load", color = Color.Gray, fontSize = 10.sp)
                    Text(if (daemon.souffleLoad.isNotEmpty()) daemon.souffleLoad else "Normal", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Transport Channel", color = Color.Gray, fontSize = 10.sp)
                    Text(syncState.transport.badge, color = MikuCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Central Brain & Subsystem Bone Health
        item {
            val brainTelemetry by MikuBrain.telemetry.collectAsState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "BRAIN & BONE ORCHESTRATION",
                        color = MikuCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        if (brainTelemetry.allBonesHealthy) "🟢 ALL BONES SYNCED" else "⚠️ BONE DEGRADED",
                        color = if (brainTelemetry.allBonesHealthy) Color(0xFF00E676) else MikuNeonPink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))

                brainTelemetry.bones.forEach { (type, health) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(type.displayName, color = Color.Gray, fontSize = 9.5.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (health.state) {
                                            MikuBrain.BoneState.IDLE -> Color.Gray
                                            MikuBrain.BoneState.ACTIVE -> MikuCyan
                                            MikuBrain.BoneState.THROTTLED -> Color.Yellow
                                            MikuBrain.BoneState.STALLED -> MikuNeonPink
                                            MikuBrain.BoneState.ERROR -> Color.Red
                                        }
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                health.state.name,
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkersTab(workers: List<MikuSyncTransceiver.WorkerInfo>) {
    if (workers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active worker threads running", color = Color.Gray, fontSize = 11.sp)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(workers) { w ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, if (w.status == "fetching") MikuCyan.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Worker #${w.id}", color = MikuCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Text(
                                if (w.album.isNotEmpty()) w.album else "Idle / Standby",
                                color = Color.White,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (w.status == "fetching") Color(0x3300E5FF) else Color(0x22FFFFFF))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                w.status.uppercase(),
                                color = if (w.status == "fetching") MikuCyan else Color.Gray,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(failures: List<String>, lastEvent: String) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (lastEvent.isNotEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(8.dp)
                ) {
                    Text("LAST EVENT", color = MikuCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    Spacer(Modifier.height(2.dp))
                    Text(lastEvent, color = Color.White, fontSize = 10.sp)
                }
            }
        }

        if (failures.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x2200E676))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓ 0 Ingress Errors or Socket Failures Reported", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            items(failures) { fail ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FF4081))
                        .border(1.dp, MikuNeonPink.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(fail, color = Color.White, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}
