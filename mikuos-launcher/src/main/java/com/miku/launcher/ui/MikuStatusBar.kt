package com.miku.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.AudiowideFont
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink
import com.miku.launcher.MikuTextSecondary
import kotlinx.coroutines.delay

/**
 * Everything the thin top bar shows. Mirrors a stock Android status bar's information
 * hierarchy: clock on the left, connection + device glyphs on the right.
 */
data class MikuStatusBarState(
    val clock: String = "",
    val wifiConnected: Boolean = false,
    val wifiLevel: Int = 0,            // 0..4
    val cellConnected: Boolean = false,
    val cellLevel: Int = 0,            // 0..4
    val cellType: String = "",         // "LTE", "5G"…
    val bluetoothConnected: Boolean = false,
    val vpnUp: Boolean = false,
    val volumePct: Int = 50,
    val isMuted: Boolean = false,
    val thermalC: Float = 0f,          // shown only when hot
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val isPlaying: Boolean = false
)

/** Signal strength bucket for the Wi-Fi / cellular glyph tint. */
private fun levelFromDbm(dbm: Int, floor: Int, ceil: Int): Int {
    if (dbm >= ceil) return 4
    if (dbm <= floor) return 0
    return (((dbm - floor).toFloat() / (ceil - floor)) * 4f + 0.5f).toInt().coerceIn(0, 4)
}

/**
 * Collects the live launcher state (network service, volume manager, WireGuard status,
 * Bluetooth profile state, battery sticky intent) into a [MikuStatusBarState].
 * [clock] and [isPlaying]/[thermalC] are passed in by the host screen, which already owns them.
 */
@Composable
fun rememberMikuStatusBarState(
    clock: String,
    isPlaying: Boolean,
    thermalC: Float
): MikuStatusBarState {
    val ctx = LocalContext.current
    val net by com.miku.launcher.network.MikuNetworkService.state.collectAsState()
    val vol by com.miku.launcher.volume.MikuVolumeManager.state.collectAsState()
    val wg by com.miku.launcher.vpn.MikuWireGuardManager.status.collectAsState()
    var btConnected by remember { mutableStateOf(false) }
    var batteryPct by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            btConnected = try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter != null && adapter.isEnabled && (
                    adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                        adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
                    )
            } catch (_: Throwable) { false }
            try {
                val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (i != null) {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) batteryPct = (level * 100 / scale)
                    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                }
            } catch (_: Throwable) {}
            delay(4000)
        }
    }
    @Suppress("UNUSED_VARIABLE") val wgTick = wg  // recompose on tunnel status changes
    val vpnUp = try { com.miku.launcher.vpn.MikuWireGuardManager.isUp() } catch (_: Throwable) { false }
    return MikuStatusBarState(
        clock = clock,
        wifiConnected = net.wifi.isConnected,
        wifiLevel = if (net.wifi.isConnected) levelFromDbm(net.wifi.rssiDbm, -90, -55) else 0,
        cellConnected = net.cellular.isConnected && net.cellular.hasSignal,
        cellLevel = if (net.cellular.hasSignal) levelFromDbm(net.cellular.signalDbm, -115, -80) else 0,
        cellType = net.cellular.networkType.substringBefore(' ').take(4),
        bluetoothConnected = btConnected,
        vpnUp = vpnUp,
        volumePct = vol.volumePct,
        isMuted = vol.isMuted,
        thermalC = thermalC,
        batteryPct = batteryPct,
        isCharging = charging,
        isPlaying = isPlaying
    )
}

/**
 * MikuOS TOP BAR — a thin, standard-layout status bar (30dp), NOT a card:
 *   LEFT  : small text clock (+ a tiny note glyph while audio plays)
 *   RIGHT : Wi-Fi · cellular · Bluetooth · VPN · volume/mute · thermal (only when hot) · battery
 * Miku-styled (teal glyphs, quilt-stitch hairline seam at the bottom edge, soft glow) but with
 * exactly the information hierarchy and static behavior of a stock status bar. Reusable so the
 * SystemUI overlay can host the same bar system-wide.
 */
