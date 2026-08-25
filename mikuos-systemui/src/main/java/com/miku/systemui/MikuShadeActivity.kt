package com.miku.systemui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MikuShadeActivity : ComponentActivity() {
    companion object {
        /** Pixels the finger had already pulled down when the nav service opened us — the
         *  panel starts that far exposed and finishes the slide, so it appears to follow. */
        const val EXTRA_DRAG_OFFSET_PX = "miku.shade.drag_offset_px"
        /** Open straight into the full quick-settings grid (second pull / QS tile). */
        const val EXTRA_START_EXPANDED = "miku.shade.start_expanded"
    }

    private fun hideSystemBars() {
        try {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dragOffset = intent?.getIntExtra(EXTRA_DRAG_OFFSET_PX, -1) ?: -1
        try {
            if (dragOffset >= 0) overridePendingTransition(0, 0)
            else overridePendingTransition(R.anim.slide_down_in, R.anim.fade_out)
        } catch (_: Throwable) {}

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        hideSystemBars()

        setContent {
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            val startY = if (dragOffset >= 0) -(screenH * 0.94f - dragOffset).coerceAtLeast(0f) else 0f
            val ty = remember { androidx.compose.animation.core.Animatable(startY) }
            LaunchedEffect(Unit) {
                if (startY != 0f) ty.animateTo(
                    0f,
                    androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = ty.value }) {
                MikuNotificationShadeView(
                    onDismiss = { finish() },
                    onOpenSettings = {
                        try {
                            val intent = packageManager.getLaunchIntentForPackage("com.miku.settings")
                            if (intent != null) startActivity(intent)
                        } catch (_: Throwable) {}
                    },
                    onOpenPower = {
                        try {
                            startActivity(Intent(this@MikuShadeActivity, MikuPowerMenuActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            })
                        } catch (_: Throwable) {}
                    },
                    startExpanded = intent?.getBooleanExtra(EXTRA_START_EXPANDED, false) == true
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        try {
            overridePendingTransition(R.anim.fade_in, R.anim.slide_up_out)
        } catch (_: Throwable) {}
    }
}
