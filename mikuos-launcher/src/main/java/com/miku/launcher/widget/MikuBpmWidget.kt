package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import com.miku.launcher.R

/**
 * Now-playing + live BPM as an individual system App Widget (display only). Reads the
 * Settings.Global keys the player publishes (miku_now_playing_title/_artist, miku_is_playing,
 * miku_beat_interval_ms) on render — no service dependency. Refreshed live via [pushUpdate] from
 * MikuBpmEngine's state emissions; throttled because beat pulses arrive every beat.
 */
class MikuBpmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        private var lastPushMs = 0L

        /** Re-render all live instances. Cheap + throttled so per-beat pulses can call it. */
        fun pushUpdate(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushMs < 1000L) return
            lastPushMs = now
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuBpmWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_bpm)

            val cr = ctx.contentResolver
            val title = try { Settings.Global.getString(cr, "miku_now_playing_title") ?: "" } catch (_: Throwable) { "" }
            val artist = try { Settings.Global.getString(cr, "miku_now_playing_artist") ?: "" } catch (_: Throwable) { "" }
            val playing = try { Settings.Global.getInt(cr, "miku_is_playing", 0) == 1 } catch (_: Throwable) { false }
            val intervalMs = try { Settings.Global.getInt(cr, "miku_beat_interval_ms", 0) } catch (_: Throwable) { 0 }
            val bpm = if (intervalMs > 0) 60_000 / intervalMs else 0

            val line1 = when {
                title.isNotEmpty() && artist.isNotEmpty() -> "$title — $artist"
                title.isNotEmpty() -> title
                else -> "MIKU MUSIC"
            }
            v.setTextViewText(R.id.widget_bpm_title, line1)
            v.setTextViewText(
                R.id.widget_bpm_value,
                if (bpm > 0) (if (playing) "♪ $bpm BPM" else "❚❚ $bpm BPM") else "— BPM"
            )
            v.setTextColor(
                R.id.widget_bpm_value,
                if (playing) 0xFFFF4FA3.toInt() else 0xFF9FF3EC.toInt()
            )

            v.setOnClickPendingIntent(R.id.widget_bpm_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