@Composable
fun MikuStatusBar(
    state: MikuStatusBarState,
    modifier: Modifier = Modifier,
    onClockClick: (() -> Unit)? = null
) {
    val glyph = MikuDimens.statusGlyph
    val seam = MikuCyan.copy(alpha = 0.55f)
    Row(
        modifier
            .fillMaxWidth()
            .height(MikuDimens.statusBarHeight)
            .drawBehind {
                // Barely-there wash so the bar reads over any wallpaper, then the stitched seam.
                drawRect(Brush.verticalGradient(listOf(Color(0x66040D12), Color(0x22040D12))))
                val stitch = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                val y = size.height - 1.dp.toPx()
                drawLine(seam, Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), 1.2.dp.toPx(), pathEffect = stitch)
            }
            .padding(horizontal = MikuDimens.screenHPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // LEFT — clock (+ playing glyph)
        val clockInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onClockClick != null) Modifier.clickable(
                interactionSource = clockInteraction,
                indication = null,
                onClick = onClockClick
            ) else Modifier
        ) {
            Text(
                text = state.clock,
                color = Color(0xFFE0FFFC),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                softWrap = false
            )
            if (state.isPlaying) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.MusicNote, contentDescription = "Playing", tint = MikuNeonPink, modifier = Modifier.size(glyph))
            }
        }
        // RIGHT — connection + device glyphs in stock order
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state.vpnUp) Icon(Icons.Default.VpnLock, contentDescription = "VPN", tint = MikuCyan, modifier = Modifier.size(glyph))
            if (state.bluetoothConnected) Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth", tint = MikuCyan, modifier = Modifier.size(glyph))
            Icon(
                if (state.wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = "Wi-Fi",
                tint = if (state.wifiConnected) MikuCyan.copy(alpha = 0.45f + 0.55f * (state.wifiLevel / 4f)) else MikuTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(glyph)
            )
            if (state.cellConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SignalCellular4Bar,
                        contentDescription = "Signal",
                        tint = MikuCyan.copy(alpha = 0.45f + 0.55f * (state.cellLevel / 4f)),
                        modifier = Modifier.size(glyph)
                    )
                    if (state.cellType.isNotEmpty()) {
                        Text(state.cellType, color = MikuTextSecondary, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            } else {
                Icon(Icons.Default.SignalCellularConnectedNoInternet0Bar, contentDescription = "No signal", tint = MikuTextSecondary.copy(alpha = 0.45f), modifier = Modifier.size(glyph))
            }
            Icon(
                if (state.isMuted || state.volumePct == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Volume",
                tint = if (state.isMuted) MikuNeonPink else MikuCyan,
                modifier = Modifier.size(glyph)
            )
            if (state.thermalC >= 45f) {
                Icon(Icons.Default.Thermostat, contentDescription = "Hot", tint = Color(0xFFFF8A65), modifier = Modifier.size(glyph))
            }
            // Battery: percentage text + a small drawn cell that fills with the level.
            val batteryColor = when {
                state.isCharging -> Color(0xFF00E676)
                state.batteryPct > 50 -> MikuCyan
                state.batteryPct > 20 -> Color(0xFFFFD600)
                else -> Color(0xFFFF1744)
            }
            Text(
                "${state.batteryPct}%",
                color = Color(0xFFE0FFFC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                maxLines = 1,
                softWrap = false
            )
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.width(20.dp).height(11.dp)) {
                    val bodyW = size.width - 3.dp.toPx()
                    val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    drawRoundRect(color = batteryColor.copy(alpha = 0.85f), size = Size(bodyW, size.height), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
                    drawRoundRect(color = batteryColor.copy(alpha = 0.85f), topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f), cornerRadius = CornerRadius(1f, 1f))
                    val inset = 2.dp.toPx()
                    val fillW = ((bodyW - inset * 2) * (state.batteryPct / 100f)).coerceAtLeast(0f)
                    drawRoundRect(color = batteryColor, topLeft = Offset(inset, inset), size = Size(fillW, size.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()))
                }
                if (state.isCharging) {
                    Icon(Icons.Default.Bolt, contentDescription = "Charging", tint = Color.White, modifier = Modifier.size(9.dp))
                }
            }
        }
    }
}
