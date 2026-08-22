package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.miku.launcher.R

/**
 * Clock badge as an individual system App Widget. The layout's TextClock views tick on their own
 * (no polling, no service), so the provider only has to wire the tap target. Named
 * MikuLauncherClockWidget because the app module already ships a MikuClockWidget.
 */
class MikuLauncherClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuLauncherClockWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_clock)
            v.setOnClickPendingIntent(R.id.widget_clock_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
