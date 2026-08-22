package com.miku.systemui

import android.content.Intent
import android.service.quicksettings.TileService

class MikuShadeTileService : TileService() {
    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, MikuShadeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivityAndCollapse(intent)
        } catch (_: Throwable) {}
    }
}
