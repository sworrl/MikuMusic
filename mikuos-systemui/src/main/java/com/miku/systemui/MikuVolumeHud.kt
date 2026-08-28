package com.miku.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Right-edge cyber volume bar drawn in the accessibility overlay layer, so volume changes are
 * visible over ANY app (Spotify, games, …) — the launcher's Compose HUD only exists inside
 * launcher windows. Mirrors the launcher's RIGHT_CYBER_BAR look: thin neon track hugging the
 * right edge, filled by volume %, percentage chip at the top of the bar.
 *
 * Suppressed while: screen off, keyguard locked, MikuOS lockscreen/AOD, or one of OUR surfaces
 * (launcher / shade / recents) is on top — those draw their own volume UI.
 * Gated by Settings.Global miku_volume_hud_overlay_enabled (default 1).
 */
class MikuVolumeHud(
    private val ctx: Context,
    private val windowManager: WindowManager
) {
    companion object {
        const val TAG = "MikuVolumeHud"
        const val GLOBAL_ENABLED = "miku_volume_hud_overlay_enabled"
        private const val AUTO_HIDE_MS = 2400L
        private const val WIDTH_DP = 46f
        private const val LAUNCHER_PKG = "com.miku.launcher"
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var view: BarView? = null
    private val hideRunnable = Runnable { hide() }

    private fun isEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getInt(ctx.contentResolver, GLOBAL_ENABLED, 1) == 1
    }.getOrDefault(true)

    private fun suppressed(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return true
        val top = runCatching { MikuTaskStack.topTask(ctx)?.second }.getOrNull()
        val cls = top?.className ?: ""
        if (top?.packageName == ctx.packageName) return true          // shade / recents / power menu
        if (top?.packageName == LAUNCHER_PKG) return true             // launcher has its own HUD
        if (cls.contains("Lockscreen", true) || cls.contains("Aod", true)) return true
        return false
    }

    /** Call on every VOLUME_CHANGED_ACTION. Reads live STREAM_MUSIC state itself. */
    fun onVolumeChanged() {
        if (!isEnabled() || suppressed()) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = cur == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)
        val pct = ((cur * 100f) / max).toInt().coerceIn(0, 100)
        main.post {
            val v = view ?: BarView(ctx).also { bv ->
                val dm = ctx.resources.displayMetrics
                val params = WindowManager.LayoutParams(
                    dp(WIDTH_DP).toInt(), (dm.heightPixels * 0.56f).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                try { windowManager.addView(bv, params) } catch (t: Throwable) {
                    Log.w(TAG, "addView: $t"); return@post
                }
                view = bv
            }
            v.bind(pct, muted)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, AUTO_HIDE_MS)
        }
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    fun destroy() = hide()

    private inner class BarView(c: Context) : View(c) {
        private var pct = 50
        private var muted = false
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 8, 26, 34) }
        private val trackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(170, 0, 229, 255)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(10.5f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        fun bind(p: Int, m: Boolean) { pct = p; muted = m; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val barW = dp(10f)
            val cx = w - dp(14f)                       // hug the right edge
            val top = dp(30f); val bottom = h - dp(10f)
            val r = RectF(cx - barW / 2, top, cx + barW / 2, bottom)
            canvas.drawRoundRect(r, barW / 2, barW / 2, track)
            canvas.drawRoundRect(r, barW / 2, barW / 2, trackEdge)
            val frac = pct / 100f
            if (frac > 0f && !muted) {
                val fTop = bottom - (bottom - top) * frac
                fill.shader = LinearGradient(
                    0f, bottom, 0f, fTop,
                    Color.rgb(0, 229, 255), Color.rgb(255, 64, 190), Shader.TileMode.CLAMP
                )
                val fr = RectF(r.left + dp(1.5f), fTop, r.right - dp(1.5f), bottom - dp(1.5f))
                canvas.drawRoundRect(fr, barW / 2, barW / 2, fill)
            }
            canvas.drawText(if (muted) "MUTE" else "$pct", cx, top - dp(10f), txt)
        }
    }
}
