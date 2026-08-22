package com.miku.systemui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class ShadePhase {
    COMPACT,
    EXPANDED
}

enum class ShadeDetailPanel {
    NONE,
    WIFI,
    BLUETOOTH,
    DAC,
    PULSAR
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MikuNotificationShadeView(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    val tiles = remember(refreshKey) { QuickSettingsModel.getTiles(ctx, scope) { refreshKey++ } }

    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var currentVol by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val cr = ctx.contentResolver
    var brightness by remember {
        mutableIntStateOf(
            try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { 128 }
        )
    }

    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val batteryPct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

    val timeStr = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val dateStr = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()) }

    var phase by remember { mutableStateOf(ShadePhase.COMPACT) }
    var activePanel by remember { mutableStateOf(ShadeDetailPanel.NONE) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) {
        if (activePanel != ShadeDetailPanel.NONE) {
            activePanel = ShadeDetailPanel.NONE
        } else if (phase == ShadePhase.EXPANDED) {
            phase = ShadePhase.COMPACT
        } else {
            onDismiss()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0030C10))
            .pointerInput(phase, activePanel) {
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDragEnd = {
                        if (totalDragY > 40f && phase == ShadePhase.COMPACT && activePanel == ShadeDetailPanel.NONE) {
                            phase = ShadePhase.EXPANDED
                        } else if (totalDragY < -40f) {
                            if (activePanel != ShadeDetailPanel.NONE) {
                                activePanel = ShadeDetailPanel.NONE
                            } else if (phase == ShadePhase.EXPANDED) {
                                phase = ShadePhase.COMPACT
                            } else {
                                onDismiss()
                            }
                        }
                    },
                    onDragCancel = { totalDragY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount
                    }
                )
            }
            .padding(top = 28.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {

            // =========================================================================
            // 1. HEADER ROW: Time, Date, Battery Pill, Power & Miku Settings
            // =========================================================================
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        timeStr,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        dateStr,
                        color = MikuTealBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Battery Pill
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MikuSurface2)
                            .border(1.dp, MikuTeal.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable { QuickSettingsModel.openMikuSettings(ctx, "battery") }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = MikuTealBright,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$batteryPct%",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Power Menu Button
                    IconButton(
                        onClick = { showPowerDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MikuSurface2)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                    }

                    // Full MikuOS Settings Button
                    IconButton(
                        onClick = {
                            onDismiss()
                            QuickSettingsModel.openMikuSettings(ctx)
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MikuSurface2)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MikuTealBright, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // =========================================================================
            // 2. DUAL DYNAMIC CYBER SLIDERS: Brightness & Audiophile Volume
            // =========================================================================
            Column(
                Modifier
                    .fillMaxWidth()
                    .mikuGlassCard()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Screen Brightness
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = brightness.toFloat(),
                        onValueChange = {
                            brightness = it.toInt()
                            try {
                                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it.toInt())
                            } catch (_: Throwable) {
                                RootShell.execFast("settings put system screen_brightness ${it.toInt()}")
                            }
                        },
                        valueRange = 5f..255f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = MikuTealBright, activeTrackColor = MikuTeal, inactiveTrackColor = MikuSurface2)
                    )
                }

                Spacer(Modifier.height(2.dp))

                // Master DAC Media Volume
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MikuPinkBright, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = currentVol.toFloat(),
                        onValueChange = {
                            currentVol = it.toInt()
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                        },
                        valueRange = 0f..maxVol.toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = MikuPinkBright, activeTrackColor = MikuPink, inactiveTrackColor = MikuSurface2)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // =========================================================================
            // 3. MAIN TRAY BODY (PIXEL 2x2 PILLS / MINI PANELS / QUICK TILES)
            // =========================================================================
            AnimatedContent(
                targetState = activePanel,
                label = "DetailPanelAnim"
            ) { panel ->
                when (panel) {
                    ShadeDetailPanel.WIFI -> {
                        WifiDetailPanel(
                            ctx = ctx,
                            onClose = { activePanel = ShadeDetailPanel.NONE },
                            onRefresh = { refreshKey++ },
                            onOpenFullSettings = {
                                onDismiss()
                                QuickSettingsModel.openMikuSettings(ctx, "wireless")
                            }
                        )
                    }
                    ShadeDetailPanel.BLUETOOTH -> {
                        BluetoothDetailPanel(
                            ctx = ctx,
                            onClose = { activePanel = ShadeDetailPanel.NONE },
                            onRefresh = { refreshKey++ },
                            onOpenFullSettings = {
                                onDismiss()
                                QuickSettingsModel.openMikuSettings(ctx, "bluetooth")
                            }
                        )
                    }
                    ShadeDetailPanel.DAC -> {
                        DacDetailPanel(
                            ctx = ctx,
                            onClose = { activePanel = ShadeDetailPanel.NONE },
                            onRefresh = { refreshKey++ },
                            onOpenFullSettings = {
                                onDismiss()
                                QuickSettingsModel.openMikuSettings(ctx, "audio_dac")
                            }
                        )
                    }
                    ShadeDetailPanel.PULSAR -> {
                        PulsarDetailPanel(
                            ctx = ctx,
                            onClose = { activePanel = ShadeDetailPanel.NONE },
                            onRefresh = { refreshKey++ },
                            onOpenFullSettings = {
                                onDismiss()
                                QuickSettingsModel.openMikuSettings(ctx, "pulsar")
                            }
                        )
                    }
                    ShadeDetailPanel.NONE -> {
                        // Standard Pixel-Style Quick Settings Tray
                        Column(Modifier.fillMaxWidth()) {
                            // 2x2 Large Feature Pills (Pixel Internet/BT/DAC/RGB Style)
                            val (isWifiOn, wifiSsid, _) = QuickSettingsModel.getWifiInfo(ctx)
                            val (isBtOn, btLabel) = QuickSettingsModel.getBluetoothInfo(ctx)
                            val dacFilter = CirrusLogicManager.getDigitalFilter(ctx)
                            val isPulsarActive = PulsarLight.getMode(ctx) != PulsarLight.Mode.OFF

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 1. Internet / Wi-Fi Pill
                                PixelFeaturePill(
                                    modifier = Modifier.weight(1f),
                                    icon = if (isWifiOn) Icons.Default.Wifi else Icons.Default.WifiOff,
                                    label = if (isWifiOn) wifiSsid else "Internet",
                                    subtitle = if (isWifiOn) "Connected" else "Off",
                                    isActive = isWifiOn,
                                    onClick = { activePanel = ShadeDetailPanel.WIFI },
                                    onLongClick = {
                                        onDismiss()
                                        QuickSettingsModel.openMikuSettings(ctx, "wireless")
                                    }
                                )

                                // 2. Bluetooth Pill
                                PixelFeaturePill(
                                    modifier = Modifier.weight(1f),
                                    icon = if (isBtOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                                    label = if (isBtOn) btLabel else "Bluetooth",
                                    subtitle = if (isBtOn) "Active" else "Off",
                                    isActive = isBtOn,
                                    onClick = { activePanel = ShadeDetailPanel.BLUETOOTH },
                                    onLongClick = {
                                        onDismiss()
                                        QuickSettingsModel.openMikuSettings(ctx, "bluetooth")
                                    }
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 3. Cirrus Master DAC Pill
                                PixelFeaturePill(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.GraphicEq,
                                    label = "Master DAC",
                                    subtitle = dacFilter.name.replace("_", " "),
                                    isActive = true,
                                    onClick = { activePanel = ShadeDetailPanel.DAC },
                                    onLongClick = {
                                        onDismiss()
                                        QuickSettingsModel.openMikuSettings(ctx, "audio_dac")
                                    }
                                )

                                // 4. Pulsar RGB Pill
                                PixelFeaturePill(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Lightbulb,
                                    label = "Pulsar RGB",
                                    subtitle = PulsarLight.getMode(ctx).label,
                                    isActive = isPulsarActive,
                                    onClick = { activePanel = ShadeDetailPanel.PULSAR },
                                    onLongClick = {
                                        onDismiss()
                                        QuickSettingsModel.openMikuSettings(ctx, "pulsar")
                                    }
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // 2x3 Quick Action Tiles Grid
                            val subTiles = tiles.filter { it.id !in listOf("wifi", "bluetooth", "cs43198_filter", "pulsar_light") }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().height(if (phase == ShadePhase.EXPANDED) 160.dp else 80.dp)
                            ) {
                                items(if (phase == ShadePhase.EXPANDED) subTiles else subTiles.take(3)) { tile ->
                                    Box(
                                        Modifier
                                            .height(72.dp)
                                            .mikuTile(tile.isActive)
                                            .combinedClickable(
                                                onClick = { tile.onClick() },
                                                onLongClick = { tile.onLongClick?.invoke() }
                                            )
                                            .padding(6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                tile.icon,
                                                contentDescription = null,
                                                tint = if (tile.isActive) MikuTealBright else MikuMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                tile.label,
                                                color = if (tile.isActive) Color.White else MikuMuted,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                tile.subtitle,
                                                color = if (tile.isActive) MikuTeal else MikuMuted.copy(alpha = 0.7f),
                                                fontSize = 7.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Now Playing Media Card
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .mikuGlassCard()
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MikuSurface2),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Miku Music Player", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("CS43198 Direct ALSA • Bit-Perfect Native", color = MikuTeal, fontSize = 9.5.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            try {
                                                val intent = ctx.packageManager.getLaunchIntentForPackage("com.miku.player")
                                                if (intent != null) ctx.startActivity(intent)
                                            } catch (_: Throwable) {}
                                        }
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = MikuTealBright, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // =========================================================================
            // 4. FOOTER / GESTURE HANDLE
            // =========================================================================
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (activePanel != ShadeDetailPanel.NONE) activePanel = ShadeDetailPanel.NONE
                        else if (phase == ShadePhase.COMPACT) phase = ShadePhase.EXPANDED
                        else phase = ShadePhase.COMPACT
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(width = 48.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MikuTeal.copy(alpha = 0.6f))
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (activePanel != ShadeDetailPanel.NONE) "▲ TAP OR SWIPE TO RETURN"
                    else if (phase == ShadePhase.COMPACT) "▼ PULL DOWN FOR QUICK SETTINGS • ▲ SWIPE UP TO DISMISS"
                    else "▲ SWIPE UP TO COLLAPSE",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }

    // Power / Reboot Dialog Modal
    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            title = { Text("MikuOS Power Core", color = MikuTealBright, fontWeight = FontWeight.Bold) },
            text = { Text("Choose a power management action for the M500 DAP:", color = Color.White, fontSize = 12.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPowerDialog = false
                        RootShell.execFast("reboot")
                    }
                ) {
                    Text("REBOOT", color = MikuTealBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showPowerDialog = false
                            RootShell.execFast("reboot recovery")
                        }
                    ) {
                        Text("RECOVERY", color = MikuPurple, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            showPowerDialog = false
                            RootShell.execFast("reboot -p")
                        }
                    ) {
                        Text("POWER OFF", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = MikuDarkBg,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

// -----------------------------------------------------------------------------
// Pixel-Style Large Feature Pill Component
// -----------------------------------------------------------------------------
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PixelFeaturePill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    subtitle: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(58.dp)
            .mikuTile(isActive)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isActive) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isActive) MikuTealBright else MikuMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (isActive) Color.White else MikuMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = if (isActive) MikuTeal else MikuMuted.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isActive) MikuTealBright.copy(alpha = 0.7f) else MikuMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Interactive In-Shade Wi-Fi Mini Panel (Pixel Internet Dialog)
// -----------------------------------------------------------------------------
@Composable
fun WifiDetailPanel(
    ctx: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    var isWifiEnabled by remember { mutableStateOf(wm?.isWifiEnabled == true) }
    val (connected, currentSsid, _) = QuickSettingsModel.getWifiInfo(ctx)

    Column(
        Modifier
            .fillMaxWidth()
            .mikuGlassCard()
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikuTealBright)
                }
                Spacer(Modifier.width(6.dp))
                Text("Internet / Wi-Fi", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Switch(
                checked = isWifiEnabled,
                onCheckedChange = {
                    isWifiEnabled = it
                    QuickSettingsModel.toggleWifi(ctx, it)
                    onRefresh()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = MikuTeal)
            )
        }

        Spacer(Modifier.height(10.dp))

        if (isWifiEnabled && connected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MikuTealBright.copy(alpha = 0.15f))
                    .border(1.dp, MikuTealBright.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(currentSsid, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Connected • High-Performance 5GHz/2.4GHz", color = MikuTeal, fontSize = 9.5.sp)
                    }
                    Icon(Icons.Default.Check, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Full Miku Wireless Settings Action
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MikuSurface2),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MikuTeal.copy(alpha = 0.5f))
        ) {
            Text("Open All Wi-Fi Networks & Vault ➔", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -----------------------------------------------------------------------------
// Interactive In-Shade Bluetooth Mini Panel (Pixel Bluetooth Dialog)
// -----------------------------------------------------------------------------
@Composable
fun BluetoothDetailPanel(
    ctx: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val bt = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null }
    var isBtEnabled by remember { mutableStateOf(bt?.isEnabled == true) }

    Column(
        Modifier
            .fillMaxWidth()
            .mikuGlassCard()
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikuTealBright)
                }
                Spacer(Modifier.width(6.dp))
                Text("Bluetooth Devices", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Switch(
                checked = isBtEnabled,
                onCheckedChange = {
                    isBtEnabled = it
                    QuickSettingsModel.toggleBluetooth(ctx, it)
                    onRefresh()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = MikuTeal)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Paired devices summary
        if (isBtEnabled) {
            val paired = try { bt?.bondedDevices?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
            if (paired.isNotEmpty()) {
                Text("PAIRED AUDIO DEVICES", color = MikuMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    paired.take(3).forEach { dev ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MikuSurface2)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Headphones, contentDescription = null, tint = MikuPurple, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(dev.name ?: "Unknown Device", color = Color.White, fontSize = 11.sp)
                            }
                            Text("LDAC / AAC", color = MikuTeal, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // Full Miku Bluetooth Settings Action
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MikuSurface2),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MikuPurple.copy(alpha = 0.5f))
        ) {
            Text("Open Full Bluetooth Settings ➔", color = MikuPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -----------------------------------------------------------------------------
// Interactive In-Shade DAC Mini Panel
// -----------------------------------------------------------------------------
@Composable
fun DacDetailPanel(
    ctx: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val curFilter = CirrusLogicManager.getDigitalFilter(ctx)

    Column(
        Modifier
            .fillMaxWidth()
            .mikuGlassCard()
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikuTealBright)
            }
            Spacer(Modifier.width(6.dp))
            Text("Cirrus Logic CS43198 DAC", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        // Digital Filter Selector
        Text("DIGITAL INTERPOLATION FILTER", color = MikuMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val filters = listOf(
                CirrusLogicManager.DigitalFilter.FAST_LINEAR to "Fast Lin",
                CirrusLogicManager.DigitalFilter.FAST_MINIMUM to "Fast Min",
                CirrusLogicManager.DigitalFilter.SLOW_LINEAR to "Slow Lin",
                CirrusLogicManager.DigitalFilter.NOS to "NOS"
            )
            filters.forEach { (f, label) ->
                val isSel = curFilter == f
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2)
                        .border(1.dp, if (isSel) MikuTealBright else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable {
                            scope.launch {
                                CirrusLogicManager.setDigitalFilter(ctx, f)
                                onRefresh()
                            }
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (isSel) MikuTealBright else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Full Miku DAC Settings Action
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MikuSurface2),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MikuTeal.copy(alpha = 0.5f))
        ) {
            Text("Open Audiophile DAC Settings ➔", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -----------------------------------------------------------------------------
// Interactive In-Shade Pulsar RGB Mini Panel
// -----------------------------------------------------------------------------
@Composable
fun PulsarDetailPanel(
    ctx: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val curMode = PulsarLight.getMode(ctx)

    Column(
        Modifier
            .fillMaxWidth()
            .mikuGlassCard()
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikuTealBright)
            }
            Spacer(Modifier.width(6.dp))
            Text("Pulsar Dual-Die RGB", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        Text("ANIMATION / SYNC PRESET", color = MikuMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val modes = listOf(
                PulsarLight.Mode.OFF to "Off",
                PulsarLight.Mode.AUDIOPHILE_AUTO to "BPM Sync",
                PulsarLight.Mode.CHROMA_RAINBOW to "Rainbow",
                PulsarLight.Mode.SMOOTH_BREATHING to "Breath"
            )
            modes.forEach { (m, label) ->
                val isSel = curMode == m
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) MikuPinkBright.copy(alpha = 0.25f) else MikuSurface2)
                        .border(1.dp, if (isSel) MikuPinkBright else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable {
                            PulsarLight.setMode(ctx, m)
                            onRefresh()
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (isSel) MikuPinkBright else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Full Miku Pulsar Settings Action
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MikuSurface2),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MikuPinkBright.copy(alpha = 0.5f))
        ) {
            Text("Open Full Pulsar RGB Settings ➔", color = MikuPinkBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
