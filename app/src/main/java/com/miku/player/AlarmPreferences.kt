package com.miku.player

import android.content.Context
import org.json.JSONArray

/** Plain (non-encrypted — nothing sensitive here) storage for the alarm list and the coarse
 *  location cache the sunrise/sunset trigger modes read from. Follows the same
 *  `prefs(context).edit()...apply()` shape as [[PlayerPreferences]]. */
object AlarmPreferences {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("miku_alarms", Context.MODE_PRIVATE)

    fun loadAlarms(context: Context): List<Alarm> {
        val raw = prefs(context).getString("alarms", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveAlarms(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        alarms.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString("alarms", arr.toString()).apply()
    }

    // Atomic-against-disk read-modify-write helpers — always start from a FRESH loadAlarms(), not
    // a caller-held snapshot. AlarmsCard (Settings UI) used to hold its own `remember`ed copy and
    // blindly overwrite the whole list on every edit; if AlarmRingService concurrently flipped a
    // one-shot alarm's `enabled=false` after it fired (see dismiss()) while Settings happened to be
    // open, the next unrelated Settings edit would clobber that write and silently re-enable/
    // reschedule an alarm that had just legitimately turned itself off. @Synchronized covers the
    // same-process race (Settings UI + AlarmRingService both run in this app's process); it doesn't
    // make SharedPreferences cross-process safe, but nothing else here needs to be.
    @Synchronized
    fun updateAlarm(context: Context, id: Int, transform: (Alarm) -> Alarm) {
        saveAlarms(context, loadAlarms(context).map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun upsertAlarm(context: Context, alarm: Alarm) {
        val current = loadAlarms(context)
        saveAlarms(context, if (current.any { it.id == alarm.id }) current.map { if (it.id == alarm.id) alarm else it } else current + alarm)
    }

    @Synchronized
    fun removeAlarm(context: Context, id: Int) {
        saveAlarms(context, loadAlarms(context).filterNot { it.id == id })
    }

    fun nextAlarmId(context: Context): Int {
        val existing = loadAlarms(context).map { it.id }
        var id = 1
        while (id in existing) id++
        return id
    }

    // --- Coarse location cache for sunrise/sunset math (SunCalc) — refreshed at most ~once/day
    // by AlarmLocation, from LocationManager.getLastKnownLocation only (no active GPS fix ever
    // triggered by this app), per the same battery-conscious rule already scoped for the
    // listening-stats feature (see memory m500-scrobble-location-todo).
    fun saveCachedLocation(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putFloat("loc_lat", lat.toFloat())
            .putFloat("loc_lon", lon.toFloat())
            .putLong("loc_at", System.currentTimeMillis())
            .apply()
    }

    fun loadCachedLocation(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains("loc_lat")) return null
        return p.getFloat("loc_lat", 0f).toDouble() to p.getFloat("loc_lon", 0f).toDouble()
    }

    fun loadLocationTimestamp(context: Context): Long = prefs(context).getLong("loc_at", 0L)

    // --- Snooze bookkeeping: which alarm id (if any) currently has a pending snooze fire, so the
    // ring UI/service can tell "this firing is a snooze" apart from "this is the real alarm."
    fun saveActiveSnooze(context: Context, alarmId: Int?) {
        prefs(context).edit().apply {
            if (alarmId == null) remove("snooze_active_id") else putInt("snooze_active_id", alarmId)
        }.apply()
    }
    fun loadActiveSnooze(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains("snooze_active_id")) p.getInt("snooze_active_id", -1).takeIf { it >= 0 } else null
    }
}
