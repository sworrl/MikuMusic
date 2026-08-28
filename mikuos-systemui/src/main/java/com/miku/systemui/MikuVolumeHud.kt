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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * MikuOS volume HUD drawn in the accessibility-overlay layer so it appears over ANY app (Spotify,
 * games, AOSP settings) — the launcher's Compose HUD only exists inside launcher windows. Honors
 * the user's chosen style (Settings.Global miku_volume_hud_style, mirrored by the launcher):
 *   right_bar     — a fuller right-edge neon glass bar (default)
 *   center_modal  — a centered holographic "Miku Music" modal
 * Suppressed while screen off / keyguard / MikuOS lock / one of our own surfaces (which draw their
 * own volume UI). Gated by Settings.Global miku_volume_hud_overlay_enabled (default 1).
 */
class MikuVolumeHud(
    private val ctx: Context,
    private val windowManager: WindowManager
) {
    companion object {
        const val TAG = "MikuVolumeHud"
        const val GLOBAL_ENABLED = "miku_volume_hud_overlay_enabled"
        const val KEY_STYLE = "miku_volume_hud_style"
        private const val AUTO_HIDE_MS = 2400L
        private const val LAUNCHER_PKG = "com.miku.launcher"
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var view: HudView? = null
    private var currentStyle = "right_bar"
    private val hideRunnable = Runnable { hide() }

    private fun isEnabled() = runCatching {
        Settings.Global.getInt(ctx.contentResolver, GLOBAL_ENABLED, 1) == 1
    }.getOrDefault(true)

    private fun style() = runCatching {
        Settings.Global.getString(ctx.contentResolver, KEY_STYLE) ?: "right_bar"
    }.getOrDefault("right_bar")

    private fun suppressed(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return true
        val top = runCatching { MikuTaskStack.topTask(ctx)?.second }.getOrNull()
        val cls = top?.className ?: ""
        if (top?.packageName == ctx.packageName) return true
        if (top?.packageName == LAUNCHER_PKG) return true
        if (cls.contains("Lockscreen", true) || cls.contains("Aod", true)) return true
        return false
    }

    fun onVolumeChanged() {
        if (!isEnabled() || suppressed()) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = cur == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)
        val pct = ((cur * 100f) / max).toInt().coerceIn(0, 100)
        val st = style()
        main.post {
            // Rebuild if the style changed since last shown (window geometry differs).
            if (view != null && st != currentStyle) hide()
            currentStyle = st
            val v = view ?: HudView(ctx, st == "center_modal").also { hv ->
                val dm = ctx.resources.displayMetrics
                val params = if (st == "center_modal") {
                    WindowManager.LayoutParams(
                        dp(248f).toInt(), dp(104f).toInt(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags(), PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.CENTER }
                } else {
                    WindowManager.LayoutParams(
                        dp(70f).toInt(), (dm.heightPixels * 0.5f).toInt(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags(), PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                }
                runCatching { windowManager.addView(hv, params) }.onFailure { Log.w(TAG, "addView: $it"); return@post }
                view = hv
            }
            v.bind(pct, muted)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, AUTO_HIDE_MS)
        }
    }

    private fun flags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    fun hide() {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    fun destroy() = hide()

    private inner class HudView(c: Context, val modal: Boolean) : View(c) {
        private var pct = 50
        private var muted = false
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 8, 20, 26) }
        private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 16, 40, 48) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        fun bind(p: Int, m: Boolean) { pct = p; muted = m; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cyan = Color.rgb(0, 229, 255); val pink = Color.rgb(255, 64, 190)
            panelEdge.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), cyan, pink, Shader.TileMode.CLAMP)
            if (modal) drawModal(canvas, cyan, pink) else drawBar(canvas, cyan, pink)
        }

        private fun drawModal(canvas: Canvas, cyan: Int, pink: Int) {
            val pad = dp(6f)
            val r = RectF(pad, pad, width - pad, height - pad)
            canvas.drawRoundRect(r, dp(20f), dp(20f), panel)
            canvas.drawRoundRect(r, dp(20f), dp(20f), panelEdge)
            txt.textSize = dp(13f); txt.textAlign = Paint.Align.LEFT
            canvas.drawText(if (muted) "MUTED" else "VOLUME", r.left + dp(18f), r.top + dp(26f), txt)
            txt.textAlign = Paint.Align.RIGHT; txt.textSize = dp(20f)
            canvas.drawText(if (muted) "—" else "$pct%", r.right - dp(18f), r.top + dp(28f), txt)
            // horizontal fill bar
            val by = r.bottom - dp(24f); val bh = dp(12f)
            val bl = r.left + dp(18f); val br = r.right - dp(18f)
            val bg = RectF(bl, by, br, by + bh)
            canvas.drawRoundRect(bg, bh / 2, bh / 2, track)
            if (!muted && pct > 0) {
                val fr = RectF(bl, by, bl + (br - bl) * (pct / 100f), by + bh)
                fill.shader = LinearGradient(bl, 0f, br, 0f, cyan, pink, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(fr, bh / 2, bh / 2, fill)
            }
        }

        private fun drawBar(canvas: Canvas, cyan: Int, pink: Int) {
            val w = width.toFloat(); val h = height.toFloat()
            // rounded glass panel hugging the right edge
            val panelW = dp(40f)
            val pr = RectF(w - panelW - dp(6f), dp(6f), w - dp(6f), h - dp(6f))
            canvas.drawRoundRect(pr, dp(20f), dp(20f), panel)
            canvas.drawRoundRect(pr, dp(20f), dp(20f), panelEdge)
            // vertical track + fill
            val barW = dp(16f)
            val cx = pr.centerX()
            val top = pr.top + dp(30f); val bottom = pr.bottom - dp(30f)
            val tr = RectF(cx - barW / 2, top, cx + barW / 2, bottom)
            canvas.drawRoundRect(tr, barW / 2, barW / 2, track)
            if (!muted && pct > 0) {
                val fTop = bottom - (bottom - top) * (pct / 100f)
                fill.shader = LinearGradient(0f, bottom, 0f, top, cyan, pink, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(tr.left, fTop, tr.right, bottom), barW / 2, barW / 2, fill)
            }
            txt.textAlign = Paint.Align.CENTER; txt.textSize = dp(12f)
            canvas.drawText(if (muted) "✕" else "$pct", cx, pr.top + dp(20f), txt)
        }
    }
}
