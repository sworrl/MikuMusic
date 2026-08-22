package com.miku.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules/cancels alarms via `AlarmManager.setAlarmClock` — the strongest wake guarantee
 * Android offers a third-party app (fully Doze/battery-restriction exempt, guaranteed exact,
 * shows the platform's own alarm-clock status bar icon so the user can see it's really armed).
 * Requires SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM, both declared in the manifest.
 *
 * Each `Alarm` is one-shot at the OS level even when it repeats at the app level: `setAlarmClock`
 * fires once, so [[AlarmReceiver]] re-arms the same alarm's NEXT occurrence immediately after it
 * fires (or after a snooze), and [[AlarmBootReceiver]] re-arms everything after a reboot (exact
 * alarms do not survive a reboot).
 */
object AlarmScheduler {
    fun rescheduleAll(ctx: Context) {
        AlarmLocation.refreshIfStale(ctx)
        AlarmPreferences.loadAlarms(ctx).forEach { if (it.enabled) scheduleNext(ctx, it) else cancel(ctx, it.id) }
    }

    fun scheduleNext(ctx: Context, alarm: Alarm) {
        val triggerAt = nextTriggerMillis(ctx, alarm) ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) return
        val fireIntent = PendingIntent.getBroadcast(
            ctx, alarmRequestCode(alarm.id),
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val showIntent = PendingIntent.getActivity(
            ctx, alarmRequestCode(alarm.id) + 100_000,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), fireIntent)
    }

    fun scheduleSnooze(ctx: Context, alarm: Alarm) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) return
        val triggerAt = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        val fireIntent = PendingIntent.getBroadcast(
            ctx, alarmRequestCode(alarm.id),
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val showIntent = PendingIntent.getActivity(
            ctx, alarmRequestCode(alarm.id) + 100_000,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        AlarmPreferences.saveActiveSnooze(ctx, alarm.id)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), fireIntent)
    }

    fun cancel(ctx: Context, alarmId: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, alarmRequestCode(alarmId),
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
    }

    private fun alarmRequestCode(alarmId: Int) = 42_000 + alarmId

    /** Next trigger time strictly after now, honoring repeatDays for every mode (a one-shot
     *  clock-time alarm with an empty repeat set just returns tomorrow's occurrence if today's
     *  has already passed — standard "it already rang, wait for tomorrow" alarm-clock behavior). */
    fun nextTriggerMillis(ctx: Context, alarm: Alarm): Long? = when (alarm.mode) {
        AlarmTriggerMode.CLOCK_TIME -> nextClockTime(alarm.hour, alarm.minute, alarm.repeatDays)
        else -> nextSunEvent(ctx, alarm.mode, alarm.repeatDays)
    }

    private fun nextClockTime(hour: Int, minute: Int, repeatDays: Set<Int>): Long {
        val now = System.currentTimeMillis()
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (repeatDays.isEmpty()) {
            if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (offset in 0..7) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) in repeatDays && c.timeInMillis > now) return c.timeInMillis
        }
        return base.timeInMillis + 7L * 24 * 3_600_000L
    }

    private fun nextSunEvent(ctx: Context, mode: AlarmTriggerMode, repeatDays: Set<Int>): Long? {
        val (lat, lon) = AlarmPreferences.loadCachedLocation(ctx) ?: return null
        val now = System.currentTimeMillis()
        for (offset in 0..8) {
            val dayStart = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (repeatDays.isNotEmpty() && dayStart.get(Calendar.DAY_OF_WEEK) !in repeatDays) continue
            val (sunrise, sunset) = SunCalc.sunriseSunset(dayStart, lat, lon) ?: continue
            val candidates = when (mode) {
                AlarmTriggerMode.SUNRISE -> listOf(sunrise)
                AlarmTriggerMode.SUNSET -> listOf(sunset)
                AlarmTriggerMode.SUNRISE_OR_SUNSET -> listOf(sunrise, sunset)
                AlarmTriggerMode.CLOCK_TIME -> emptyList()
            }.filter { it > now }.sorted()
            if (candidates.isNotEmpty()) return candidates.first()
        }
        return null
    }
}
