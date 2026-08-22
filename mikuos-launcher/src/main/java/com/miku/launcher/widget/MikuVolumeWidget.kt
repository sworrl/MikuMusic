package com.miku.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.widget.RemoteViews
import com.miku.launcher.R

/**
 * STREAM_MUSIC volume as an individual system App Widget. Live on-render read from AudioManager;
 * refreshed via [pushUpdate] from MikuVolumeManager on every volume change (lightly throttled —
 * knob/rotary spins fire updates in bursts). The volume HUD is an in-launcher Compose overlay
 * with no external trigger intent, so the tap target is the launcher itself.
 */
class MikuVolumeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        private var lastPushMs = 0L

        fun pushUpdate(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushMs < 300L) return
            lastPushMs = now
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MikuVolumeWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_miku_volume)

            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val max = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
            val pct = ((cur * 100) / max).coerceIn(0, 100)
            val muted = cur == 0

            // Follows the spectral volume gradient's mood: teal low, pink hot, red danger/mute.
            val tint = when {
                muted -> 0xFFFF5252.toInt()
                pct >= 80 -> 0xFFFF5252.toInt()
                pct >= 50 -> 0xFFFF4FA3.toInt()
                else -> 0xFF39C5BB.toInt()
            }
            v.setTextViewText(R.id.widget_volume_icon, if (muted) "🔇" else "🔊")
            v.setTextViewText(R.id.widget_volume_text, if (muted) "MUTE" else "VOL $pct%")
            v.setTextColor(R.id.widget_volume_text, tint)

            v.setOnClickPendingIntent(R.id.widget_volume_root, MikuBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
