package com.miku.player

import org.json.JSONArray
import org.json.JSONObject

/** How an alarm decides when to fire. CLOCK_TIME uses hour/minute directly; the SUN_* modes derive
 *  a fresh trigger time each day from [[SunCalc]] using the last cached coarse location. */
enum class AlarmTriggerMode { CLOCK_TIME, SUNRISE, SUNSET, SUNRISE_OR_SUNSET }

/** What plays when the alarm fires. */
enum class AlarmSource { SHUFFLE_ALL, LIKED_SONGS, DAILY_MIX, ARTIST, ALBUM }

/**
 * One user-configured alarm. `repeatDays` uses [java.util.Calendar] DAY_OF_WEEK values (1=Sunday
 * .. 7=Saturday) directly — no translation layer needed against Calendar-based scheduling math in
 * [[AlarmScheduler]]. An empty set means "one-shot, next matching time only," matching how every
 * mainstream alarm clock treats an unrepeated alarm (fires once, disables itself).
 */
data class Alarm(
    val id: Int,
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val mode: AlarmTriggerMode = AlarmTriggerMode.CLOCK_TIME,
    val repeatDays: Set<Int> = emptySet(),
    val label: String = "Alarm",
    val fadeInSeconds: Int = 30,
    val snoozeMinutes: Int = 9,
    val source: AlarmSource = AlarmSource.SHUFFLE_ALL,
    val sourceRef: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("enabled", enabled); put("hour", hour); put("minute", minute)
        put("mode", mode.name); put("repeatDays", JSONArray(repeatDays.toList()))
        put("label", label); put("fadeInSeconds", fadeInSeconds); put("snoozeMinutes", snoozeMinutes)
        put("source", source.name); put("sourceRef", sourceRef ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val days = mutableSetOf<Int>()
            val arr = o.optJSONArray("repeatDays")
            if (arr != null) for (i in 0 until arr.length()) days.add(arr.getInt(i))
            return Alarm(
                id = o.getInt("id"),
                enabled = o.optBoolean("enabled", true),
                hour = o.optInt("hour", 7),
                minute = o.optInt("minute", 0),
                mode = runCatching { AlarmTriggerMode.valueOf(o.optString("mode", "CLOCK_TIME")) }.getOrDefault(AlarmTriggerMode.CLOCK_TIME),
                repeatDays = days,
                label = o.optString("label", "Alarm"),
                fadeInSeconds = o.optInt("fadeInSeconds", 30),
                snoozeMinutes = o.optInt("snoozeMinutes", 9),
                source = runCatching { AlarmSource.valueOf(o.optString("source", "SHUFFLE_ALL")) }.getOrDefault(AlarmSource.SHUFFLE_ALL),
                sourceRef = o.optString("sourceRef", "").takeIf { it.isNotBlank() }
            )
        }
    }
}
