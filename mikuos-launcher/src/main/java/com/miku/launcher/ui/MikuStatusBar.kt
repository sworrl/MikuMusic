@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.miku.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
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
 * Everything the thin top bar shows — a stock status bar's hierarchy, but data-verbose:
 * LEFT  clock · live BPM · now-playing quality ("24/96")
 * RIGHT thermal °C · volume % · Wi-Fi + dBm · cellular · BT · VPN · battery % + bolt
 */
data class MikuStatusBarState(
    val clock: String = "",
    val bpm: Int = 0,                  // 0 = hide
    val isPlaying: Boolean = false,
    val quality: String = "",          // "24/96" while playing, "" otherwise
    val wifiConnected: Boolean = false,
    val wifiDbm: Int = -100,
    val wifiLevel: Int = 0,            // 0..4
    val cellConnected: Boolean = false,
    val cellLevel: Int = 0,            // 0..4
    val cellType: String = "",         // "LTE", "5G"…
    val bluetoothConnected: Boolean = false,
    val vpnUp: Boolean = false,
    val volumePct: Int = 0,
    val volumeKnown: Boolean = false, // false until MikuVolumeManager has read AudioManager once
    val isMuted: Boolean = false,
    val thermalC: Float = 0f,          // 0 = no sensor read yet → rendered as "—°"
    val batteryPct: Int = -1,          // -1 = not read yet → rendered as "—%" with an empty cell
    val isCharging: Boolean = false
)

private fun levelFromDbm(dbm: Int, floor: Int, ceil: Int): Int {
    if (dbm >= ceil) return 4
    if (dbm <= floor) return 0
    return (((dbm - floor).toFloat() / (ceil - floor)) * 4f + 0.5f).toInt().coerceIn(0, 4)
}

/** "Direct DTA 24-bit / 96kHz" → "24/96"; "16-bit / 44.1kHz" → "16/44". */
private fun compactQuality(format: String): String {
    val bits = Regex("(\\d{2})-?bit").find(format)?.groupValues?.get(1)
    val khz = Regex("(\\d{2,3})(?:\\.\\d)?\\s*kHz", RegexOption.IGNORE_CASE).find(format)?.groupValues?.get(1)
    return if (bits != null && khz != null) "$bits/$khz" else ""
}

