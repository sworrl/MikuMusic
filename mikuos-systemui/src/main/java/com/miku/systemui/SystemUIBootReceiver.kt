package com.miku.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SystemUIBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("MikuOS_SystemUI", "MikuOS SystemUI BootReceiver triggered")
    }
}
