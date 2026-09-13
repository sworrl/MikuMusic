package com.miku.player.network

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.miku.player.*
import com.miku.player.metrics.MikuMetricDatabase
import com.miku.player.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 3D Embossed Tactile Action Icon Button.
 * Replaces blown-out flat neon circles with a tactile beveled cyber button
 * featuring a top specular highlight rim, dark recessed inner bed, and soft luminescent icon tint.
 */
@Composable
fun Cyber3dIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    accentColor: Color = MikuCyan,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    val buttonShape = remember { CutCornerShape(6.dp) }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(buttonShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.65f)
                    )
                )
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp)
                .clip(CutCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE16303E),
                            Color(0xFF081822)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.75f),
                                CyberGlassBorder.copy(alpha = 0.25f),
                                accentColor.copy(alpha = 0.45f)
                            )
                        )
                    ),
                    CutCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = accentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Hatsune Miku Fully Interactive 3D Embossed RF Network & Wi-Fi Hardware Control Station.
 * Full modern replacement for AOSP Wi-Fi Settings with unlimited hardware telemetry,
 * real-time deduplicated AP scanner, 3D embossed tactile controls, network auth/connect modal,
 * RF channel spectrogram, and low-level radio power controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuNetworkObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkState by MikuNetworkService.state.collectAsState()
    val wifi = networkState.wifi
    val cell = networkState.cellular
    val scannedAPs = networkState.scannedAPs
    val channelOccupancies = networkState.channelOccupancies

    // Filter out the active connected network from the available list to prevent duplication
    val availableAPs = remember(scannedAPs, wifi.isConnected, wifi.ssid) {
        if (wifi.isConnected && wifi.ssid.isNotEmpty()) {
            scannedAPs.filter { it.ssid.isNotBlank() && it.ssid != wifi.ssid && !it.isConnected }
        } else {
            scannedAPs.filter { it.ssid.isNotBlank() }
        }
    }

    var selectedAPForConnect by remember { mutableStateOf<MikuNetworkService.ScannedAccessPoint?>(null) }
    var isAddHiddenNetworkOpen by remember { mutableStateOf(false) }
    var hiddenSsidInput by remember { mutableStateOf("") }
    var hiddenPasswordInput by remember { mutableStateOf("") }
    var connectPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isWgQrModalOpen by remember { mutableStateOf(false) }
    var historyRecords by remember { mutableStateOf<List<MikuMetricDatabase.NetworkRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        historyRecords = MikuMetricDatabase.getInstance(ctx).getRecentNetworkHistory(20)
        MikuNetworkService.triggerScan(ctx)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweepPulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB040D12))
            .clickable { onDismissRequest() }
            .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .align(Alignment.Center)
                .clickable(enabled = false) {}
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
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFA09222E),
                                Color(0xFF04121A),
                                Color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    MikuCyan.copy(alpha = pulseGlow),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    Color(0xFF2979FF).copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(14.dp)
            ) {
                // Frosted Watermark of Task-Specific Telecom & RF Announcer Miku Art
                Image(
                    painter = painterResource(R.drawable.miku_dj_megaphone),
                    contentDescription = "Miku Telecom Announcer",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center)
                        .padding(16.dp),
                    alpha = 0.08f,
                    contentScale = ContentScale.Fit
                )

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // ============================================================
                    // HEADER BAR WITH 3D EMBOSSED TACTILE BUTTONS & MIKU AVATAR
                    // ============================================================
                    MikuNetObsHeaderBar(
                        networkState = networkState,
                        wifi = wifi,
                        cell = cell,
                        onDismissRequest = onDismissRequest
                    )

                    // ============================================================
                    // 3D EMBOSSED CONNECTED WI-FI HERO POD (SHOWN ONLY ONCE)
                    // ============================================================
                    MikuNetObsConnectedWifiHero(wifi = wifi)

                    // ============================================================
                    // 3D EMBOSSED AVAILABLE WI-FI ACCESS POINTS
                    // ============================================================
                    MikuNetObsAvailableApList(
                        networkState = networkState,
                        wifi = wifi,
                        availableAPs = availableAPs,
                        onSelectApForConnect = { selectedAPForConnect = it },
                        onConnectPasswordChange = { connectPassword = it },
                        onAddHiddenNetworkOpenChange = { isAddHiddenNetworkOpen = it }
                    )

                    // ============================================================
                    // 3D EMBOSSED RF CHANNEL OCCUPANCY & CONGESTION SPECTROGRAM
                    // ============================================================
                    MikuNetObsChannelSpectrogram(channelOccupancies = channelOccupancies)

                    // ============================================================
                    // 3D EMBOSSED UNRESTRICTED HARDWARE WIRELESS SETTINGS CARD
                    // ============================================================
                    MikuNetObsHardwareControls(wifi = wifi)

                    // ============================================================
                    // 3D EMBOSSED UDR WIREGUARD SPLIT-TUNNEL & WAN BRIDGE
                    // ============================================================
                    MikuNetObsWireGuardCard(
                        wifi = wifi,
                        onOpenWgQrModal = { isWgQrModalOpen = true }
                    )
                }
            }
        }

        // ============================================================
        // 3D EMBOSSED INTERACTIVE NETWORK AUTH / CONNECTION DIALOG
        // ============================================================
        MikuNetObsConnectDialog(
            selectedAPForConnect = selectedAPForConnect,
            onSelectedApChange = { selectedAPForConnect = it },
            connectPassword = connectPassword,
            onConnectPasswordChange = { connectPassword = it },
            showPassword = showPassword,
            onShowPasswordChange = { showPassword = it }
        )

        // ============================================================
        // 3D EMBOSSED ADD HIDDEN NETWORK MODAL
        // ============================================================
        MikuNetObsAddHiddenDialog(
            isAddHiddenNetworkOpen = isAddHiddenNetworkOpen,
            onAddHiddenNetworkOpenChange = { isAddHiddenNetworkOpen = it },
            hiddenSsidInput = hiddenSsidInput,
            onHiddenSsidInputChange = { hiddenSsidInput = it },
            hiddenPasswordInput = hiddenPasswordInput,
            onHiddenPasswordInputChange = { hiddenPasswordInput = it },
            showPassword = showPassword,
            onShowPasswordChange = { showPassword = it }
        )

        // ============================================================
        // 3D EMBOSSED WIREGUARD SPLIT-TUNNEL QR CODE MODAL
        // ============================================================
        MikuNetObsWireGuardQrModal(
            isWgQrModalOpen = isWgQrModalOpen,
            onWgQrModalOpenChange = { isWgQrModalOpen = it }
        )
    }
}

