package com.miku.systemui

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class QsTile(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val isActive: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null
)

object QuickSettingsModel {

    fun openMikuSettings(ctx: Context, section: String? = null) {
        try {
            val intent = Intent().setClassName("com.miku.settings", "com.miku.settings.MikuSettingsActivity").apply {
                if (section != null) putExtra("extra_section", section)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            try {
                val fallback = ctx.packageManager.getLaunchIntentForPackage("com.miku.settings")?.apply {
                    if (section != null) putExtra("extra_section", section)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (fallback != null) ctx.startActivity(fallback)
            } catch (_: Throwable) {}
        }
    }

    data class WifiState(val isEnabled: Boolean, val isAssociated: Boolean, val ssid: String, val level: Int?)

    fun getWifiState(ctx: Context): WifiState {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isEnabled = wm?.isWifiEnabled == true
        @Suppress("DEPRECATION")
        val info = wm?.connectionInfo
        // Associated (not just radio-on) — subtitle said "Connected" whenever the radio was on.
        val associated = isEnabled && (info?.networkId ?: -1) != -1
        val rawSsid = info?.ssid?.replace("\"", "") ?: ""
        val ssid = if (!associated || rawSsid.isBlank() || rawSsid == "<unknown ssid>") "Wi-Fi" else rawSsid
        // Signal level only when actually associated; the old code turned "no link" into -100 dBm → level 0.
        val level = if (associated) info?.rssi?.let { WifiManager.calculateSignalLevel(it, 5) } else null
        return WifiState(isEnabled, associated, ssid, level)
    }

    fun toggleWifi(ctx: Context, enable: Boolean) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { wm?.isWifiEnabled = enable }
        RootShell.execFast("svc wifi " + (if (enable) "enable" else "disable"))
    }

    fun getBluetoothInfo(ctx: Context): Pair<Boolean, String> {
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val isEnabled = runCatching { bt?.isEnabled == true }.getOrDefault(false)
        var connectedName = ""
        if (isEnabled && bt != null) {
            runCatching {
                // Real CONNECTED device (was showing any PAIRED device as "Active").
                val a2dp = bt.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)
                val hs = bt.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)
                if (a2dp == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                    hs == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    // Name of the device that is ACTUALLY connected (BluetoothDevice.isConnected,
                    // SystemApi via reflection). The old predicate never looked at the device, so
                    // it named whichever bonded device came first — often the wrong one.
                    connectedName = bt.bondedDevices?.firstOrNull { dev ->
                        runCatching { dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean }.getOrNull() == true
                    }?.let { runCatching { it.name }.getOrNull() } ?: "Connected"
                }
            }
        }
        val label = if (connectedName.isNotBlank()) connectedName else if (isEnabled) "Bluetooth On" else "Bluetooth Off"
        return Pair(isEnabled, label)
    }

    fun toggleBluetooth(ctx: Context, enable: Boolean) {
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        runCatching {
            if (enable) bt?.enable() else bt?.disable()
        }
        RootShell.execFast("svc bluetooth " + (if (enable) "enable" else "disable"))
    }

    fun getTiles(ctx: Context, scope: CoroutineScope, onRefresh: () -> Unit): List<QsTile> {
        val list = mutableListOf<QsTile>()

        // 1. Wi-Fi (Internet) — "Connected" only when actually associated with a network.
        val wifi = getWifiState(ctx)
        val isWifiOn = wifi.isEnabled
        list.add(
            QsTile(
                id = "wifi",
                label = if (wifi.isAssociated) wifi.ssid else if (isWifiOn) "Wi-Fi" else "Internet",
                subtitle = when {
                    !isWifiOn -> "Off"
                    wifi.isAssociated -> wifi.level?.let { "Connected · signal $it/4" } ?: "Connected"
                    else -> "On · not connected"
                },
                icon = if (isWifiOn) Icons.Default.Wifi else Icons.Default.WifiOff,
                isActive = isWifiOn,
                onClick = {
                    val next = !isWifiOn
                    toggleWifi(ctx, next)
                    onRefresh()
                },
                onLongClick = { openMikuSettings(ctx, "wireless") }
            )
        )

        // 2. Bluetooth
        val (isBtOn, btLabel) = getBluetoothInfo(ctx)
        list.add(
            QsTile(
                id = "bluetooth",
                label = if (isBtOn) btLabel else "Bluetooth",
                subtitle = if (isBtOn) "Active" else "Off",
                icon = if (isBtOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                isActive = isBtOn,
                onClick = {
                    val next = !isBtOn
                    toggleBluetooth(ctx, next)
                    onRefresh()
                },
                onLongClick = { openMikuSettings(ctx, "bluetooth") }
            )
        )

        // 3. Cirrus CS43198 Filter — state comes from sysfs / Settings.Global; "unknown" when neither
        //    answers (the tile used to be hard-wired isActive = true and defaulted to Fast Linear).
        val currentFilter = CirrusLogicManager.getDigitalFilter(ctx)
        list.add(
            QsTile(
                id = "cs43198_filter",
                label = "DAC Filter",
                subtitle = when (currentFilter) {
                    CirrusLogicManager.DigitalFilter.FAST_LINEAR -> "Fast Linear"
                    CirrusLogicManager.DigitalFilter.FAST_MINIMUM -> "Fast Min"
                    CirrusLogicManager.DigitalFilter.SLOW_LINEAR -> "Slow Linear"
                    CirrusLogicManager.DigitalFilter.SLOW_MINIMUM -> "Slow Min"
                    CirrusLogicManager.DigitalFilter.NOS -> "NOS (Raw)"
                    null -> "Unknown — tap to set"
                },
                icon = Icons.Default.GraphicEq,
                isActive = currentFilter != null,
                onClick = {
                    val all = CirrusLogicManager.DigitalFilter.values()
                    val nextIdx = if (currentFilter == null) 0 else (currentFilter.ordinal + 1) % all.size
                    val nextFilter = all[nextIdx]
                    scope.launch {
                        CirrusLogicManager.setDigitalFilter(ctx, nextFilter)
                        onRefresh()
                    }
                },
                onLongClick = { openMikuSettings(ctx, "audio_dac") }
            )
        )

        // 4. Headphone Gain (PO Gain)
        val currentGain = CirrusLogicManager.getGainMode(ctx)
        val isHighGain = currentGain == CirrusLogicManager.GainMode.HIGH
        list.add(
            QsTile(
                id = "cs43198_gain",
                label = "PO Gain",
                subtitle = when (currentGain) {
                    CirrusLogicManager.GainMode.HIGH -> "High (+6 dB)"
                    CirrusLogicManager.GainMode.LOW -> "Low (0 dB)"
                    null -> "Unknown — tap to set"
                },
                icon = Icons.Default.VolumeUp,
                isActive = isHighGain,
                onClick = {
                    val next = if (isHighGain) CirrusLogicManager.GainMode.LOW else CirrusLogicManager.GainMode.HIGH
                    scope.launch {
                        CirrusLogicManager.setGainMode(ctx, next)
                        onRefresh()
                    }
                },
                onLongClick = { openMikuSettings(ctx, "audio_dac") }
            )
        )

        // 5. Audio Turbo High Power
        val isTurbo = CirrusLogicManager.isHighPowerEnabled(ctx)
        list.add(
            QsTile(
                id = "audio_turbo",
                label = "Audio Turbo",
                subtitle = if (isTurbo) "High Rails" else "Standard",
                icon = Icons.Default.FlashOn,
                isActive = isTurbo,
                onClick = {
                    scope.launch {
                        CirrusLogicManager.setHighPowerEnabled(ctx, !isTurbo)
                        onRefresh()
                    }
                },
                onLongClick = { openMikuSettings(ctx, "audio_dac") }
            )
        )

        // 6. Dynamic Range Enhancement (DRE)
        val isDre = CirrusLogicManager.isDreEnabled(ctx)
        list.add(
            QsTile(
                id = "dre_mode",
                label = "DRE 130dB+",
                subtitle = if (isDre) "Active" else "Off",
                icon = Icons.Default.Tune,
                isActive = isDre,
                onClick = {
                    scope.launch {
                        CirrusLogicManager.setDreEnabled(ctx, !isDre)
                        onRefresh()
                    }
                },
                onLongClick = { openMikuSettings(ctx, "audio_dac") }
            )
        )

        // 7. Pulsar Dual-Die RGB - the tile used to light up "active" with the mode name as if
        //    the diode were glowing. It is only a stored preference: the OS publishes whether the
        //    LED can be driven at all (Settings.Global miku_pulsar_hw_writable) and on this unit
        //    it cannot, so the tile says so instead of implying a lit indicator.
        val pulsarLedDrivable = PulsarLight.isHardwareWritable(ctx)
        val isPulsarActive = pulsarLedDrivable && PulsarLight.getMode(ctx) != PulsarLight.Mode.OFF
        list.add(
            QsTile(
                id = "pulsar_light",
                label = "Pulsar RGB",
                subtitle = if (!pulsarLedDrivable) "LED inactive on this unit"
                           else PulsarLight.getMode(ctx).label,
                icon = Icons.Default.Lightbulb,
                isActive = isPulsarActive,
                onClick = {
                    val all = PulsarLight.Mode.values()
                    val cur = PulsarLight.getMode(ctx)
                    val next = all[(cur.ordinal + 1) % all.size]
                    PulsarLight.setMode(ctx, next)
                    onRefresh()
                },
                onLongClick = { openMikuSettings(ctx, "pulsar") }
            )
        )

        // 8. Wireless ADB — the port shown is the one adbd is really bound to (service.adb.tcp.port)
        val adbPort = WirelessAdbManager.currentPort()
        val isAdb = adbPort != null
        list.add(
            QsTile(
                id = "wireless_adb",
                label = "Wireless ADB",
                subtitle = if (adbPort != null) "Port $adbPort" else "Off",
                icon = Icons.Default.Cable,
                isActive = isAdb,
                onClick = {
                    scope.launch {
                        WirelessAdbManager.setEnabled(!isAdb)
                        onRefresh()
                    }
                },
                onLongClick = { openMikuSettings(ctx, "system_about") }
            )
        )

        // 9. Airplane Mode
        val isAir = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        list.add(
            QsTile(
                id = "airplane_mode",
                label = "Airplane Mode",
                subtitle = if (isAir) "On" else "Off",
                icon = Icons.Default.Flight,
                isActive = isAir,
                onClick = {
                    val next = if (isAir) 0 else 1
                    Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, next)
                    RootShell.execFast("settings put global airplane_mode_on $next; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + (next == 1))
                    onRefresh()
                },
                onLongClick = { openMikuSettings(ctx, "wireless") }
            )
        )

        return list
    }
}
