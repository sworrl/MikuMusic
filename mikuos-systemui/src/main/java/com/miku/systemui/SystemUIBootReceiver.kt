package com.miku.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class SystemUIBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("MikuOS_SystemUI", "MikuOS SystemUI BootReceiver triggered - enabling gesture nav & shade")
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val serviceName = "com.miku.systemui/.MikuNotificationShadeService"
            if (!current.contains(serviceName)) {
                val updated = if (current.isEmpty()) serviceName else "$current:$serviceName"
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (_: Throwable) {}
        // The shade's notification list + media card need our NotificationListenerService.
        MikuNotificationStore.ensureEnabled(context)
    }
}

