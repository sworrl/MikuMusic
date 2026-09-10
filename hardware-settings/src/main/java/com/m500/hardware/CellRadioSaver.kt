package com.m500.hardware

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cell radio saver for the data-only Google Fi SIM (hosted in the always-alive hardware daemon).
 *
 * The SIM is T-Mobile-network-only; wherever T-Mobile has no usable signal the modem hunts for a
 * network forever (constant OUT_OF_SERVICE scanning + *telephony-radio* wakelocks) - a large,
 * pointless idle drain whenever the device sits at home on WiFi. This watcher:
 *   - powers the CELL RADIO OFF after the SIM has been out-of-service for [OOS_GRACE_MS] while
 *     WiFi is connected (so connectivity is never lost by the action), and
 *   - powers it straight back ON when WiFi disconnects, or when the feature is toggled off.
 * Only a radio *we* turned off is ever turned back on (flag in Settings.Global), so a user's own
 * airplane-mode/radio choices are never overridden. Toggle: Settings.Global m500_cell_radio_saver
 * (default ON), surfaced in MikuSettings -> Wireless -> Google Fi card.
 *
 * setRadioPower is @SystemApi (MODIFY_PHONE_STATE, granted via the platform key) - reflection.
 */
object CellRadioSaver {
    private const val TAG = "CellRadioSaver"
    const val KEY_ENABLED = "m500_cell_radio_saver"          // 1 = on (default)
    private const val KEY_WE_TURNED_OFF = "m500_cell_radio_saver_off"
    private const val OOS_GRACE_MS = 10 * 60_000L
    private const val POLL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    @Volatile private var wifiUp = false
    @Volatile private var oosSinceMs = 0L
    private var loop: Job? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { wifiUp = true }
                    override fun onLost(network: Network) {
                        wifiUp = false
                        // WiFi gone: restore the radio immediately if we were the ones who cut it.
                        scope.launch { restoreIfWeTurnedOff(app, "wifi lost") }
                    }
                })
        } catch (t: Throwable) { Log.w(TAG, "wifi callback failed", t) }

        loop = scope.launch {
            while (true) {
                try { tick(app) } catch (t: Throwable) { Log.w(TAG, "tick failed", t) }
                delay(POLL_MS)
            }
        }
        Log.i(TAG, "started (enabled=${isEnabled(app)})")
    }

    fun isEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_ENABLED, 1) == 1 }.getOrDefault(true)

    private fun weTurnedOff(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_WE_TURNED_OFF, 0) == 1 }.getOrDefault(false)

    private fun tick(ctx: Context) {
        if (!isEnabled(ctx)) { runCatching { restoreIfWeTurnedOff(ctx, "feature disabled") }; return }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (weTurnedOff(ctx)) return                     // already saved; wifi-loss callback restores
        if (!wifiUp) { oosSinceMs = 0L; return }         // never cut the radio without WiFi

        val ss: ServiceState? = runCatching { tm.serviceState }.getOrNull()
        // Data-only SIM: voice reg can be OOS forever while data works, so check data paths too.
        // getDataRegState is hidden - reflection with a safe fallback.
        val dataReg = runCatching {
            ServiceState::class.java.getMethod("getDataRegState").invoke(ss) as? Int
        }.getOrNull()
        val inService = ss != null && (
            ss.state == ServiceState.STATE_IN_SERVICE ||
            dataReg == ServiceState.STATE_IN_SERVICE ||
            runCatching { tm.dataState == TelephonyManager.DATA_CONNECTED }.getOrDefault(false))
        if (inService) { oosSinceMs = 0L; return }       // real signal - leave the radio alone

        val now = System.currentTimeMillis()
        if (oosSinceMs == 0L) { oosSinceMs = now; return }
        if (now - oosSinceMs < OOS_GRACE_MS) return

        if (setRadioPower(tm, false)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_WE_TURNED_OFF, 1) }
            Log.i(TAG, "cell radio OFF (no service ${((now - oosSinceMs) / 60000)}min, WiFi up)")
        }
    }

    fun restoreIfWeTurnedOff(ctx: Context, why: String) {
        if (!weTurnedOff(ctx)) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (setRadioPower(tm, true)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_WE_TURNED_OFF, 0) }
            oosSinceMs = 0L
            Log.i(TAG, "cell radio ON ($why)")
        }
    }

    private fun setRadioPower(tm: TelephonyManager, on: Boolean): Boolean = try {
        TelephonyManager::class.java.getMethod("setRadioPower", Boolean::class.javaPrimitiveType)
            .invoke(tm, on)
        true
    } catch (t: Throwable) { Log.w(TAG, "setRadioPower($on) failed", t); false }
}