/**
 * Collects the same live sources the quilt badges use (network service, volume manager,
 * BPM engine, WireGuard, Bluetooth profiles, battery sticky intent, now-playing Globals)
 * into a [MikuStatusBarState]. [clock] and [thermalC] come from the host screen.
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
    val bpmState by com.miku.launcher.bpm.MikuBpmEngine.state.collectAsState()
    val wg by com.miku.launcher.vpn.MikuWireGuardManager.status.collectAsState()
    var btConnected by remember { mutableStateOf(false) }
    // Read the REAL battery immediately (was defaulting to a fake 100 that showed until the
    // visibility-gated poll below first ran - "top bar stuck at 100%").
    val initialBattery = remember {
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (lvl >= 0 && scl > 0) lvl * 100 / scl else -1
        } catch (_: Throwable) { -1 }
    }
    // -1 is kept as the honest "could not read" sentinel (the old .coerceAtLeast(0) turned a failed
    // read into a confident, alarming "0%"). MikuStatusBar renders "—%" for a negative value.
    var batteryPct by remember { mutableIntStateOf(initialBattery) }
    var charging by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("") }
    var batteryTempC by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            // Battery first (cheap sticky read) so it is fresh the instant the bar is shown,
            // then gate the heavier polls on visibility.
            try {
                val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (bi != null) {
                    val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scl = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lvl >= 0 && scl > 0) batteryPct = lvl * 100 / scl
                    val st0 = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = st0 == BatteryManager.BATTERY_STATUS_CHARGING || st0 == BatteryManager.BATTERY_STATUS_FULL
                }
            } catch (_: Throwable) {}
            MikuPowerProfile.awaitVisible()
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
                    val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (t > 0) batteryTempC = t / 10f
                }
            } catch (_: Throwable) {}
            quality = try {
                compactQuality(android.provider.Settings.Global.getString(ctx.contentResolver, "miku_now_playing_format") ?: "")
            } catch (_: Throwable) { "" }
            delay(MikuPowerProfile.refreshMs(3000L))
        }
    }
    @Suppress("UNUSED_VARIABLE") val wgTick = wg  // recompose on tunnel status changes
    val vpnUp = try { com.miku.launcher.vpn.MikuWireGuardManager.isUp() } catch (_: Throwable) { false }
    val playing = isPlaying || bpmState.isPlaying
    return MikuStatusBarState(
        clock = clock,
        bpm = if (bpmState.bpm in 40f..260f) bpmState.bpm.toInt() else 0,
        isPlaying = playing,
        quality = if (playing) quality else "",
        wifiConnected = net.wifi.isConnected,
        wifiDbm = net.wifi.rssiDbm,
        wifiLevel = if (net.wifi.isConnected) levelFromDbm(net.wifi.rssiDbm, -90, -55) else 0,
        cellConnected = net.cellular.isConnected && net.cellular.hasSignal,
        // The modem's OWN level (SignalStrength.level, 0..4) is the real measurement. Deriving bars
        // from signalDbm was a fake-full-bars bug: signalDbm is 0 when the dBm was never reported
        // (pre-API-29, null SignalStrength, out-of-range cellSignalStrengths) and levelFromDbm(0, …)
        // returns 4 — painting a FULL signal icon for a reading that does not exist.
        cellLevel = if (net.cellular.hasSignal) net.cellular.signalLevel5.coerceIn(0, 4) else 0,
        cellType = net.cellular.networkType.substringBefore(' ').take(4),
        bluetoothConnected = btConnected,
        vpnUp = vpnUp,
        volumePct = vol.volumePct,
        volumeKnown = vol.maxVolume > 0,
        isMuted = vol.isMuted,
        thermalC = if (thermalC > 0f) thermalC else batteryTempC,
        batteryPct = batteryPct,
        isCharging = charging
    )
}

/** Thermal ladder shared with the thermal badge. */
fun thermalColor(c: Float): Color = when {
    c >= 55f -> com.miku.launcher.ui.MikuIdentity.Coral
    c >= 48f -> Color(0xFFFF8A65)
    c >= 40f -> com.miku.launcher.ui.MikuIdentity.Gold
    else -> MikuCyan
}

/**
 * MikuOS TOP BAR — one thin (30dp), standard-layout, data-verbose status bar. NOT a card.
 *   LEFT : HH:mm · "128♥" live BPM · "24/96" quality chip (while playing)
 *   RIGHT: 52° thermal · VOL 37 · Wi-Fi + dBm · cellular · BT · VPN · 68% + battery cell/bolt
 * Miku-styled (teal glyphs, stitched hairline seam, glow) with a stock bar's static behavior.
 * If the row cannot fit the available width, items drop in this order: VPN, dBm, quality, BPM.
 * Reusable so the SystemUI overlay can host the same bar system-wide.
 */
