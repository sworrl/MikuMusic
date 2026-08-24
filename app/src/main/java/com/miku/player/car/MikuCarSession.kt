package com.miku.player.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class MikuCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MikuCarPlayerScreen(carContext)
    }
}
