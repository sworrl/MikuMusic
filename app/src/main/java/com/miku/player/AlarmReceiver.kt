package com.miku.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager (see [[AlarmScheduler]]) at the exact trigger instant. Does the minimum
 *  possible here — hand off to the foreground [[AlarmRingService]] immediately — since a
 *  BroadcastReceiver only gets a few seconds before the OS may kill it. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        if (alarmId < 0) return
        val svc = Intent(context, AlarmRingService::class.java)
            .setAction(AlarmRingService.ACTION_RING)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        ContextCompat.startForegroundService(context, svc)
    }

    companion object {
        const val ACTION_FIRE = "com.miku.player.alarm.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

/** Exact alarms don't survive a reboot — this re-arms every enabled alarm's next occurrence the
 *  moment the device finishes booting. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AlarmScheduler.rescheduleAll(context)
    }
}
