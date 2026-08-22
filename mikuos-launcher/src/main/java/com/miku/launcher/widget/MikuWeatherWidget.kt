package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.miku.launcher.R
import com.miku.launcher.weather.MikuWeatherService
import kotlin.math.roundToInt

/**
 * Current weather as an individual system App Widget. Widgets render in the launcher process, so
 * the render is a synchronous read of MikuWeatherService.state.value — no fetch of its own.
 * Refreshed via [pushUpdate] from the service's fetch cycle; until the first successful fetch
 * (lastUpdatedTime == 0) it shows an awaiting-data placeholder instead of default values.
 */
class MikuWeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuWeatherWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_weather)

            val w = MikuWeatherService.state.value.weather
            val fresh = w.lastUpdatedTime > 0L
            v.setTextViewText(R.id.widget_weather_icon, if (fresh) w.icon else "🌐")
            v.setTextViewText(
                R.id.widget_weather_temp,
                if (fresh) "${w.tempF.roundToInt()}°F" else "--°"
            )
            v.setTextViewText(
                R.id.widget_weather_cond,
                if (fresh) w.summary else "AWAITING DATA"
            )
            // Severe conditions flip the temp readout red, matching the in-bar badge's urgency cue.
            v.setTextColor(
                R.id.widget_weather_temp,
                if (fresh && w.severeWarning != null) 0xFFFF5252.toInt() else 0xFF39C5BB.toInt()
            )

            v.setOnClickPendingIntent(R.id.widget_weather_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
