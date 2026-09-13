package com.m500.hardware

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class UsbDacTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = UsbDacManager.isActive(applicationContext)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "USB DAC: ON" else "USB DAC: OFF"
        // Was "${'$'}{getSampleRate()/1000}kHz" - that is the SAVED PREFERENCE (default 192 kHz),
        // printed on an ON tile as if it were the rate the host had negotiated. The real rate
        // lives in /proc/asound and is shown on the USB DAC screen; the tile says "configured".
        tile.subtitle = if (active) "configured ${UsbDacManager.getSampleRate(applicationContext) / 1000}kHz" else "MTP / ADB"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val current = UsbDacManager.isActive(applicationContext)
        val next = !current
        scope.launch {
            UsbDacManager.setUsbDacMode(applicationContext, next)
            updateTile()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class PocketLockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val cr = contentResolver
        val mode = android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
        val active = mode == "touch_and_key_lock"
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Fn Pocket Lock"
        tile.subtitle = if (active) "Touch & Keys Locked" else "Key Lock Only"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val cr = contentResolver
        val mode = android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
        val next = if (mode == "touch_and_key_lock") "key_lock" else "touch_and_key_lock"
        android.provider.Settings.Global.putString(cr, "fn_settings", next)
        updateTile()
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class PulsarTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = PulsarLight.isEnabled(applicationContext)
        val mode = PulsarLight.getMode(applicationContext)
        // The tile lit up "active" with a mode name as if the diode were glowing. It is a stored
        // preference: on this unit the LED nodes are not writable, so say that instead.
        val drivable = PulsarLight.isHardwareWritable()
        tile.state = if (enabled && drivable) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Pulsar RGB"
        tile.subtitle = when {
            !drivable -> "LED inactive on this unit"
            enabled -> mode.label
            else -> "Off"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val modes = PulsarLight.Mode.values()
        val currentMode = PulsarLight.getMode(applicationContext)
        val nextIdx = (modes.indexOf(currentMode) + 1) % modes.size
        val nextMode = modes[nextIdx]

        scope.launch {
            if (nextMode == PulsarLight.Mode.OFF) {
                PulsarLight.setEnabled(applicationContext, false)
            } else {
                PulsarLight.setEnabled(applicationContext, true)
                PulsarLight.setMode(applicationContext, nextMode)
            }
            updateTile()
        }
    }
}
