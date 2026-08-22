package com.m500.hardware

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

object PocketLockOverlay {
    private const val TAG = "PocketLockOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var isShowing = false
    private var lastTapTime = 0L

    fun show(context: Context) {
        mainHandler.post {
            try {
                if (isShowing && overlayView != null) return@post
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.FILL
                }

                val lifecycleOwner = OverlayLifecycleOwner()
                lifecycleOwner.performRestore(null)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                val composeView = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                    isClickable = true
                    isFocusable = true
                    setContent {
                        val mode = PocketLockManager.getFnMode(context)
                        val allowVol = PocketLockManager.isAllowVolumeWheel(context)
                        CyberPocketHudScreen(mode = mode, allowVolumeWheel = allowVol)
                    }
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 350) {
                                Log.i(TAG, "Emergency double-tap dismiss triggered")
                                hide(context)
                            }
                            lastTapTime = now
                        }
                        // Absorb and consume 100% of touches
                        true
                    }
                }

                wm.addView(composeView, params)
                overlayView = composeView
                isShowing = true
                Log.i(TAG, "Pocket Lock WindowManager Touch Shield ADDED and ACTIVE")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to add Pocket Lock WindowManager overlay", e)
            }
        }
    }

    fun hide(context: Context) {
        mainHandler.post {
            try {
                if (!isShowing || overlayView == null) return@post
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                wm.removeView(overlayView)
                overlayView = null
                isShowing = false
                Log.i(TAG, "Pocket Lock WindowManager Touch Shield REMOVED")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to remove Pocket Lock WindowManager overlay", e)
            }
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun performRestore(savedState: android.os.Bundle?) {
            savedStateRegistryController.performRestore(savedState)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
