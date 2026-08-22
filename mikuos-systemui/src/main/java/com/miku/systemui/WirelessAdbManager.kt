package com.miku.systemui

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

object WirelessAdbManager {
    private const val DEFAULT_PORT = 5555

    fun isEnabled(): Boolean {
        val port = RootShell.execOut("getprop service.adb.tcp.port")?.trim() ?: "-1"
        return port == "$DEFAULT_PORT" || (port.toIntOrNull() ?: -1) > 0
    }

    suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val port = if (enabled) "$DEFAULT_PORT" else "-1"
        RootShell.execFast("setprop service.adb.tcp.port $port; stop adbd; start adbd")
        true
    }

    fun getWifiIpAddress(context: Context): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback && (iface.name.startsWith("wlan") || iface.name.startsWith("eth"))) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
}
