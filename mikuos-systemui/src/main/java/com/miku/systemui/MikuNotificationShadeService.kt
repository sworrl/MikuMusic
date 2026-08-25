package com.miku.systemui

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

class MikuNotificationShadeService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var touchInterceptView: View? = null
    private var pullHandleView: View? = null
    private var leftEdgeView: View? = null
    private var rightEdgeView: View? = null

    private val backReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == "com.miku.systemui.action.TRIGGER_BACK") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initTouchInterceptor()
        initPullHandle()
        initEdgeBackGestures()
        try {
            val filter = android.content.IntentFilter("com.miku.systemui.action.TRIGGER_BACK")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(backReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(backReceiver, filter)
            }
        } catch (_: Throwable) {}
    }

    private fun openShadeActivity() {
        try {
            val intent = Intent(this, MikuShadeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        } catch (_: Throwable) {
            try {
                val intent = Intent(this, MikuShadeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun initTouchInterceptor() {
        touchInterceptView = View(this).apply {
            var startY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        if (dy > 15f) {
                            openShadeActivity()
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val dy = event.rawY - startY
                        if (dy > 15f) {
                            openShadeActivity()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            setOnClickListener {
                openShadeActivity()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            96,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        try {
            windowManager.addView(touchInterceptView, params)
        } catch (_: Throwable) {}
    }

    private fun initPullHandle() {
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF00E5FF.toInt()) // Miku Cyan Bright
                cornerRadius = 8f
            }
        }

        val container = FrameLayout(this).apply {
            val pillParams = FrameLayout.LayoutParams(160, 12).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 2
            }
            addView(pill, pillParams)

            var startY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        if (dy > 8f || event.action == MotionEvent.ACTION_UP) {
                            openShadeActivity()
                        }
                        true
                    }
                    else -> true
                }
            }
            setOnClickListener {
                openShadeActivity()
            }
        }

        val handleParams = WindowManager.LayoutParams(
            280,
            56,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        pullHandleView = container

        try {
            windowManager.addView(pullHandleView, handleParams)
        } catch (_: Throwable) {}
    }

    private fun openPowerMenuActivity() {
        try {
            val intent = Intent(this, MikuPowerMenuActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            val pi = PendingIntent.getActivity(
                this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        } catch (_: Throwable) {
            try {
                val intent = Intent(this, MikuPowerMenuActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    private var powerDownTimestamp = 0L

    /**
     * True when the framework itself routes long-press-power to MikuPowerMenuActivity
     * (Settings.Global power_button_long_press = 5 = LONG_PRESS_POWER_ASSISTANT with
     * Settings.Secure assistant = our component — seeded by the ROM rc). In that mode this
     * accessibility key filter must stand down, otherwise one hold fires the modal twice.
     */
    private fun frameworkOwnsPowerLongPress(): Boolean = try {
        android.provider.Settings.Global.getInt(contentResolver, "power_button_long_press", 0) == 5 &&
            (android.provider.Settings.Secure.getString(contentResolver, "assistant") ?: "")
                .startsWith("com.miku.systemui/")
    } catch (_: Throwable) { false }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER && !frameworkOwnsPowerLongPress()) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (powerDownTimestamp == 0L) {
                    powerDownTimestamp = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - powerDownTimestamp > 450L) {
                    openPowerMenuActivity()
                    powerDownTimestamp = 0L
                    return true
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                powerDownTimestamp = 0L
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString() ?: ""
            val pkg = event.packageName?.toString() ?: ""
            if (cls.contains("GlobalActions", ignoreCase = true) ||
                cls.contains("PowerDialog", ignoreCase = true) ||
                cls.contains("ShutdownActivity", ignoreCase = true) ||
                (pkg.contains("android") && cls.contains("GlobalActionsDialog", ignoreCase = true))) {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Throwable) {}
                
                openPowerMenuActivity()
                return
            }

            if (cls.contains("NotificationShade", ignoreCase = true) ||
                cls.contains("QuickSettings", ignoreCase = true) ||
                cls.contains("StatusBarWindow", ignoreCase = true)) {
                openShadeActivity()
            }
        }
    }

    
    enum class GestureAction { HOME, RECENTS, QUICK_SWITCH }

    private var gesturePillView: GesturePillView? = null

    private fun triggerHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun triggerRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private fun initEdgeBackGestures() {
        val density = resources.displayMetrics.density
        val edgeWidthPx = (32 * density).toInt()

        leftEdgeView = EdgeBackView(this, isLeft = true) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        val leftParams = WindowManager.LayoutParams(
            edgeWidthPx, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; x = 0; y = 0 }
        try { windowManager.addView(leftEdgeView, leftParams) } catch (e: Throwable) {}

        rightEdgeView = EdgeBackView(this, isLeft = false) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        val rightParams = WindowManager.LayoutParams(
            edgeWidthPx, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = 0; y = 0 }
        try { windowManager.addView(rightEdgeView, rightParams) } catch (e: Throwable) {}

        // Add Bottom Gesture Pill
        val barHeightPx = (32 * density).toInt()
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, barHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 0 }

        gesturePillView = GesturePillView(this) { action ->
            when (action) {
                GestureAction.HOME -> triggerHome()
                GestureAction.RECENTS -> triggerRecents()
                GestureAction.QUICK_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
        try { windowManager.addView(gesturePillView, pillParams) } catch (e: Throwable) {}
    }

    class EdgeBackView(
        context: Context,
        private val isLeft: Boolean,
        private val onBackTriggered: () -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var isTracking = false
        private var isTriggered = false
        private val heartPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.parseColor("#39C5BB")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 28f * density
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.WHITE)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!isTracking) return
            val dx = if (isLeft) (currentX - startX).coerceAtLeast(0f) else (startX - currentX).coerceAtLeast(0f)
            val maxDrag = 40f * density
            val progress = (dx / maxDrag).coerceIn(0f, 1f)
            val centerY = currentY.coerceIn(60f * density, height - 60f * density)
            
            // Only draw a cute glowing kawaii heart moving inwards
            if (progress > 0.1f) {
                val heartX = if (isLeft) (progress * 30f * density) else width - (progress * 30f * density)
                heartPaint.alpha = (progress * 255).toInt()
                canvas.drawText("♥", heartX, centerY + 10f * density, heartPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    currentX = event.rawX; currentY = event.rawY
                    isTracking = true; isTriggered = false
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTracking) return false
                    currentX = event.rawX; currentY = event.rawY
                    val dx = if (isLeft) (event.rawX - startX) else (startX - event.rawX)
                    val dy = Math.abs(event.rawY - startY)
                    if (dx > 16f * density && dy < 2.2f * dx && !isTriggered) {
                        isTriggered = true
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isTracking && isTriggered) {
                        onBackTriggered()
                    }
                    isTracking = false; isTriggered = false
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isTracking = false; isTriggered = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    class GesturePillView(context: Context, private val onAction: (GestureAction) -> Unit) : View(context) {
        private val pillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.WHITE
        }
        private var startY = 0f; private var startX = 0f
        private var currentY = 0f; private var currentX = 0f
        private var startTime = 0L; private var isDragging = false

        private val density = resources.displayMetrics.density
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val offsetY = if (isDragging) (currentY - startY).coerceAtMost(0f) * 0.4f else 0f
            val pillWidth = 76 * density
            val pillHeight = 4f * density
            val r = 2f * density
            val left = (w - pillWidth) / 2f
            val top = h - pillHeight - (6 * density) + offsetY
            canvas.drawRoundRect(left, top, left + pillWidth, top + pillHeight, r, r, pillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    currentX = event.rawX; currentY = event.rawY
                    startTime = android.os.SystemClock.elapsedRealtime()
                    isDragging = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return false
                    currentX = event.rawX; currentY = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return false
                    isDragging = false
                    val deltaY = currentY - startY
                    val deltaX = currentX - startX
                    val duration = android.os.SystemClock.elapsedRealtime() - startTime
                    invalidate()
                    if (deltaY < -14f * density) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        if (duration >= 250L) onAction(GestureAction.RECENTS) else onAction(GestureAction.HOME)
                    } else if (Math.abs(deltaX) > 36f * density && Math.abs(deltaY) < 18f * density) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        onAction(GestureAction.QUICK_SWITCH)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(backReceiver) } catch (_: Throwable) {}
        touchInterceptView?.let { runCatching { windowManager.removeView(it) } }
        pullHandleView?.let { runCatching { windowManager.removeView(it) } }
        leftEdgeView?.let { runCatching { windowManager.removeView(it) } }
        rightEdgeView?.let { runCatching { windowManager.removeView(it) } }
        gesturePillView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }
}

