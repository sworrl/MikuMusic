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

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initTouchInterceptor()
        initPullHandle()
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER) {
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
                
                Handler(Looper.getMainLooper()).postDelayed({
                    openPowerMenuActivity()
                }, 150)
                return
            }

            if (cls.contains("NotificationShade", ignoreCase = true) ||
                cls.contains("QuickSettings", ignoreCase = true) ||
                cls.contains("StatusBarWindow", ignoreCase = true)) {
                openShadeActivity()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        touchInterceptView?.let { runCatching { windowManager.removeView(it) } }
        pullHandleView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }
}