@Composable
fun MikuStatusBar(
    state: MikuStatusBarState,
    modifier: Modifier = Modifier,
    onClockClick: (() -> Unit)? = null
) {
    val glyph = 13.dp
    val ctxBar = androidx.compose.ui.platform.LocalContext.current
    // Album accent (25 % into teal) on the stitch seam + quality chip; pure teal when idle.
    val np = rememberNpAccent()
    val seam = np.subtle.copy(alpha = 0.55f)
    val chipColor = np.subtle
    val textColor = Color(0xFFE0FFFC)
    val profile by rememberPowerProfile()
    val profileGlyph = MikuPowerProfile.glyph(profile)
    // Colour crossfade only. The NUMBERS are printed raw: tweening them meant that on every
    // unknown -> known transition (-1 -> 68 %, 0 -> 42 °C) the bar spent ~250 ms displaying
    // readings that were never measured ("12%", "19°") as if they were live telemetry.
    val thermalShown = state.thermalC
    val volumeShown = state.volumePct
    val batteryShown = state.batteryPct
    val bpmShown = state.bpm
    val thermalTint = animatedColor(thermalColor(state.thermalC), "sbThermalColor")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(MikuDimens.statusBarHeight)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color(0x66040D12), Color(0x22040D12))))
                val stitch = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                val y = size.height - 1.dp.toPx()
                drawLine(seam, Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), 1.2.dp.toPx(), pathEffect = stitch)
            }
            .padding(horizontal = 16.dp)
    ) {
        // ---- fit pass: estimate widths (10.5sp condensed ≈ 6.2dp/char, glyph 13dp, gap 5dp) ----
        val avail = maxWidth.value
        var showVpn = state.vpnUp
        // dBm only when the radio actually reported one (-100 is the service's "unknown" floor).
        var showDbm = state.wifiConnected && state.wifiDbm > -100
        var showQuality = state.quality.isNotEmpty()
        var showBpm = state.bpm > 0 && state.isPlaying
        fun estimate(): Float {
            var w = state.clock.length * 7.2f + 6f
            if (showBpm) w += ("${state.bpm}♥".length) * 6.2f + 6f
            if (showQuality) w += state.quality.length * 6.2f + 10f
            var r = 0f
            r += "${state.thermalC.toInt()}°".length * 6.2f + 5f
            r += "VOL ${state.volumePct}".length * 6.2f + 5f
            r += 13f + 5f + (if (showDbm) "${state.wifiDbm}".length * 6.2f + 2f else 0f)
            r += 13f + 5f + (if (state.cellConnected && state.cellType.isNotEmpty()) state.cellType.length * 5.5f else 0f)
            if (state.bluetoothConnected) r += 13f + 5f
            if (showVpn) r += 13f + 5f
            r += "${state.batteryPct}%".length * 6.2f + 5f + 20f
            return w + 12f + r
        }
        if (estimate() > avail) showVpn = false
        if (estimate() > avail) showDbm = false
        if (estimate() > avail) showQuality = false
        if (estimate() > avail) showBpm = false

        Row(
            Modifier.fillMaxWidth().height(MikuDimens.statusBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // LEFT
            val clockInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = if (onClockClick != null) Modifier.clickable(interactionSource = clockInteraction, indication = null, onClick = onClockClick) else Modifier
            ) {
                Text(state.clock, color = textColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 0.3.sp, maxLines = 1, softWrap = false)
                if (showBpm) {
                    Text("${bpmShown}♥", color = MikuNeonPink, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                }
                if (showQuality) {
                    Box(
                        Modifier
                            .drawBehind {
                                drawRoundRect(chipColor.copy(alpha = 0.18f), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                                drawRoundRect(chipColor.copy(alpha = 0.6f), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()), style = Stroke(1f))
                            }
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(state.quality, color = MikuNowPlayingAccent.tintText(MikuCyan, np.full, Color(0xFF07131A)), fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                    }
                }
            }
            // RIGHT
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (state.thermalC > 0f) "${thermalShown.toInt()}°" else "—°", color = if (state.thermalC > 0f) thermalTint else MikuTextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.isMuted || state.volumePct == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Volume",
                        tint = if (state.isMuted) MikuNeonPink else MikuCyan,
                        modifier = Modifier.size(glyph)
                    )
                    Text(if (state.volumeKnown) "${volumeShown}" else "—", color = textColor, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = "Wi-Fi",
                        tint = if (state.wifiConnected) MikuCyan.copy(alpha = 0.45f + 0.55f * (state.wifiLevel / 4f)) else MikuTextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(glyph)
                    )
                    if (showDbm) Text("${state.wifiDbm}", color = MikuTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                if (state.cellConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SignalCellular4Bar, contentDescription = "Signal", tint = MikuCyan.copy(alpha = 0.45f + 0.55f * (state.cellLevel / 4f)), modifier = Modifier.size(glyph))
                        if (state.cellType.isNotEmpty()) Text(state.cellType, color = MikuTextSecondary, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                } else {
                    Icon(Icons.Default.SignalCellularConnectedNoInternet0Bar, contentDescription = "No signal", tint = MikuTextSecondary.copy(alpha = 0.45f), modifier = Modifier.size(glyph))
                }
                if (state.bluetoothConnected) Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth", tint = MikuCyan, modifier = Modifier.size(glyph))
                if (showVpn) Icon(Icons.Default.VpnLock, contentDescription = "VPN", tint = MikuCyan, modifier = Modifier.size(glyph))
                // batteryPct < 0 = the sticky ACTION_BATTERY_CHANGED could not be read. Show "—%" in a
                // neutral tint and an empty cell rather than a red "0%" that reads as an emergency.
                val batteryKnown = state.batteryPct >= 0
                val batteryColor = when {
                    !batteryKnown -> MikuTextSecondary
                    state.isCharging -> com.miku.launcher.ui.MikuIdentity.Leek
                    state.batteryPct > 50 -> MikuCyan
                    state.batteryPct > 20 -> com.miku.launcher.ui.MikuIdentity.Gold
                    else -> com.miku.launcher.ui.MikuIdentity.Coral
                }
                Text(
                    if (batteryKnown) "${batteryShown}%" else "—%",
                    color = if (batteryKnown) textColor else MikuTextSecondary,
                    fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1, softWrap = false
                )
                run {
                    Spacer(Modifier.width(5.dp))
                    // Power: mode glyph (⚡ perf / ☾ save / auto = live profile ♪ ☾ ⚡ ✦).
                    // Tap opens Miku Music's Power Governor; long-press cycles auto → perf → save.
                    val powerMode by rememberPowerMode()
                    val modeGlyph = MikuPowerProfile.modeGlyph(powerMode, profile)
                    val glyphColor = when {
                        powerMode == "perf" || profile == "perf" -> MikuIdentity.PinkNeon
                        powerMode == "save" -> MikuIdentity.Lavender
                        else -> textColor.copy(alpha = 0.85f)
                    }
                    Text(modeGlyph, color = glyphColor, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { MikuPowerProfile.openGovernor(ctxBar) },
                            onLongClick = {
                                val next = MikuPowerProfile.cycleMode(ctxBar)
                                com.miku.launcher.haptics.MikuHaptics.confirm(ctxBar)
                                android.widget.Toast.makeText(ctxBar, "Power mode: " + MikuPowerProfile.modeLabel(next, profile), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ))
                }
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.width(20.dp).height(11.dp)) {
                        val bodyW = size.width - 3.dp.toPx()
                        val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        drawRoundRect(color = batteryColor.copy(alpha = 0.85f), size = Size(bodyW, size.height), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
                        drawRoundRect(color = batteryColor.copy(alpha = 0.85f), topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f), cornerRadius = CornerRadius(1f, 1f))
                        val inset = 2.dp.toPx()
                        // No fill at all when the level is unknown (negative) — an empty cell, not 0 %.
                        val fillW = if (batteryKnown)
                            ((bodyW - inset * 2) * (state.batteryPct / 100f)).coerceAtLeast(0f) else 0f
                        drawRoundRect(color = batteryColor, topLeft = Offset(inset, inset), size = Size(fillW, size.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()))
                    }
                    if (state.isCharging) Icon(Icons.Default.Bolt, contentDescription = "Charging", tint = Color.White, modifier = Modifier.size(9.dp))
                }
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