// ============================================================================
// Sections extracted out of MikuNetworkObservatoryModal.
//
// MikuNetworkObservatoryModal compiled to ~44k dex instructions in one method.
// ART's JIT refuses to compile any method over 16384 instructions, so the whole
// modal was re-interpreted on every recomposition and pinned the main thread.
// Each private composable below is one of the modal's major visual sections,
// moved verbatim so every resulting method lands well under that ceiling.
// ============================================================================

/**
 * Header bar of the RF network observatory: Miku avatar, link/ping summary, the
 * Wi-Fi master power switch, and the 3D embossed scan and close buttons.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsHeaderBar(
    networkState: MikuNetworkService.NetworkState,
    wifi: MikuNetworkService.WifiGranularState,
    cell: MikuNetworkService.CellularGranularState,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current

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
                    painter = painterResource(R.drawable.miku_dj_megaphone),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "ELECTRIC ANGEL TRANSCEIVER",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "CV01 RF Link: ${when {
                        wifi.isConnected -> "${wifi.bandLabel.ifEmpty { "Wi-Fi" }} (${wifi.rssiDbm?.let { "${it}dBm" } ?: "— dBm"})"
                        networkState.activeTransport == "CELLULAR" -> "Cellular ${cell.networkType.ifEmpty { "—" }}"
                        else -> "No link"
                    }} · Ping: ${if (networkState.latencyMs >= 0L) "${networkState.latencyMs}ms" else "unreachable"}",
                    color = MikuCyan,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Wi-Fi Master Power Toggle
            Switch(
                checked = wifi.isEnabled,
                onCheckedChange = { MikuNetworkService.setWifiEnabled(ctx, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MikuCyan,
                    checkedTrackColor = Color(0x3300E5FF),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0x22FFFFFF)
                ),
                modifier = Modifier.scale(0.8f)
            )

            // 3D Embossed Refresh / Scan Button
            Cyber3dIconButton(
                onClick = { MikuNetworkService.triggerScan(ctx) },
                icon = Icons.Default.Refresh,
                contentDescription = "Scan APs",
                accentColor = MikuCyan,
                isLoading = networkState.isScanning
            )

            // 3D Embossed Close Button
            Cyber3dIconButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = "Close Modal",
                accentColor = MikuNeonPink
            )
        }
    }

    Spacer(Modifier.height(10.dp))
}

/**
 * 3D embossed hero pod for the currently connected Wi-Fi network: SSID/BSSID,
 * band and PHY chips, RSSI bars, IP/gateway/DNS readout and the disconnect /
 * forget actions.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsConnectedWifiHero(
    wifi: MikuNetworkService.WifiGranularState
) {
    val ctx = LocalContext.current

    if (wifi.isConnected && wifi.ssid.isNotBlank()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(CutCornerShape(11.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xEE0C2936),
                                Color(0xFF04141E),
                                Color(0xFF020B10)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(listOf(MikuCyan, Color(0x3300E5FF), Color(0xFF2979FF)))
                        ),
                        CutCornerShape(11.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = MikuCyan, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = wifi.ssid,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00E676))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "CONNECTED // ${wifi.bssid}",
                                        color = Color(0xFF00E676),
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .clip(CutCornerShape(4.dp))
                                    .background(MikuCyan.copy(alpha = 0.2f))
                                    .border(0.5.dp, MikuCyan, CutCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (wifi.frequencyMhz > 0) "${wifi.bandLabel} Ch ${wifi.channel}" else "band —", color = MikuCyan, fontSize = 7.5.sp, fontWeight = FontWeight.Black)
                            }
                            Box(
                                Modifier
                                    .clip(CutCornerShape(4.dp))
                                    .background(Color(0x3300E676))
                                    .border(0.5.dp, Color(0xFF00E676), CutCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(wifi.standard.ifEmpty { "PHY —" }, color = Color(0xFF00E676), fontSize = 7.5.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 5-Stage Micro Bars & Link Speed
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(16.dp)
                        ) {
                            repeat(5) { i ->
                                val active = (i + 1) <= wifi.signalLevel5
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height(((i + 1) * 3.2).dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(if (active) MikuCyan else Color.White.copy(alpha = 0.2f))
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${wifi.rssiDbm?.let { "$it dBm" } ?: "— dBm"} · ${wifi.signalPct?.let { "$it%" } ?: "—%"} · Tx ${if (wifi.txLinkSpeedMbps > 0) "${wifi.txLinkSpeedMbps}M" else "—"} / Rx ${if (wifi.rxLinkSpeedMbps > 0) "${wifi.rxLinkSpeedMbps}M" else "—"}",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 3D Recessed Hardware IP & Routing Grid
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(6.dp))
                            .background(Color(0x66020A0E))
                            .border(0.5.dp, CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("IPv4 Address:", color = MikuTextSecondary, fontSize = 8.sp)
                            Text(wifi.ipAddress, color = Color.White, fontSize = 8.sp, fontFamily = AudiowideFont)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Gateway Router:", color = MikuTextSecondary, fontSize = 8.sp)
                            Text(wifi.gateway, color = Color.White, fontSize = 8.sp, fontFamily = AudiowideFont)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("DNS Resolvers:", color = MikuTextSecondary, fontSize = 8.sp)
                            Text(listOf(wifi.dns1, wifi.dns2).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { "—" }, color = Color.White, fontSize = 8.sp, fontFamily = AudiowideFont)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Carrier Frequency:", color = MikuTextSecondary, fontSize = 8.sp)
                            Text(if (wifi.frequencyMhz > 0) "${wifi.frequencyMhz} MHz" else "—", color = MikuCyan, fontSize = 8.sp, fontFamily = AudiowideFont)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 3D Embossed Disconnect & Forget Actions
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                                .clickable { MikuNetworkService.disconnectWifi(ctx) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(1.dp)
                                    .clip(CutCornerShape(5.dp))
                                    .background(Color(0xEE2A0A10))
                                    .border(1.dp, Color(0xFFFF5252), CutCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("DISCONNECT", color = Color(0xFFFF5252), fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            }
                        }

                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                                .clickable { MikuNetworkService.forgetNetwork(ctx, wifi.ssid) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(1.dp)
                                    .clip(CutCornerShape(5.dp))
                                    .background(Color(0xEE102430))
                                    .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("FORGET NETWORK", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Available Wi-Fi access point list (deduplicated scan results) plus the
 * "+ ADD HIDDEN" entry point and the empty/scanning placeholder.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsAvailableApList(
    networkState: MikuNetworkService.NetworkState,
    wifi: MikuNetworkService.WifiGranularState,
    availableAPs: List<MikuNetworkService.ScannedAccessPoint>,
    onSelectApForConnect: (MikuNetworkService.ScannedAccessPoint?) -> Unit,
    onConnectPasswordChange: (String) -> Unit,
    onAddHiddenNetworkOpenChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "AVAILABLE WI-FI ACCESS POINTS (${availableAPs.size})",
            color = MikuCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp
        )

        TextButton(
            onClick = { onAddHiddenNetworkOpenChange(true) },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("+ ADD HIDDEN", color = MikuNeonPink, fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        }
    }

    Spacer(Modifier.height(6.dp))

    if (availableAPs.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(8.dp))
                .background(Color(0x220A222C))
                .border(1.dp, CyberGlassBorder, CutCornerShape(8.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (wifi.isEnabled) "No other Wi-Fi networks in range · Scanning..." else "Wi-Fi is turned off.",
                color = MikuTextSecondary,
                fontSize = 8.5.sp
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            availableAPs.forEach { ap ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.12f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f)
                                )
                            )
                        )
                        .clickable {
                            if (ap.isSaved) {
                                MikuNetworkService.connectToNetwork(ctx, ap.ssid, "", ap.securityType)
                            } else if (ap.securityType.contains("Open")) {
                                MikuNetworkService.connectToNetwork(ctx, ap.ssid, "", "Open")
                            } else {
                                onSelectApForConnect(ap)
                                onConnectPasswordChange("")
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(1.dp)
                            .clip(CutCornerShape(7.dp))
                            .background(Color(0xEE091F2A))
                            .border(1.dp, if (ap.isSaved) MikuCyan.copy(alpha = 0.6f) else CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(7.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (ap.securityType.contains("Open")) Icons.Default.Wifi else Icons.Default.WifiLock,
                                    contentDescription = null,
                                    tint = if (ap.isSaved) Color(0xFF00FF7F) else if (ap.bandLabel == "5GHz") MikuCyan else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = ap.ssid,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (ap.isSaved) {
                                            Box(
                                                Modifier
                                                    .clip(CutCornerShape(3.dp))
                                                    .background(Color(0x3300FF7F))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text("SAVED", color = Color(0xFF00FF7F), fontSize = 6.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                            }
                                        }
                                        Box(
                                            Modifier
                                                .clip(CutCornerShape(3.dp))
                                                .background(if (ap.bandLabel == "5GHz") Color(0x3300E5FF) else Color(0x22FFFFFF))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(ap.bandLabel, color = if (ap.bandLabel == "5GHz") MikuCyan else Color.LightGray, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(
                                            text = "Ch ${ap.channel} · ${ap.securityType}",
                                            color = MikuTextSecondary,
                                            fontSize = 7.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Signal bars
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.Bottom,
                                    modifier = Modifier.height(12.dp)
                                ) {
                                    repeat(5) { i ->
                                        val active = (i + 1) <= ap.signalLevel5
                                        Box(
                                            Modifier
                                                .width(2.5.dp)
                                                .height(((i + 1) * 2.4).dp)
                                                .clip(RoundedCornerShape(0.5.dp))
                                                .background(if (active) MikuCyan else Color.White.copy(alpha = 0.2f))
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${ap.rssiDbm}dBm",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * RF channel occupancy / congestion spectrogram section.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsChannelSpectrogram(
    channelOccupancies: List<MikuNetworkService.ChannelOccupancy>
) {
    Text(
        text = "RF SPECTRUM CHANNEL CONGESTION",
        color = MikuCyan,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

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
                .background(Color(0xFF031018))
                .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2.4 GHz Band (Channels 1-11)", color = MikuTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    Text("5 GHz Band (Channels 36-165)", color = MikuTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))

                if (channelOccupancies.isEmpty()) {
                    Text("No AP spectrum data available yet.", color = MikuTextSecondary, fontSize = 7.5.sp)
                } else {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(channelOccupancies) { ch ->
                            val barHeight = (ch.apCount * 12).coerceIn(16, 48)
                            Box(
                                Modifier
                                    .width(42.dp)
                                    .clip(CutCornerShape(4.dp))
                                    .background(Color(0x2200E5FF))
                                    .border(0.5.dp, MikuCyan.copy(alpha = 0.4f), CutCornerShape(4.dp))
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${ch.apCount} APs",
                                        color = if (ch.apCount > 3) Color(0xFFFF1744) else MikuCyan,
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .width(12.dp)
                                            .height(barHeight.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (ch.apCount > 3) Color(0xFFFF1744)
                                                else if (ch.apCount > 1) Color(0xFFFFD600)
                                                else Color(0xFF00E676)
                                            )
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Ch ${ch.channel}",
                                        color = Color.White,
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * Wi-Fi radio state card: operating band, channel/frequency and 802.11 standard,
 * all read back from WifiInfo. Read-only on purpose — see the note inside.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsHardwareControls(
    wifi: MikuNetworkService.WifiGranularState
) {
    Text(
        text = "RADIO STATE (READ-ONLY)",
        color = MikuCyan,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

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
                .background(Color(0xEE0A232F))
                .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // FAKE-DATA FIX: this card used to host a "Wi-Fi Power Save / Sleep Mode"
                // switch and an AUTO / 5G / 2.4G "Radio Band Steering Preference" selector.
                // Neither one touched the radio. The switch shelled out to
                // `su -c "iw dev wlan0 set power_save ..."` (there is no su on this device,
                // and no `iw` binary) and then set its own state to whatever was tapped; the
                // band buttons set state and nothing else — there was never a call to the
                // Wi-Fi stack at all. Both echoed the tap straight back as "applied".
                // They now read out the radio state the framework actually reports, and say
                // "—" when the framework reports nothing.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Operating Band", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text("From WifiInfo — band steering belongs to the OS, not to this screen", color = MikuTextSecondary, fontSize = 7.sp)
                    }
                    Text(
                        if (wifi.bandLabel.isNotBlank()) wifi.bandLabel else "—",
                        color = MikuCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }

                HorizontalDivider(color = Color(0x2200E5FF))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Channel / Frequency", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text("Live association readout", color = MikuTextSecondary, fontSize = 7.sp)
                    }
                    Text(
                        if (wifi.frequencyMhz > 0) "CH ${wifi.channel} · ${wifi.frequencyMhz} MHz" else "—",
                        color = MikuCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }

                HorizontalDivider(color = Color(0x2200E5FF))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("802.11 Standard", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text("Radio sleep is managed by the Wi-Fi framework — there is no root path here to override it", color = MikuTextSecondary, fontSize = 7.sp)
                    }
                    Text(
                        if (wifi.standard.isNotBlank()) wifi.standard else "—",
                        color = MikuCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * UDR WireGuard split-tunnel / WAN bridge card: home-network bypass headline,
 * endpoint / assigned IP / routes readout, QR-CONF entry point and the tunnel
 * policy explainer.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsWireGuardCard(
    wifi: MikuNetworkService.WifiGranularState,
    onOpenWgQrModal: () -> Unit
) {
    val ctx = LocalContext.current

    Text(
        text = "UDR WIREGUARD SPLIT-TUNNEL (WAN BRIDGE)",
        color = Color(0xFF00E676),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

    val isHomeWifi = remember(wifi.isConnected, wifi.ssid, wifi.ipAddress) {
        MikuIngestConfig.isHomeNetwork(ctx, wifi.isConnected, wifi.ssid, wifi.ipAddress)
    }

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
                .background(Color(0xEE081F26))
                .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tunnel Status & Endpoint
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isHomeWifi) Color(0xFF00E5FF) else Color(0xFF00E676))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isHomeWifi) "DIRECT HOME LAN (TUNNEL BYPASSED)" else "SPLIT-TUNNEL ONLINE (4G / WAN)",
                                color = if (isHomeWifi) MikuCyan else Color(0xFF00E676),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "WAN Endpoint: ${MikuIngestConfig.vpnEndpoint(ctx).ifBlank { "not configured" }} · Assigned IP: ${MikuIngestConfig.vpnAssignedIp(ctx).ifBlank { "auto" }}",
                            color = MikuTextSecondary,
                            fontSize = 7.5.sp
                        )
                        Text(
                            "Routes: ${MikuIngestConfig.vpnRoutes(ctx).ifBlank { "not configured" }}",
                            color = MikuTextSecondary,
                            fontSize = 7.sp
                        )
                    }

                    Button(
                        onClick = { onOpenWgQrModal() },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676).copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, Color(0xFF00E676)),
                        shape = CutCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("QR / CONF", color = Color(0xFF00E676), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                }

                HorizontalDivider(color = Color(0x2200E676))

                // Dynamic Auto-Tunnel Rules Explanation
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(4.dp))
                        .background(Color(0xFF04131A))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(
                            "TUNNEL POLICY & CONDITIONAL ROUTING:",
                            color = MikuCyan,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            "• On Home Wi-Fi: WireGuard is bypassed to prevent double-NAT loops.\n• On 4G LTE or External Wi-Fi: Automatically routes the configured LAN subnets back to the WAN endpoint${MikuIngestConfig.vpnEndpoint(ctx).let { if (it.isNotBlank()) " at $it" else "" }}.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 7.sp,
                            lineHeight = 10.sp
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
}

/**
 * Interactive network auth / connection dialog overlay for a tapped access point.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsConnectDialog(
    selectedAPForConnect: MikuNetworkService.ScannedAccessPoint?,
    onSelectedApChange: (MikuNetworkService.ScannedAccessPoint?) -> Unit,
    connectPassword: String,
    onConnectPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onShowPasswordChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    if (selectedAPForConnect != null) {
        val ap = selectedAPForConnect!!
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC8000000))
                .clickable { onSelectedApChange(null) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Color(0xFF031620))
                        .border(1.dp, MikuCyan, CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "CONNECT TO NETWORK",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = ap.ssid,
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = "BSSID: ${ap.bssid} · ${ap.bandLabel} Ch ${ap.channel} (${ap.securityType})",
                            color = MikuTextSecondary,
                            fontSize = 7.5.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        if (!ap.securityType.contains("Open")) {
                            OutlinedTextField(
                                value = connectPassword,
                                onValueChange = { onConnectPasswordChange(it) },
                                label = { Text("Network Password / Key", fontSize = 9.5.sp) },
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { onShowPasswordChange(!showPassword) }) {
                                        Icon(
                                            if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = MikuCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MikuCyan,
                                    unfocusedBorderColor = CyberGlassBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = MikuCyan
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("This is an unencrypted Open network. No password is required.", color = Color(0xFF00E676), fontSize = 8.5.sp)
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable { onSelectedApChange(null) }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE12222E))
                                        .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CANCEL", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable {
                                        MikuNetworkService.connectToNetwork(ctx, ap.ssid, connectPassword, ap.securityType)
                                        onSelectedApChange(null)
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE09303E))
                                        .border(1.dp, MikuCyan, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CONNECT", color = MikuCyan, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add-hidden-network dialog overlay (manual SSID + passphrase entry).
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsAddHiddenDialog(
    isAddHiddenNetworkOpen: Boolean,
    onAddHiddenNetworkOpenChange: (Boolean) -> Unit,
    hiddenSsidInput: String,
    onHiddenSsidInputChange: (String) -> Unit,
    hiddenPasswordInput: String,
    onHiddenPasswordInputChange: (String) -> Unit,
    showPassword: Boolean,
    onShowPasswordChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    if (isAddHiddenNetworkOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC8000000))
                .clickable { onAddHiddenNetworkOpenChange(false) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Color(0xFF031620))
                        .border(1.dp, MikuNeonPink, CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "ADD HIDDEN WI-FI NETWORK",
                            color = MikuNeonPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = hiddenSsidInput,
                            onValueChange = { onHiddenSsidInputChange(it) },
                            label = { Text("Network SSID Name", fontSize = 9.5.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MikuCyan,
                                unfocusedBorderColor = CyberGlassBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = MikuCyan
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = hiddenPasswordInput,
                            onValueChange = { onHiddenPasswordInputChange(it) },
                            label = { Text("Password (Leave blank if open)", fontSize = 9.5.sp) },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { onShowPasswordChange(!showPassword) }) {
                                    Icon(
                                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = MikuCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MikuCyan,
                                unfocusedBorderColor = CyberGlassBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = MikuCyan
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable { onAddHiddenNetworkOpenChange(false) }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE12222E))
                                        .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CANCEL", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable {
                                        if (hiddenSsidInput.isNotBlank()) {
                                            MikuNetworkService.connectToNetwork(ctx, hiddenSsidInput, hiddenPasswordInput, if (hiddenPasswordInput.isNotEmpty()) "WPA2 Personal" else "Open Network")
                                            onAddHiddenNetworkOpenChange(false)
                                            onHiddenSsidInputChange("")
                                            onHiddenPasswordInputChange("")
                                        }
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE3A091E))
                                        .border(1.dp, MikuNeonPink, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CONNECT", color = MikuNeonPink, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * WireGuard split-tunnel config modal: the peer config is assembled at runtime
 * from MikuIngestConfig (no bundled private key) and shown for import into the
 * user's own WireGuard app.
 *
 * Extracted out of [MikuNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MikuNetObsWireGuardQrModal(
    isWgQrModalOpen: Boolean,
    onWgQrModalOpenChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    if (isWgQrModalOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD8000000))
                .clickable { onWgQrModalOpenChange(false) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Color(0xFF031620))
                        .border(1.dp, Color(0xFF00E676), CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WIREGUARD SPLIT-TUNNEL QR",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            IconButton(onClick = { onWgQrModalOpenChange(false) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // The WireGuard peer config is assembled at runtime from
                        // MikuIngestConfig (gitignored local.properties / prefs). The private
                        // key and real endpoint are NEVER bundled — the baked QR PNG that
                        // embedded a real private key + WAN IP was removed during the PII scrub.
                        // Users import their own key via the WireGuard app.
                        run {
                            val wgAddr = MikuIngestConfig.vpnAssignedIp(ctx)
                            val wgEndpoint = MikuIngestConfig.vpnEndpoint(ctx)
                            val wgRoutes = MikuIngestConfig.vpnRoutes(ctx)
                            val configured = wgAddr.isNotBlank() || wgEndpoint.isNotBlank() || wgRoutes.isNotBlank()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF04121A))
                                    .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                if (!configured) {
                                    Text(
                                        text = "VPN not configured.\nSet miku.vpn.* in your config to enable the split-tunnel.",
                                        color = MikuTextSecondary,
                                        fontSize = 9.sp
                                    )
                                } else {
                                    Text(
                                        text = "[Interface]\nAddress = ${wgAddr.ifBlank { "—" }}\n\n[Peer]\nEndpoint = ${wgEndpoint.ifBlank { "—" }}\nAllowedIPs = ${wgRoutes.ifBlank { "—" }}",
                                        color = Color(0xFF9EE6C8),
                                        fontSize = 9.sp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "PrivateKey is never bundled — import it from your own WireGuard config.",
                                        color = MikuTextSecondary,
                                        fontSize = 7.5.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = "UDR WAN Endpoint: ${MikuIngestConfig.vpnEndpoint(ctx).ifBlank { "not configured" }}\nAllowed IPs: ${MikuIngestConfig.vpnRoutes(ctx).ifBlank { "not configured" }}\nActive on 4G LTE & External Wi-Fi",
                            color = MikuTextSecondary,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
