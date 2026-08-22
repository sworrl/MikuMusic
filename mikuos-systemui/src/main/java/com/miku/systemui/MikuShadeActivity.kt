package com.miku.systemui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MikuShadeActivity : ComponentActivity() {

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
        try {
            overridePendingTransition(R.anim.slide_down_in, R.anim.fade_out)
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
            MikuNotificationShadeView(
                onDismiss = { finish() },
                onOpenSettings = {
                    try {
                        val intent = packageManager.getLaunchIntentForPackage("com.miku.settings")
                        if (intent != null) startActivity(intent)
                    } catch (_: Throwable) {}
                }
            )
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
