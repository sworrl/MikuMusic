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
        // Read the real port via SystemProperties (reflection) — readable without root; the old
        // RootShell getprop returned null on this no-root OS so the tile was permanently "Off".
        val port = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tcp.port") as? String
        } catch (_: Throwable) { null }?.trim() ?: "-1"
        return (port.toIntOrNull() ?: -1) > 0
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
