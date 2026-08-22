package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import com.miku.launcher.R

/**
 * Combined BPM widget: live now-playing tempo (MikuBpmWidget's read) on top, the tap-tempo orb +
 * tapped readout (MikuBpmTapperWidget's session, shared via its SharedPreferences store) below.
 * Taps broadcast ACTION_TAP back to this provider; the shared handler re-renders the whole
 * BPM widget family.
 */
class MikuBpmComboWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MikuBpmTapperWidget.ACTION_TAP) {
            MikuBpmTapperWidget.registerTap(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        private var lastPushMs = 0L

        /** Throttled like MikuBpmWidget — the live half also refreshes on per-beat pulses. */
        fun pushUpdate(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushMs < 1000L) return
            lastPushMs = now
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuBpmComboWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_bpm_combo)

            // ---- Live now-playing half ---------------------------------------------------
            val cr = ctx.contentResolver
            val title = try { Settings.Global.getString(cr, "miku_now_playing_title") ?: "" } catch (_: Throwable) { "" }
            val playing = try { Settings.Global.getInt(cr, "miku_is_playing", 0) == 1 } catch (_: Throwable) { false }
            val intervalMs = try { Settings.Global.getInt(cr, "miku_beat_interval_ms", 0) } catch (_: Throwable) { 0 }
            val liveBpm = if (intervalMs > 0) 60_000 / intervalMs else 0

            v.setTextViewText(R.id.widget_combo_title, title.ifEmpty { "MIKU MUSIC" })
            v.setTextViewText(
                R.id.widget_combo_live,
                if (liveBpm > 0) (if (playing) "♪ $liveBpm BPM" else "❚❚ $liveBpm BPM") else "— BPM"
            )
            v.setTextColor(
                R.id.widget_combo_live,
                if (playing) 0xFFFF4FA3.toInt() else 0xFF9FF3EC.toInt()
            )

            // ---- Tapper half (shared session store) ---------------------------------------
            val (tapBpm, taps) = MikuBpmTapperWidget.tapSnapshot(ctx)
            v.setTextViewText(
                R.id.widget_combo_tapped,
                if (tapBpm > 0f) "TAP ${tapBpm.toInt()} BPM" else "TAP — BPM"
            )
            v.setTextViewText(
                R.id.widget_combo_count,
                if (taps > 0) "$taps TAP${if (taps == 1) "" else "S"}" else "TAP IN"
            )

            v.setOnClickPendingIntent(
                R.id.widget_combo_tap_btn,
                MikuBpmTapperWidget.tapIntent(ctx, MikuBpmComboWidget::class.java, 7012)
            )
            v.setOnClickPendingIntent(R.id.widget_combo_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
