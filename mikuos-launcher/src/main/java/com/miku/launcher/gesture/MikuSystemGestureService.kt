package com.miku.launcher.gesture
import com.miku.launcher.*

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.miku.launcher.R
import com.miku.launcher.RootShell
import com.miku.launcher.MikuLauncherActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hatsune Miku System-Wide Gesture Navigation Overlay Service.
 * Provides seamless, hardware-accelerated Pixel-style gesture navigation (Home / Recents)
 * running persistently above all third-party and system apps (Magisk, Spotify, Settings, Chrome, etc.).
 */
class MikuSystemGestureService : Service() {

    private var windowManager: WindowManager? = null
    private var gesturePillView: GesturePillView? = null
    private var topShadeInterceptView: View? = null
    private var leftEdgeInterceptView: View? = null
    private var rightEdgeInterceptView: View? = null

    companion object {
        private const val TAG = "MikuGestureNav"

        fun start(context: Context) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                RootShell.execFast(
                    "appops set com.miku.launcher SYSTEM_ALERT_WINDOW allow; " +
                    "setprop qemu.hw.mainkeys 1; " +
                    "settings put global policy_control immersive.full=*; " +
                    "cmd overlay enable com.android.internal.systemui.navbar.gestural 2>/dev/null; " +
                    "cmd overlay disable com.android.internal.systemui.navbar.threebutton 2>/dev/null; " +
                    "settings put secure navigation_mode 2; " +
                    "settings put secure back_gesture_inset_scale_left 2; " +
                    "settings put secure back_gesture_inset_scale_right 2"
                )
                try {
                    val intent = Intent(appCtx, MikuSystemGestureService::class.java)
                    appCtx.startService(intent)
                } catch (_: Throwable) {}
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MikuSystemGestureService::class.java))
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupGestureOverlay()
        setupTopShadeOverlay()
        setupEdgeBackOverlays()
    }

    private fun setupTopShadeOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val density = resources.displayMetrics.density
        val topBarHeightPx = (24 * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            topBarHeightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }

        val topView = View(this).apply {
            var startY = 0f
            var isSwipeDetected = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        isSwipeDetected = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        if (dy > 20 * density && !isSwipeDetected) {
                            isSwipeDetected = true
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerOpenShade()
                            true
                        } else {
                            true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isSwipeDetected = false
                        false
                    }
                    else -> false
                }
            }
        }
        topShadeInterceptView = topView
        try {
            wm.addView(topView, params)
        } catch (_: Throwable) {}
    }

    private fun triggerOpenShade() {
        try {
            val intent = Intent().setClassName("com.miku.systemui", "com.miku.systemui.MikuShadeActivity").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.miku.systemui")
                if (intent != null) startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun setupGestureOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val barHeightPx = (32 * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }

        val view = GesturePillView(this) { action ->
            when (action) {
                GestureAction.HOME -> triggerHome()
                GestureAction.RECENTS -> triggerRecents()
            }
        }
        gesturePillView = view

        try {
            windowManager?.addView(view, params)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to add gesture pill overlay", t)
        }
    }

    private fun triggerHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            val opts = android.app.ActivityOptions.makeCustomAnimation(
                this,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            startActivity(intent, opts.toBundle())
        } catch (_: Throwable) {}
    }

    private fun triggerRecents() {
        val intent = Intent(this, MikuLauncherActivity::class.java).apply {
            putExtra("open_recents", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            val opts = android.app.ActivityOptions.makeCustomAnimation(
                this,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            startActivity(intent, opts.toBundle())
        } catch (_: Throwable) {}
    }

    private fun setupEdgeBackOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val density = resources.displayMetrics.density
        val edgeWidthPx = (24 * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Left Edge Back Overlay (Pixel-style inward swipe back)
        val leftParams = WindowManager.LayoutParams(
            edgeWidthPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        val leftView = View(this).apply {
            var startX = 0f
            var startY = 0f
            var isTriggered = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isTriggered = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = Math.abs(event.rawY - startY)
                        if (dx > 28 * density && dy < 1.8f * dx && !isTriggered) {
                            isTriggered = true
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerBack()
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isTriggered = false
                        false
                    }
                    else -> false
                }
            }
        }
        leftEdgeInterceptView = leftView
        try {
            wm.addView(leftView, leftParams)
        } catch (_: Throwable) {}

        // Right Edge Back Overlay (Pixel-style inward swipe back)
        val rightParams = WindowManager.LayoutParams(
            edgeWidthPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        val rightView = View(this).apply {
            var startX = 0f
            var startY = 0f
            var isTriggered = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isTriggered = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = startX - event.rawX
                        val dy = Math.abs(event.rawY - startY)
                        if (dx > 28 * density && dy < 1.8f * dx && !isTriggered) {
                            isTriggered = true
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerBack()
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isTriggered = false
                        false
                        }
                    else -> false
                }
            }
        }
        rightEdgeInterceptView = rightView
        try {
            wm.addView(rightView, rightParams)
        } catch (_: Throwable) {}
    }

    private fun triggerBack() {
        CoroutineScope(Dispatchers.IO).launch {
            RootShell.execFast("input keyevent 4")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gesturePillView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        gesturePillView = null

        topShadeInterceptView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        topShadeInterceptView = null

        leftEdgeInterceptView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        leftEdgeInterceptView = null

        rightEdgeInterceptView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        rightEdgeInterceptView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    enum class GestureAction {
        HOME, RECENTS
    }

    class GesturePillView(
        context: Context,
        private val onAction: (GestureAction) -> Unit
    ) : View(context) {

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.parseColor("#4D00E5FF")
        }

        private var startY = 0f
        private var startX = 0f
        private var currentY = 0f
        private var currentX = 0f
        private var startTime = 0L
        private var isDragging = false
        private var isHoldArmed = false

        private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val holdRunnable = Runnable {
            if (isDragging && (currentY - startY) < -18f) {
                isHoldArmed = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

        private val density = resources.displayMetrics.density
        private val pillWidthPx = 76 * density
        private val pillHeightPx = 4.5f * density
        private val pillRadiusPx = 2.5f * density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val offsetY = if (isDragging) (currentY - startY).coerceAtMost(0f) * 0.4f else 0f
            val pillLeft = (w - pillWidthPx) / 2f
            val pillTop = h - pillHeightPx - (6 * density) + offsetY
            val pillRight = pillLeft + pillWidthPx
            val pillBottom = pillTop + pillHeightPx

            val rect = RectF(pillLeft, pillTop, pillRight, pillBottom)

            // Dynamic Gradient: Cyan to Neon Pink (or full pink when hold armed)
            val startColor = if (isHoldArmed) Color.parseColor("#FFFF4081") else Color.parseColor("#00E5FF")
            val endColor = Color.parseColor("#FFFF4081")

            val shader = android.graphics.LinearGradient(
                pillLeft, pillTop, pillRight, pillBottom,
                intArrayOf(startColor, endColor),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
            pillPaint.shader = shader

            // Outer subtle glow
            canvas.drawRoundRect(
                RectF(pillLeft - 1f, pillTop - 1f, pillRight + 1f, pillBottom + 1f),
                pillRadiusPx + 1f,
                pillRadiusPx + 1f,
                glowPaint
            )

            // Main pill body
            canvas.drawRoundRect(rect, pillRadiusPx, pillRadiusPx, pillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat()
            val pillLeft = (w - pillWidthPx) / 2f - (40 * density)
            val pillRight = (w + pillWidthPx) / 2f + (40 * density)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x < pillLeft || event.x > pillRight) {
                        return false
                    }
                    startX = event.rawX
                    startY = event.rawY
                    currentX = event.rawX
                    currentY = event.rawY
                    startTime = SystemClock.elapsedRealtime()
                    isDragging = true
                    isHoldArmed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    holdHandler.postDelayed(holdRunnable, 300L)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return false
                    currentX = event.rawX
                    currentY = event.rawY
                    val deltaY = currentY - startY
                    if (deltaY >= -18f) {
                        holdHandler.removeCallbacks(holdRunnable)
                        holdHandler.postDelayed(holdRunnable, 300L)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return false
                    isDragging = false
                    isHoldArmed = false
                    holdHandler.removeCallbacks(holdRunnable)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) return false
                    isDragging = false
                    holdHandler.removeCallbacks(holdRunnable)
                    val deltaY = currentY - startY
                    val duration = SystemClock.elapsedRealtime() - startTime
                    invalidate()

                    if (deltaY < -14f) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (isHoldArmed || duration >= 300L) {
                            onAction(GestureAction.RECENTS)
                        } else {
                            onAction(GestureAction.HOME)
                        }
                    } else if (Math.abs(deltaY) < 12f && duration < 300L) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onAction(GestureAction.HOME)
                    }
                    isHoldArmed = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
