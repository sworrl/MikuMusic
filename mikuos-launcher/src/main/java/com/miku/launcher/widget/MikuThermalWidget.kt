package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.RemoteViews
import com.miku.launcher.R
import java.io.File

/**
 * CPU + battery temperature as an individual system App Widget. Live on-render read: CPU from the
 * same thermal sysfs zones the launcher's telemetry poller uses, battery from the sticky
 * ACTION_BATTERY_CHANGED broadcast (EXTRA_TEMPERATURE / 10). Same cross-fallbacks as the poller
 * so a locked-down zone never shows 0°C.
 */
class MikuThermalWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        // Same zone list as MikuLauncherActivity's thermal telemetry poller.
        private val THERMAL_PATHS = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )

        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuThermalWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_thermal)

            var cpu = 0f
            for (p in THERMAL_PATHS) {
                try {
                    val file = File(p)
                    if (file.exists() && file.canRead()) {
                        val raw = file.readText().trim().toFloatOrNull() ?: 0f
                        val deg = if (raw > 1000f) raw / 1000f else raw
                        if (deg in 15f..115f) {
                            cpu = deg
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }

            var bat = 0f
            try {
                val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val rawB = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                if (rawB > 0) bat = rawB / 10f
            } catch (_: Throwable) {}

            if (cpu == 0f) cpu = if (bat > 0f) bat + 4.5f else 36.5f
            if (bat == 0f) bat = cpu - 4.0f

            // Teal nominal, amber warm, red hot — keyed to the hotter of the two sensors.
            val maxTemp = maxOf(cpu, bat)
            val tint = when {
                maxTemp >= 60f -> 0xFFFF5252.toInt()
                maxTemp >= 45f -> 0xFFFFD600.toInt()
                else -> 0xFF39C5BB.toInt()
            }
            v.setTextViewText(R.id.widget_thermal_cpu, "${cpu.toInt()}°C")
            v.setTextColor(R.id.widget_thermal_cpu, tint)
            v.setTextViewText(R.id.widget_thermal_bat, "BAT ${bat.toInt()}°C")

            v.setOnClickPendingIntent(R.id.widget_thermal_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
