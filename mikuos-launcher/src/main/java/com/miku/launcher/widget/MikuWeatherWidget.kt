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
 * Refreshed via [pushUpdate] from the service's fetch cycle. On a cold start it seeds from the
 * persisted last forecast; only when no fetch has EVER succeeded does it show an awaiting-data
 * placeholder instead of default values.
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

            // The widget can render in a freshly-started launcher process before the service's
            // start() runs — seed from the persisted last forecast so it's never blank when one
            // exists. No-op if state already holds a fresher condition.
            if (MikuWeatherService.state.value.weather.lastUpdatedTime == 0L) {
                runCatching { MikuWeatherService.restoreLastWeather(ctx.applicationContext) }
            }
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
